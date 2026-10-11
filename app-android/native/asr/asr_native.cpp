// In-process audio decoding and SenseVoice features for the recording helper.
//
// Decoding through MediaCodec costs several cross-process round trips per compressed frame (the extractor and the
// software codecs live in media.extractor / media.swcodec), which for a 6-minute 48 kHz MP3 was ~40 s of a ~50 s
// transcription. Here the file is memory-mapped and decoded in the app process:
//   WAV  (PCM 8/16/24/32-bit, IEEE float 32-bit, incl. WAVE_FORMAT_EXTENSIBLE)
//   MP3  (minimp3, CC0)
//   AMR-NB / AMR-WB storage format, "#!AMR\n" / "#!AMR-WB\n", and AMR inside 3GP (opencore-amr, Apache-2.0)
//   AAC in M4A / MP4 and ADTS .aac (fdk-aac); an AAC config fdk-aac rejects is only demuxed here and decoded by
//   MediaCodec via nextSample()
// Anything else returns no handle and the caller falls back to MediaExtractor + MediaCodec.
// Output is mono (channels averaged) on the int16 scale at the file's own sample rate.
//
// features() is the C++ twin of Frontend.kt: kaldi fbank (25 ms hamming, 10 ms shift, DC removal, preemphasis
// 0.97, 512-point power spectrum, 80 mel bins 20 Hz - 8 kHz, log) -> LFR (7 frames every 6) -> CMVN.
#include <fcntl.h>
#include <jni.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

#define MINIMP3_IMPLEMENTATION
#include "minimp3.h"

#include "interf_dec.h"  // AMR-NB: Decoder_Interface_*
#include "dec_if.h"      // AMR-WB: D_IF_*
#include "aacdecoder_lib.h"

namespace {

// MP4_AAC: an M4A/MP4 file with AAC audio fdk-aac cannot configure; only demuxed here (nextSample + aacConfig),
// MediaCodec decodes. AAC: decoded here (MP4 sample table, or ADTS when there is no table).
enum Kind { WAV = 1, MP3 = 2, AMR_NB = 3, AMR_WB = 4, MP4_AAC = 5, AAC = 6 };

struct Source {
    Kind kind;
    const uint8_t* data = nullptr;
    size_t size = 0;
    size_t pos = 0;
    size_t end = 0;  // end of the audio payload
    int rate = 0;
    int channels = 1;
    // WAV
    int bits = 16;
    bool isFloat = false;
    // MP3
    mp3dec_t mp3{};
    // AMR
    void* amr = nullptr;
    // MP4: per-sample offsets and sizes of the audio track (AMR in 3GP: one entry per AMR frame)
    std::vector<uint64_t> sampleOff;
    std::vector<uint32_t> sampleSize;
    size_t sampleIdx = 0;
    std::vector<uint8_t> asc;  // AAC AudioSpecificConfig (csd-0)
    HANDLE_AACDECODER aac = nullptr;
    std::vector<INT_PCM> aacPcm;
    // decoded but not yet returned
    std::vector<float> pending;
    size_t pendingPos = 0;
};

uint16_t le16(const uint8_t* p) { return uint16_t(p[0] | p[1] << 8); }
uint32_t le32(const uint8_t* p) { return uint32_t(p[0] | p[1] << 8 | p[2] << 16 | uint32_t(p[3]) << 24); }
uint16_t be16(const uint8_t* p) { return uint16_t(p[0] << 8 | p[1]); }
uint32_t be32(const uint8_t* p) { return uint32_t(p[0]) << 24 | uint32_t(p[1]) << 16 | uint32_t(p[2]) << 8 | p[3]; }
uint64_t be64(const uint8_t* p) { return uint64_t(be32(p)) << 32 | be32(p + 4); }

// ---- MP4 / 3GP ----
// Walks moov/trak/mdia/minf/stbl of the first sound track: sample entry (mp4a with esds, or samr/sawb), sample
// sizes (stsz/stz2 not needed for these files: stsz), chunk offsets (stco/co64) and sample-to-chunk map (stsc).
struct Mp4Track {
    char fourcc[5] = {0};
    int rate = 0, channels = 0;
    std::vector<uint8_t> asc;
    std::vector<uint32_t> sizes;
    std::vector<uint64_t> chunkOff;
    std::vector<uint32_t> stscFirst, stscCount;
    bool sound = false;
};

// Iterates child boxes in [p, end): calls f(type, body, bodyEnd). Returns false on a malformed box.
template <class F>
bool boxes(const uint8_t* p, const uint8_t* end, F f) {
    while (p + 8 <= end) {
        uint64_t sz = be32(p);
        const uint8_t* body = p + 8;
        if (sz == 1) {
            if (p + 16 > end) return false;
            sz = be64(p + 8);
            body = p + 16;
        } else if (sz == 0) {
            sz = uint64_t(end - p);
        }
        if (sz < uint64_t(body - p) || sz > uint64_t(end - p)) return false;
        if (!f(p + 4, body, p + sz)) return false;
        p += sz;
    }
    return true;
}

// ES descriptor length: 1-4 bytes, 7 bits each.
size_t descLen(const uint8_t*& p, const uint8_t* end) {
    size_t n = 0;
    for (int i = 0; i < 4 && p < end; ++i) {
        uint8_t b = *p++;
        n = n << 7 | (b & 0x7f);
        if (!(b & 0x80)) break;
    }
    return n;
}

void parseEsds(const uint8_t* p, const uint8_t* end, Mp4Track& t) {
    p += 4;  // version + flags
    while (p + 2 <= end) {
        uint8_t tag = *p++;
        size_t len = descLen(p, end);
        if (tag == 0x03) {  // ES_Descriptor: ES_ID(2) flags(1) [depends(2)] [url] [ocr(2)] then sub-descriptors
            if (p + 3 > end) return;
            uint8_t flags = p[2];
            p += 3;
            if (flags & 0x80) p += 2;
            if (flags & 0x40 && p < end) p += 1 + *p;
            if (flags & 0x20) p += 2;
        } else if (tag == 0x04) {  // DecoderConfigDescriptor: 13 fixed bytes then DecoderSpecificInfo
            p += 13;
        } else if (tag == 0x05) {  // DecoderSpecificInfo = AudioSpecificConfig
            if (p + len <= end) t.asc.assign(p, p + len);
            return;
        } else {
            p += len;
        }
    }
}

bool parseStbl(const uint8_t* b, const uint8_t* e, Mp4Track& t) {
    return boxes(b, e, [&](const uint8_t* type, const uint8_t* body, const uint8_t* end) {
        if (memcmp(type, "stsd", 4) == 0 && body + 8 <= end) {
            const uint8_t* entry = body + 8;  // version/flags + entry count, then the first sample entry box
            if (entry + 8 + 28 > end) return true;
            memcpy(t.fourcc, entry + 4, 4);
            const uint8_t* se = entry + 8;  // SampleEntry(8) + AudioSampleEntry v0 (20)
            t.channels = be16(se + 16);
            t.rate = int(be32(se + 24) >> 16);
            const uint8_t* children = se + 28;
            int ver = be16(se + 8);  // QuickTime sound description versions carry extra fields
            if (ver == 1) children += 16;
            else if (ver == 2) children += 36;
            const uint8_t* entryEnd = entry + be32(entry);
            if (entryEnd > end) entryEnd = end;
            boxes(children, entryEnd, [&](const uint8_t* ct, const uint8_t* cb, const uint8_t* ce) {
                if (memcmp(ct, "esds", 4) == 0) parseEsds(cb, ce, t);
                return true;
            });
        } else if (memcmp(type, "stsz", 4) == 0 && body + 12 <= end) {
            uint32_t fixed = be32(body + 4), n = be32(body + 8);
            if (fixed) t.sizes.assign(n, fixed);
            else for (uint32_t i = 0; i < n && body + 12 + 4 * (i + 1) <= end; ++i) t.sizes.push_back(be32(body + 12 + 4 * i));
        } else if (memcmp(type, "stco", 4) == 0 && body + 8 <= end) {
            uint32_t n = be32(body + 4);
            for (uint32_t i = 0; i < n && body + 8 + 4 * (i + 1) <= end; ++i) t.chunkOff.push_back(be32(body + 8 + 4 * i));
        } else if (memcmp(type, "co64", 4) == 0 && body + 8 <= end) {
            uint32_t n = be32(body + 4);
            for (uint32_t i = 0; i < n && body + 8 + 8 * (i + 1) <= end; ++i) t.chunkOff.push_back(be64(body + 8 + 8 * i));
        } else if (memcmp(type, "stsc", 4) == 0 && body + 8 <= end) {
            uint32_t n = be32(body + 4);
            for (uint32_t i = 0; i < n && body + 8 + 12 * (i + 1) <= end; ++i) {
                t.stscFirst.push_back(be32(body + 8 + 12 * i));
                t.stscCount.push_back(be32(body + 8 + 12 * i + 4));
            }
        }
        return true;
    });
}

// First sound track of the movie; false if none or the tables are inconsistent.
bool parseMp4(const uint8_t* d, size_t n, Mp4Track& out) {
    bool found = false;
    boxes(d, d + n, [&](const uint8_t* type, const uint8_t* body, const uint8_t* end) {
        if (memcmp(type, "moov", 4) != 0) return true;
        boxes(body, end, [&](const uint8_t* tt, const uint8_t* tb, const uint8_t* te) {
            if (found || memcmp(tt, "trak", 4) != 0) return true;
            Mp4Track t;
            boxes(tb, te, [&](const uint8_t* mt, const uint8_t* mb, const uint8_t* me) {
                if (memcmp(mt, "mdia", 4) != 0) return true;
                boxes(mb, me, [&](const uint8_t* ht, const uint8_t* hb, const uint8_t* he) {
                    if (memcmp(ht, "hdlr", 4) == 0 && hb + 12 <= he) t.sound = memcmp(hb + 8, "soun", 4) == 0;
                    if (memcmp(ht, "minf", 4) == 0)
                        boxes(hb, he, [&](const uint8_t* st, const uint8_t* sb, const uint8_t* se) {
                            if (memcmp(st, "stbl", 4) == 0) parseStbl(sb, se, t);
                            return true;
                        });
                    return true;
                });
                return true;
            });
            if (t.sound && !t.sizes.empty() && !t.chunkOff.empty() && !t.stscFirst.empty()) {
                out = std::move(t);
                found = true;
            }
            return true;
        });
        return true;
    });
    return found;
}

// Sample offsets from the chunk offsets and the sample-to-chunk runs.
bool sampleTable(const Mp4Track& t, size_t fileSize, std::vector<uint64_t>& off, std::vector<uint32_t>& size) {
    size_t s = 0;
    for (size_t r = 0; r < t.stscFirst.size() && s < t.sizes.size(); ++r) {
        uint32_t firstChunk = t.stscFirst[r];
        uint32_t lastChunk = r + 1 < t.stscFirst.size() ? t.stscFirst[r + 1] - 1 : uint32_t(t.chunkOff.size());
        for (uint32_t c = firstChunk; c <= lastChunk && c >= 1 && c <= t.chunkOff.size() && s < t.sizes.size(); ++c) {
            uint64_t o = t.chunkOff[c - 1];
            for (uint32_t k = 0; k < t.stscCount[r] && s < t.sizes.size(); ++k, ++s) {
                if (o + t.sizes[s] > fileSize) return false;
                off.push_back(o);
                size.push_back(t.sizes[s]);
                o += t.sizes[s];
            }
        }
    }
    return !off.empty();
}

bool parseWav(Source& s) {
    if (s.size < 12 || memcmp(s.data, "RIFF", 4) != 0 || memcmp(s.data + 8, "WAVE", 4) != 0) return false;
    size_t p = 12;
    int format = 0;
    bool haveFmt = false;
    while (p + 8 <= s.size) {
        const uint8_t* c = s.data + p;
        uint32_t len = le32(c + 4);
        size_t body = p + 8;
        if (memcmp(c, "fmt ", 4) == 0 && body + 16 <= s.size) {
            format = le16(s.data + body);
            s.channels = le16(s.data + body + 2);
            s.rate = int(le32(s.data + body + 4));
            s.bits = le16(s.data + body + 14);
            if (format == 0xFFFE && len >= 26 && body + 26 <= s.size) format = le16(s.data + body + 24);  // extensible
            haveFmt = true;
        } else if (memcmp(c, "data", 4) == 0) {
            if (!haveFmt) return false;
            s.pos = body;
            s.end = (len == 0xFFFFFFFFu || body + len > s.size) ? s.size : body + len;
            s.isFloat = format == 3;
            bool ok = (format == 1 && (s.bits == 8 || s.bits == 16 || s.bits == 24 || s.bits == 32)) ||
                      (format == 3 && s.bits == 32);
            return ok && s.channels > 0 && s.rate > 0;
        }
        p = body + len + (len & 1);  // chunks are word aligned
    }
    return false;
}

// Up to 4096 frames per call.
bool decodeWav(Source& s) {
    const int bytes = s.bits / 8;
    const size_t frameBytes = size_t(bytes) * s.channels;
    size_t frames = std::min<size_t>(4096, (s.end - s.pos) / frameBytes);
    if (frames == 0) return false;
    s.pending.resize(frames);
    const uint8_t* p = s.data + s.pos;
    for (size_t f = 0; f < frames; ++f) {
        float acc = 0;
        for (int c = 0; c < s.channels; ++c, p += bytes) {
            float v;
            if (s.isFloat) { float x; memcpy(&x, p, 4); v = x * 32768.0f; }
            else if (bytes == 1) v = (float(p[0]) - 128.0f) * 256.0f;
            else if (bytes == 2) v = float(int16_t(le16(p)));
            else if (bytes == 3) v = float(int32_t(uint32_t(p[0]) << 8 | uint32_t(p[1]) << 16 | uint32_t(p[2]) << 24) >> 8) / 256.0f;
            else v = float(int32_t(le32(p))) / 65536.0f;
            acc += v;
        }
        s.pending[f] = acc / s.channels;
    }
    s.pos += frames * frameBytes;
    s.pendingPos = 0;
    return true;
}

size_t id3v2Size(const uint8_t* d, size_t n) {
    if (n < 10 || memcmp(d, "ID3", 3) != 0) return 0;
    size_t sz = size_t(d[6] & 0x7f) << 21 | size_t(d[7] & 0x7f) << 14 | size_t(d[8] & 0x7f) << 7 | size_t(d[9] & 0x7f);
    return 10 + sz + ((d[5] & 0x10) ? 10 : 0);
}

// One MP3 frame per call; frames that decode to no samples (tags, garbage, the VBR info frame) are skipped.
bool decodeMp3(Source& s) {
    mp3d_sample_t pcm[MINIMP3_MAX_SAMPLES_PER_FRAME];
    while (s.pos < s.end) {
        mp3dec_frame_info_t info;
        int samples = mp3dec_decode_frame(&s.mp3, s.data + s.pos, int(std::min<size_t>(s.end - s.pos, 1 << 20)), pcm, &info);
        if (info.frame_bytes == 0) return false;  // no further frame in the file
        s.pos += size_t(info.frame_bytes);
        if (samples == 0) continue;
        if (s.rate == 0) { s.rate = info.hz; s.channels = info.channels; }
        int ch = info.channels;
        s.pending.resize(size_t(samples));
        for (int i = 0; i < samples; ++i) {
            float acc = 0;
            for (int c = 0; c < ch; ++c) acc += float(pcm[i * ch + c]);
            s.pending[size_t(i)] = acc / ch;
        }
        s.pendingPos = 0;
        return true;
    }
    return false;
}

// Payload bytes after the one-byte frame header, by frame type (RFC 4867 storage format).
const int kNbBytes[16] = {12, 13, 15, 17, 19, 20, 26, 31, 5, 0, 0, 0, 0, 0, 0, 0};
const int kWbBytes[16] = {17, 23, 32, 36, 40, 46, 50, 58, 60, 5, 0, 0, 0, 0, 0, 0};

size_t amrFrameBytes(Kind kind, uint8_t toc) {
    int ft = (toc >> 3) & 0x0f;
    return 1 + size_t(kind == AMR_WB ? kWbBytes[ft] : kNbBytes[ft]);
}

// One AMR frame: from the storage-format stream, or from the frame table of a 3GP file.
bool decodeAmr(Source& s) {
    const uint8_t* fp;
    size_t avail;
    bool table = !s.sampleOff.empty();
    if (table) {
        if (s.sampleIdx >= s.sampleOff.size()) return false;
        fp = s.data + s.sampleOff[s.sampleIdx];
        avail = s.sampleSize[s.sampleIdx];
    } else {
        if (s.pos >= s.end) return false;
        fp = s.data + s.pos;
        avail = s.end - s.pos;
    }
    size_t frame = amrFrameBytes(s.kind, fp[0]);
    if (frame > avail) return false;  // truncated tail
    short out[320];
    int n = s.kind == AMR_WB ? 320 : 160;
    if (s.kind == AMR_WB) D_IF_decode(s.amr, fp, out, 0);
    else Decoder_Interface_Decode(s.amr, fp, out, 0);
    if (table) ++s.sampleIdx;
    else s.pos += frame;
    s.pending.resize(size_t(n));
    for (int i = 0; i < n; ++i) s.pending[size_t(i)] = float(out[i]);
    s.pendingPos = 0;
    return true;
}

HANDLE_AACDECODER openAac(Source& s, TRANSPORT_TYPE tt) {
    HANDLE_AACDECODER dec = aacDecoder_Open(tt, 1);
    if (!dec) return nullptr;
    aacDecoder_SetParam(dec, AAC_PCM_LIMITER_ENABLE, 0);  // level limiting would only colour the features
    s.aacPcm.resize(8 * 4096);  // up to 8 channels of the longest frame (USAC with 4:1 SBR)
    return dec;
}

// One AAC frame. MP4: one access unit per fill, as the packet transport requires. ADTS: the stream is fed in
// chunks and the decoder finds the frames itself. Frames with bit errors come back concealed and are kept.
bool decodeAac(Source& s) {
    bool adts = s.sampleOff.empty();
    int failures = 0;
    for (;;) {
        AAC_DECODER_ERROR err = aacDecoder_DecodeFrame(s.aac, s.aacPcm.data(), INT(s.aacPcm.size()), 0);
        if (err == AAC_DEC_NOT_ENOUGH_BITS) {
            const uint8_t* p;
            UINT size;
            if (adts) {
                if (s.pos >= s.end) return false;
                p = s.data + s.pos;
                size = UINT(std::min<size_t>(s.end - s.pos, 4096));
            } else {
                if (s.sampleIdx >= s.sampleOff.size()) return false;
                p = s.data + s.sampleOff[s.sampleIdx];
                size = s.sampleSize[s.sampleIdx];
            }
            UCHAR* buf = const_cast<UCHAR*>(p);
            UINT valid = size;
            if (aacDecoder_Fill(s.aac, &buf, &size, &valid) != AAC_DEC_OK) return false;
            if (adts) s.pos += size - valid;
            else ++s.sampleIdx;
            continue;
        }
        if (!IS_OUTPUT_VALID(err)) {
            // drop what is buffered and go on with the next access unit / the next chunk
            if (++failures > 256) return false;
            if (!adts) aacDecoder_SetParam(s.aac, AAC_TPDEC_CLEAR_BUFFER, 1);
            continue;
        }
        CStreamInfo* info = aacDecoder_GetStreamInfo(s.aac);
        if (!info || info->frameSize <= 0 || info->numChannels <= 0) continue;
        if (s.rate == 0) { s.rate = info->sampleRate; s.channels = info->numChannels; }
        int ch = info->numChannels, n = info->frameSize;
        s.pending.resize(size_t(n));
        for (int i = 0; i < n; ++i) {
            float acc = 0;
            for (int c = 0; c < ch; ++c) acc += float(s.aacPcm[size_t(i * ch + c)]);
            s.pending[size_t(i)] = acc / ch;
        }
        s.pendingPos = 0;
        return true;
    }
}

bool decodeNext(Source& s) {
    switch (s.kind) {
        case WAV: return decodeWav(s);
        case MP3: return decodeMp3(s);
        case AMR_NB:
        case AMR_WB: return decodeAmr(s);
        case AAC: return decodeAac(s);
        default: return false;  // MP4_AAC: PCM comes from MediaCodec, fed by nextSample()
    }
}

// MP4 / 3GP: AAC goes to fdk-aac (or, if it refuses the config, is demuxed for MediaCodec); AMR (samr / sawb) is
// split into frames and decoded here.
bool openMp4(Source& s) {
    Mp4Track t;
    if (!parseMp4(s.data, s.size, t)) return false;
    std::vector<uint64_t> off;
    std::vector<uint32_t> size;
    if (!sampleTable(t, s.size, off, size)) return false;
    if (memcmp(t.fourcc, "mp4a", 4) == 0) {
        if (t.asc.size() < 2) return false;
        s.kind = MP4_AAC;
        s.asc = t.asc;
        s.rate = t.rate;
        s.channels = t.channels > 0 ? t.channels : 1;
        s.sampleOff = std::move(off);
        s.sampleSize = std::move(size);
        s.aac = openAac(s, TT_MP4_RAW);
        UCHAR* conf[] = {s.asc.data()};
        UINT confLen[] = {UINT(s.asc.size())};
        if (s.aac && aacDecoder_ConfigRaw(s.aac, conf, confLen) == AAC_DEC_OK) {
            s.kind = AAC;
            s.rate = 0;  // the output rate (twice the core rate with SBR) comes with the first frame
            if (decodeAac(s)) return true;
        }
        if (s.aac) aacDecoder_Close(s.aac);
        s.aac = nullptr;
        s.kind = MP4_AAC;
        s.rate = t.rate;
        s.sampleIdx = 0;
        s.pending.clear();
        return true;
    }
    bool nb = memcmp(t.fourcc, "samr", 4) == 0, wb = memcmp(t.fourcc, "sawb", 4) == 0;
    if (!nb && !wb) return false;
    s.kind = wb ? AMR_WB : AMR_NB;
    s.rate = wb ? 16000 : 8000;
    for (size_t i = 0; i < off.size(); ++i) {  // a 3GP sample may hold several AMR frames
        uint64_t p = off[i], e = off[i] + size[i];
        while (p < e) {
            size_t f = amrFrameBytes(s.kind, s.data[p]);
            if (p + f > e) break;
            s.sampleOff.push_back(p);
            s.sampleSize.push_back(uint32_t(f));
            p += f;
        }
    }
    s.amr = wb ? D_IF_init() : Decoder_Interface_init();
    return !s.sampleOff.empty();
}

void destroy(Source* s) {
    if (!s) return;
    if (s->amr) {
        if (s->kind == AMR_WB) D_IF_exit(s->amr);
        else Decoder_Interface_exit(s->amr);
    }
    if (s->aac) aacDecoder_Close(s->aac);
    if (s->data) munmap(const_cast<uint8_t*>(s->data), s->size);
    delete s;
}

Source* openFile(const char* path) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return nullptr;
    struct stat st{};
    if (fstat(fd, &st) != 0 || st.st_size < 12) { close(fd); return nullptr; }
    void* m = mmap(nullptr, size_t(st.st_size), PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (m == MAP_FAILED) return nullptr;
    auto* s = new Source();
    s->data = static_cast<const uint8_t*>(m);
    s->size = size_t(st.st_size);
    s->end = s->size;
    madvise(m, s->size, MADV_SEQUENTIAL);
    const uint8_t* d = s->data;
    if (memcmp(d, "RIFF", 4) == 0) {
        s->kind = WAV;
        if (!parseWav(*s)) { destroy(s); return nullptr; }
    } else if (s->size >= 9 && memcmp(d, "#!AMR-WB\n", 9) == 0) {
        s->kind = AMR_WB; s->pos = 9; s->rate = 16000; s->amr = D_IF_init();
    } else if (s->size >= 6 && memcmp(d, "#!AMR\n", 6) == 0) {
        s->kind = AMR_NB; s->pos = 6; s->rate = 8000; s->amr = Decoder_Interface_init();
    } else if (memcmp(d + 4, "ftyp", 4) == 0) {
        if (!openMp4(*s)) { destroy(s); return nullptr; }  // unusual layout: MediaCodec + MediaExtractor instead
    } else {
        size_t skip = id3v2Size(d, s->size);
        bool sync = skip + 2 <= s->size && d[skip] == 0xff && (d[skip + 1] & 0xe0) == 0xe0;
        if (!sync) { destroy(s); return nullptr; }  // unknown: let MediaCodec handle it
        s->pos = skip;
        if ((d[skip + 1] & 0xf6) == 0xf0) {  // 12-bit sync with layer 00: ADTS (an MP3 frame never has layer 00)
            s->kind = AAC;
            s->aac = openAac(*s, TT_MP4_ADTS);
            if (!s->aac || !decodeAac(*s)) { destroy(s); return nullptr; }  // also learns the sample rate
        } else {
            s->kind = MP3;
            mp3dec_init(&s->mp3);
            if (!decodeMp3(*s)) { destroy(s); return nullptr; }  // also learns the sample rate
        }
    }
    if ((s->kind == AMR_NB || s->kind == AMR_WB) && !s->amr) { destroy(s); return nullptr; }
    return s;
}

// ---- features ----
constexpr int kFrameLen = 400, kShift = 160, kFft = 512, kMels = 80, kLfrM = 7, kLfrN = 6, kDim = kMels * kLfrM;

struct Tables {
    float window[kFrameLen];
    float cosT[kFft / 2], sinT[kFft / 2];
    int rev[kFft];
    int melFirst[kMels], melCount[kMels];
    std::vector<float> melW[kMels];
};

const Tables& tables() {
    static Tables t;
    static std::once_flag once;
    std::call_once(once, [] {
        for (int i = 0; i < kFrameLen; ++i) t.window[i] = float(0.54 - 0.46 * cos(2 * M_PI * i / (kFrameLen - 1)));
        for (int i = 0; i < kFft / 2; ++i) { t.cosT[i] = float(cos(2 * M_PI * i / kFft)); t.sinT[i] = float(-sin(2 * M_PI * i / kFft)); }
        for (int i = 0, j = 0; i < kFft; ++i) {
            t.rev[i] = j;
            int bit = kFft >> 1;
            while (j & bit) { j ^= bit; bit >>= 1; }
            j ^= bit;
        }
        auto mel = [](double f) { return 1127.0 * log(1.0 + f / 700.0); };
        double lo = mel(20.0), hi = mel(8000.0), delta = (hi - lo) / (kMels + 1);
        for (int m = 0; m < kMels; ++m) {
            double left = lo + m * delta, center = lo + (m + 1) * delta, right = lo + (m + 2) * delta;
            t.melFirst[m] = -1;
            for (int k = 0; k < kFft / 2; ++k) {
                double b = mel(16000.0 / kFft * k);
                double v = std::max(0.0, std::min((b - left) / (center - left), (right - b) / (right - center)));
                if (v > 0) {
                    if (t.melFirst[m] < 0) t.melFirst[m] = k;
                    t.melW[m].push_back(float(v));
                }
            }
            t.melCount[m] = int(t.melW[m].size());
        }
    });
    return t;
}

void fft(float* re, float* im, const Tables& t) {
    for (int i = 0; i < kFft; ++i) {
        int j = t.rev[i];
        if (i < j) { std::swap(re[i], re[j]); std::swap(im[i], im[j]); }
    }
    for (int len = 2; len <= kFft; len <<= 1) {
        int half = len >> 1, step = kFft / len;
        for (int start = 0; start < kFft; start += len) {
            for (int k = 0; k < half; ++k) {
                float wr = t.cosT[k * step], wi = t.sinT[k * step];
                int a = start + k, b = a + half;
                float xr = re[b] * wr - im[b] * wi, xi = re[b] * wi + im[b] * wr;
                re[b] = re[a] - xr; im[b] = im[a] - xi;
                re[a] += xr; im[a] += xi;
            }
        }
    }
}

// Returns the number of LFR frames written to out (each kDim floats).
int computeFeatures(const float* pcm, int n, const float* shift, const float* scale, float* out, int outCap) {
    if (n < kFrameLen) return 0;
    const Tables& t = tables();
    int frames = 1 + (n - kFrameLen) / kShift;
    int tLfr = (frames + kLfrN - 1) / kLfrN;
    if (tLfr * kDim > outCap) return -1;
    std::vector<float> fb(size_t(frames) * kMels);
    float re[kFft], im[kFft], power[kFft / 2 + 1];
    const double eps = 1.1920928955078125e-07;
    for (int f = 0; f < frames; ++f) {
        const float* x = pcm + size_t(f) * kShift;
        double mean = 0;
        for (int i = 0; i < kFrameLen; ++i) mean += x[i];
        float mu = float(mean / kFrameLen);
        float prev = x[0] - mu;
        for (int i = 0; i < kFrameLen; ++i) {
            float v = x[i] - mu;
            re[i] = (v - 0.97f * prev) * t.window[i];
            prev = v;
        }
        std::fill(re + kFrameLen, re + kFft, 0.0f);
        std::fill(im, im + kFft, 0.0f);
        fft(re, im, t);
        for (int k = 0; k <= kFft / 2; ++k) power[k] = re[k] * re[k] + im[k] * im[k];
        float* row = fb.data() + size_t(f) * kMels;
        for (int m = 0; m < kMels; ++m) {
            double e = 0;
            const float* w = t.melW[m].data();
            const float* p = power + t.melFirst[m];
            for (int j = 0; j < t.melCount[m]; ++j) e += double(p[j]) * w[j];
            row[m] = float(log(std::max(e, eps)));
        }
    }
    for (int i = 0; i < tLfr; ++i) {
        float* v = out + size_t(i) * kDim;
        for (int j = 0; j < kLfrM; ++j) {
            int src = std::min(std::max(i * kLfrN + j - (kLfrM - 1) / 2, 0), frames - 1);
            const float* row = fb.data() + size_t(src) * kMels;
            for (int k = 0; k < kMels; ++k) {
                int d = j * kMels + k;
                v[d] = (row[k] + shift[d]) * scale[d];
            }
        }
    }
    return tLfr;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_bob_recordinghelper_asr_NativeAudio_open(JNIEnv* env, jclass, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    Source* s = openFile(p);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jint JNICALL Java_com_bob_recordinghelper_asr_NativeAudio_sampleRate(JNIEnv*, jclass, jlong h) {
    return reinterpret_cast<Source*>(h)->rate;
}

JNIEXPORT jstring JNICALL Java_com_bob_recordinghelper_asr_NativeAudio_format(JNIEnv* env, jclass, jlong h) {
    auto* s = reinterpret_cast<Source*>(h);
    const char* names[] = {"", "wav", "mp3", "amr-nb", "amr-wb", "mp4-aac", "aac"};
    return env->NewStringUTF(names[s->kind]);
}

JNIEXPORT jint JNICALL Java_com_bob_recordinghelper_asr_NativeAudio_channels(JNIEnv*, jclass, jlong h) {
    return reinterpret_cast<Source*>(h)->channels;
}

// MP4_AAC: the AudioSpecificConfig for MediaCodec's csd-0.
JNIEXPORT jbyteArray JNICALL Java_com_bob_recordinghelper_asr_NativeAudio_aacConfig(JNIEnv* env, jclass, jlong h) {
    auto* s = reinterpret_cast<Source*>(h);
    jbyteArray a = env->NewByteArray(jsize(s->asc.size()));
    env->SetByteArrayRegion(a, 0, jsize(s->asc.size()), reinterpret_cast<const jbyte*>(s->asc.data()));
    return a;
}

// MP4_AAC: copies the next access unit into out; returns its size, 0 at the end, -size if out is too small.
JNIEXPORT jint JNICALL Java_com_bob_recordinghelper_asr_NativeAudio_nextSample(JNIEnv* env, jclass, jlong h, jbyteArray out) {
    auto* s = reinterpret_cast<Source*>(h);
    if (s->kind != MP4_AAC || s->sampleIdx >= s->sampleOff.size()) return 0;
    uint32_t n = s->sampleSize[s->sampleIdx];
    if (jsize(n) > env->GetArrayLength(out)) return -jint(n);
    env->SetByteArrayRegion(out, 0, jsize(n), reinterpret_cast<const jbyte*>(s->data + s->sampleOff[s->sampleIdx]));
    ++s->sampleIdx;
    return jint(n);
}

// Fills out with up to out.length mono samples; returns how many, 0 at the end of the file.
JNIEXPORT jint JNICALL Java_com_bob_recordinghelper_asr_NativeAudio_read(JNIEnv* env, jclass, jlong h, jfloatArray out) {
    auto* s = reinterpret_cast<Source*>(h);
    jsize cap = env->GetArrayLength(out);
    std::vector<float> tmp;
    tmp.reserve(size_t(cap));
    while (jsize(tmp.size()) < cap) {
        if (s->pendingPos >= s->pending.size() && !decodeNext(*s)) break;
        size_t take = std::min(s->pending.size() - s->pendingPos, size_t(cap) - tmp.size());
        tmp.insert(tmp.end(), s->pending.begin() + long(s->pendingPos), s->pending.begin() + long(s->pendingPos + take));
        s->pendingPos += take;
    }
    if (!tmp.empty()) env->SetFloatArrayRegion(out, 0, jsize(tmp.size()), tmp.data());
    return jint(tmp.size());
}

JNIEXPORT void JNICALL Java_com_bob_recordinghelper_asr_NativeAudio_close(JNIEnv*, jclass, jlong h) {
    destroy(reinterpret_cast<Source*>(h));
}

JNIEXPORT jint JNICALL Java_com_bob_recordinghelper_asr_NativeAudio_features(JNIEnv* env, jclass, jfloatArray pcm, jint n,
                                                                            jfloatArray shift, jfloatArray scale, jfloatArray out) {
    jsize cap = env->GetArrayLength(out);
    float* p = static_cast<float*>(env->GetPrimitiveArrayCritical(pcm, nullptr));
    float* sh = static_cast<float*>(env->GetPrimitiveArrayCritical(shift, nullptr));
    float* sc = static_cast<float*>(env->GetPrimitiveArrayCritical(scale, nullptr));
    float* o = static_cast<float*>(env->GetPrimitiveArrayCritical(out, nullptr));
    int r = computeFeatures(p, n, sh, sc, o, cap);
    env->ReleasePrimitiveArrayCritical(out, o, 0);
    env->ReleasePrimitiveArrayCritical(scale, sc, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(shift, sh, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(pcm, p, JNI_ABORT);
    return r;
}

}  // extern "C"

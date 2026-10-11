// libllmjni.so: llama.cpp (CPU) for recording-helper's local transcript polishing.
// Java side: com.bob.recordinghelper.llm.LlmEngine. Text crosses JNI as UTF-8 byte arrays (NewStringUTF is modified
// UTF-8 and breaks on characters outside the BMP).
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <string>
#include <vector>

#include "llama.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "RhLlm", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "RhLlm", __VA_ARGS__)

namespace {

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int n_ctx = 0;
    int n_batch = 512;
    std::atomic<bool> cancel{false};
    // stats of the last generate(): prompt tokens, generated tokens, prefill ms, generation ms
    long long stats[4] = {0, 0, 0, 0};
};

long long now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

// length of the longest prefix of s that ends on a complete UTF-8 character
size_t utf8_complete(const std::string & s) {
    size_t n = s.size();
    size_t i = n;
    // walk back over at most 3 continuation bytes to the lead byte of the last character
    size_t back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = (unsigned char) s[i - 1];
        if ((c & 0xC0) != 0x80) {
            size_t need = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
            return (n - (i - 1) >= need) ? n : i - 1;
        }
        --i;
        ++back;
    }
    return n;
}

jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray a = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(a, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return a;
}

std::string from_bytes(JNIEnv * env, jbyteArray a) {
    jsize n = env->GetArrayLength(a);
    std::string s((size_t) n, '\0');
    env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_bob_recordinghelper_llm_LlmEngine_nativeLoad(JNIEnv * env, jclass, jbyteArray jpath, jint threads, jint n_ctx) {
    static bool inited = false;
    if (!inited) {
        llama_backend_init();
        inited = true;
    }
    std::string path = from_bytes(env, jpath);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    // read the weights into memory instead of mmapping the file: the CPU backend repacks most weights into its own
    // buffers, and with mmap the file pages stay resident too, so a model counts about twice in the app's PSS
    // (HyperOS force-stops apps above ~6 GB PSS; 9B Q4_0 measured 9.4 GB with mmap)
    mp.load_mode = LLAMA_LOAD_MODE_NONE;
    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) {
        LOGE("load failed: %s", path.c_str());
        return 0;
    }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        LOGE("context failed");
        llama_model_free(model);
        return 0;
    }
    auto * e = new Engine();
    e->model = model;
    e->ctx = ctx;
    e->vocab = llama_model_get_vocab(model);
    e->n_ctx = n_ctx;
    LOGI("loaded %s ctx=%d threads=%d", path.c_str(), n_ctx, threads);
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_com_bob_recordinghelper_llm_LlmEngine_nativeFree(JNIEnv *, jclass, jlong h) {
    auto * e = reinterpret_cast<Engine *>(h);
    if (!e) return;
    llama_free(e->ctx);
    llama_model_free(e->model);
    delete e;
}

JNIEXPORT void JNICALL
Java_com_bob_recordinghelper_llm_LlmEngine_nativeCancel(JNIEnv *, jclass, jlong h) {
    auto * e = reinterpret_cast<Engine *>(h);
    if (e) e->cancel = true;
}

JNIEXPORT jint JNICALL
Java_com_bob_recordinghelper_llm_LlmEngine_nativeCountTokens(JNIEnv * env, jclass, jlong h, jbyteArray jtext) {
    auto * e = reinterpret_cast<Engine *>(h);
    std::string text = from_bytes(env, jtext);
    return -llama_tokenize(e->vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, false, true);
}

// prompt: a complete chat-formatted prompt (the Kotlin side builds it). sink.onToken(byte[]) gets the output as it
// is produced, cut on whole UTF-8 characters. Returns the whole output, or null on error.
JNIEXPORT jbyteArray JNICALL
Java_com_bob_recordinghelper_llm_LlmEngine_nativeGenerate(JNIEnv * env, jclass, jlong h, jbyteArray jprompt,
                                                         jint max_tokens, jobject sink) {
    auto * e = reinterpret_cast<Engine *>(h);
    e->cancel = false;
    std::string prompt = from_bytes(env, jprompt);
    llama_memory_clear(llama_get_memory(e->ctx), true);

    int n = -llama_tokenize(e->vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> toks((size_t) std::max(n, 0));
    if (n <= 0 || llama_tokenize(e->vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), n, true, true) < 0) {
        LOGE("tokenize failed");
        return nullptr;
    }
    if (n + max_tokens > e->n_ctx) {
        LOGE("prompt %d + max %d tokens exceeds context %d", n, (int) max_tokens, e->n_ctx);
        return nullptr;
    }
    long long t0 = now_ms();
    for (int i = 0; i < n; i += e->n_batch) {
        int k = std::min(e->n_batch, n - i);
        if (llama_decode(e->ctx, llama_batch_get_one(toks.data() + i, k)) != 0) {
            LOGE("prefill decode failed at %d", i);
            return nullptr;
        }
        if (e->cancel) return nullptr;
    }
    long long t1 = now_ms();

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // deterministic polishing: a mild repetition penalty against loops, then greedy
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(e->vocab), 64, 1.05f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    jclass cls = sink ? env->GetObjectClass(sink) : nullptr;
    jmethodID on_token = cls ? env->GetMethodID(cls, "onToken", "([B)V") : nullptr;

    std::string out, pending;
    int generated = 0;
    for (; generated < max_tokens && !e->cancel; ++generated) {
        llama_token t = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, t)) break;
        char buf[256];
        int len = llama_token_to_piece(e->vocab, t, buf, sizeof(buf), 0, false);
        if (len > 0) pending.append(buf, (size_t) len);
        size_t ok = utf8_complete(pending);
        if (ok > 0) {
            std::string piece = pending.substr(0, ok);
            pending.erase(0, ok);
            out += piece;
            if (on_token) {
                jbyteArray b = to_bytes(env, piece);
                env->CallVoidMethod(sink, on_token, b);
                env->DeleteLocalRef(b);
                if (env->ExceptionCheck()) break;
            }
        }
        if (llama_decode(e->ctx, llama_batch_get_one(&t, 1)) != 0) {
            LOGE("decode failed at token %d", generated);
            break;
        }
    }
    out += pending;
    llama_sampler_free(smpl);
    long long t2 = now_ms();
    e->stats[0] = n;
    e->stats[1] = generated;
    e->stats[2] = t1 - t0;
    e->stats[3] = t2 - t1;
    return to_bytes(env, out);
}

JNIEXPORT jlongArray JNICALL
Java_com_bob_recordinghelper_llm_LlmEngine_nativeLastStats(JNIEnv * env, jclass, jlong h) {
    auto * e = reinterpret_cast<Engine *>(h);
    jlongArray a = env->NewLongArray(4);
    env->SetLongArrayRegion(a, 0, 4, reinterpret_cast<const jlong *>(e->stats));
    return a;
}

}  // extern "C"

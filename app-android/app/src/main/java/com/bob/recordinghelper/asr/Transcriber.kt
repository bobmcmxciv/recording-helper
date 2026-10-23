package com.bob.recordinghelper.asr

import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Audio file → text on the phone: decode, cut into ≤10 s pieces at pauses, features, SenseVoice.
 * Decoding and features run on their own thread while the calling thread drives the NPU, so the slower of the two
 * sets the pace instead of their sum. Pieces whose loudest 100 ms is below [SILENCE_RMS] are not sent to the model.
 *
 * With a [speakers] tracker, a third thread computes voiceprints on the CPU ([SpeakerTracker]); at the end every
 * piece's text is split where the speaker changes (the model's frame j covers samples j × 960 of the piece) and the
 * result carries "名字：文字" lines. [onLive] reports progress for the live view (any thread).
 */
class Transcriber(
    private val frontend: Frontend,
    private val model: SenseVoice,
    private val speakers: SpeakerTracker? = null,
    private val library: Map<String, List<FloatArray>> = emptyMap(),
    private val onLive: (Live) -> Unit = {},
    private val checkpoint: AsrCheckpoint? = null,
) {

    data class Segment(val start: Double, val end: Double, val text: String, val speaker: String = "")

    /** Events for the live view. Piece indices count from 0 in recording order. */
    sealed class Live {
        /** A piece came back from the model ([text] empty for silence); [modelMs] 0 when it was skipped. */
        data class Piece(val index: Int, val start: Double, val end: Double, val text: String, val modelMs: Long) : Live()
        /** Current speaker number of every piece so far (0 = no speech found in it yet). */
        data class Speakers(val byPiece: IntArray, val count: Int) : Live()
        /** Final speaker-attributed segments. */
        data class Final(val segments: List<Segment>) : Live()
    }

    data class Result(
        val text: String,
        val segments: List<Segment>,
        val audioSeconds: Double,
        val modelMs: Long,
        /** fbank + LFR + CMVN (on the decode thread, overlapping the model) */
        val featureMs: Long,
        val totalMs: Long,
        /** pieces skipped as silence */
        val silentSegments: Int = 0,
        /** speaker-attributed segments, consecutive same-speaker text merged (empty without a tracker) */
        val speakerSegments: List<Segment> = emptyList(),
        val speakerCount: Int = 0,
        /** CAM++ time on the speaker thread (overlapping the model) */
        val speakerMs: Long = 0,
        /** extra wait at the end for the speaker thread to catch up */
        val speakerWaitMs: Long = 0,
        val speakerError: String? = null,
    ) {
        /** What to upload: "名字：文字" lines when two or more speakers were told apart, else the plain text. */
        val uploadText: String
            get() = if (speakerCount >= 2) speakerSegments.joinToString("\n") { "${it.speaker}：${it.text}" } else text
    }

    private class Piece(
        val index: Int,
        val start: Long,
        val n: Int,
        val features: FloatArray?,
        val pcm: FloatArray?,
        val cached: AsrCheckpoint.Entry? = null,
    )

    private class Done(val index: Int, val start: Long, val n: Int, val ids: IntArray?)

    fun transcribe(file: File, onSegment: (Segment) -> Unit = {}): Result {
        val t0 = System.nanoTime()
        val restored = checkpoint?.load().orEmpty()
        val queue = ArrayBlockingQueue<Piece>(QUEUE)
        val end = Piece(-1, -1, 0, null, null)
        val spkQueue = ArrayBlockingQueue<Piece>(SPK_QUEUE)
        var featureNs = 0L
        var duration = 0.0
        var failure: Throwable? = null
        val stop = AtomicBoolean(false)
        val tracker = speakers
        var spkError: String? = null
        val spkThread = tracker?.let {
            thread(name = "asr-spk", priority = Thread.NORM_PRIORITY) {
                runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT) }
                var sinceRecluster = 0L
                while (true) {
                    val p = try { spkQueue.take() } catch (_: InterruptedException) { break }
                    if (p === end) break
                    if (spkError != null) continue // after a failure keep draining so the decoder never blocks
                    try {
                        tracker.addPiece(p.index, p.start, p.pcm!!, p.n)
                        sinceRecluster += p.n
                        if (sinceRecluster >= RECLUSTER_SAMPLES) {
                            sinceRecluster = 0
                            tracker.recluster()
                            onLive(Live.Speakers(IntArray(p.index + 1) { tracker.pieceSpeaker(it) }, tracker.speakers))
                        }
                    } catch (t: Throwable) {
                        spkError = t.toString()
                        android.util.Log.e("RhSpeakers", "speaker thread failed at piece ${p.index}", t)
                    }
                }
            }
        }
        val producer = thread(name = "asr-decode", priority = Thread.NORM_PRIORITY) {
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT) }
            var index = 0
            try {
                val segmenter = Segmenter { pcm, n, startSample ->
                    if (stop.get()) throw InterruptedException("transcription abandoned")
                    if (tracker != null) {
                        if (spkError == null) spkQueue.put(Piece(index, startSample, n, null, pcm))
                    }
                    val hit = restored[index]?.takeIf { it.start == startSample && it.n == n }
                    if (hit != null) {
                        queue.put(Piece(index, startSample, n, null, null, cached = hit))
                    } else if (loudest100ms(pcm, n) < SILENCE_RMS) {
                        queue.put(Piece(index, startSample, n, null, null))
                    } else {
                        val f0 = System.nanoTime()
                        val feats = frontend.featuresFlat(Segmenter.padded(pcm, n))
                        featureNs += System.nanoTime() - f0
                        queue.put(Piece(index, startSample, n, feats, null))
                    }
                    index++
                }
                duration = AudioDecoder.decode(file) { x, n -> segmenter.feed(x, n) }
                segmenter.finish()
            } catch (t: Throwable) {
                failure = t
            } finally {
                queue.put(end)
                if (tracker != null) spkQueue.put(end)
            }
        }
        var modelNs = 0L
        var silent = 0
        val segments = ArrayList<Segment>()
        val done = ArrayList<Done>()
        var finished = false
        try {
            while (true) {
                val p = queue.take()
                if (p === end) break
                var ms = 0L
                val cached = p.cached
                val text: String
                val ids: IntArray?
                if (cached != null) {
                    text = cached.text
                    if (text.isEmpty()) silent++
                    ids = if (tracker != null) cached.ids else null
                } else if (p.features == null) {
                    silent++
                    text = ""
                    ids = null
                    checkpoint?.save(AsrCheckpoint.Entry(p.index, p.start, p.n, "", null))
                } else {
                    val m0 = System.nanoTime()
                    text = model.runFlat(p.features).trim().also {
                        modelNs += System.nanoTime() - m0
                        ms = (System.nanoTime() - m0) / 1_000_000
                    }
                    val rawIds = model.lastIds.copyOf()
                    ids = if (tracker != null) rawIds else null
                    checkpoint?.save(AsrCheckpoint.Entry(p.index, p.start, p.n, text, rawIds))
                }
                done.add(Done(p.index, p.start, p.n, ids))
                val seg = Segment(p.start / 16000.0, (p.start + p.n) / 16000.0, text)
                segments.add(seg)
                onSegment(seg)
                onLive(Live.Piece(p.index, seg.start, seg.end, text, ms))
            }
            finished = true
        } finally {
            if (finished) {
                // normal end: the decoder may still be handing its last pieces to the (slower) speaker thread -
                // let it; taking them out of the queue here would leave a gap in the speaker thread's piece order
                producer.join()
            } else {
                // the model threw: stop the decoder at its next piece, unblock it if it waits on a full queue,
                // and stop the speaker thread (its end marker may have been drained)
                stop.set(true)
                while (producer.isAlive) {
                    queue.poll()
                    spkQueue.poll()
                    producer.join(10)
                }
                spkThread?.interrupt()
            }
        }
        failure?.let { spkThread?.interrupt(); throw it }
        val text = segments.joinToString("") { it.text }.trim()

        var speakerSegments = emptyList<Segment>()
        var waitMs = 0L
        if (tracker != null && spkThread != null) {
            val w0 = System.nanoTime()
            spkThread.join()
            waitMs = (System.nanoTime() - w0) / 1_000_000
            if (spkError == null) {
                try {
                    speakerSegments = attribute(tracker, done)
                    onLive(Live.Final(speakerSegments))
                } catch (t: Throwable) {
                    spkError = t.toString()
                    android.util.Log.e("RhSpeakers", "speaker attribution failed", t)
                }
            }
        }
        return Result(text, segments, duration, modelNs / 1_000_000, featureNs / 1_000_000,
            (System.nanoTime() - t0) / 1_000_000, silent, speakerSegments, tracker?.speakers ?: 0,
            tracker?.embedMs ?: 0, waitMs, spkError)
    }

    /** Final clustering, then each piece's text split by speaker and consecutive same-speaker text merged. */
    private fun attribute(tracker: SpeakerTracker, done: List<Done>): List<Segment> {
        tracker.recluster()
        val names = tracker.names(library, com.bob.recordinghelper.local.VoiceMatcher.THRESHOLD, com.bob.recordinghelper.local.VoiceMatcher.MARGIN)
        val out = ArrayList<Segment>()
        for (d in done.sortedBy { it.index }) {
            val ids = d.ids ?: continue
            val frames = minOf(SenseVoice.FRAMES, (d.n + 959) / 960)
            val lab = tracker.frameSpeakers(d.index, frames)
            var f0 = 0
            while (f0 < frames) {
                var f1 = f0 + 1
                while (f1 < frames && lab[f1] == lab[f0]) f1++
                var piece = model.textOf(ids, f0, f1).trim()
                // punctuation emitted just after a speaker change belongs to the previous speaker's sentence
                val lead = piece.takeWhile { it in LEADING_PUNCT }
                if (lead.isNotEmpty() && out.isNotEmpty()) {
                    out[out.size - 1] = out.last().copy(text = out.last().text + lead)
                    piece = piece.drop(lead.length).trim()
                }
                if (piece.isNotEmpty()) {
                    val who = names[lab[f0]] ?: "说话人${lab[f0]}"
                    val st = (d.start + f0 * 960L) / 16000.0
                    val en = (d.start + minOf(d.n.toLong(), f1 * 960L)) / 16000.0
                    val last = out.lastOrNull()
                    if (last != null && last.speaker == who) out[out.size - 1] = last.copy(end = en, text = last.text + piece)
                    else out.add(Segment(st, en, piece, who))
                }
                f0 = f1
            }
        }
        return out
    }

    companion object {
        /** Pieces waiting for the NPU; each holds ~370 KB of features. */
        private const val QUEUE = 4

        /** Pieces waiting for the speaker thread; each holds ~640 KB of samples. */
        private const val SPK_QUEUE = 16

        private const val LEADING_PUNCT = "，。？！、；：,.?!;: "

        /** Re-group speakers for the live view after every minute of audio. */
        private const val RECLUSTER_SAMPLES = 60L * 16000

        /**
         * int16 RMS of the loudest 100 ms below which a piece is skipped. On a 49-minute meeting every piece whose
         * loudest 100 ms stayed under 75 came out empty, while one peaking at 104 still held "嗯嗯".
         */
        const val SILENCE_RMS = 80.0

        fun loudest100ms(pcm: FloatArray, n: Int): Double {
            var best = 0.0
            var i = 0
            while (i + 1600 <= n) {
                var s = 0.0
                for (k in i until i + 1600) s += pcm[k].toDouble() * pcm[k]
                best = maxOf(best, s / 1600)
                i += 1600
            }
            return sqrt(best)
        }
    }
}

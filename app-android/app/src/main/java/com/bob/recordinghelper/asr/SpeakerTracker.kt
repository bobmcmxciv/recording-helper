package com.bob.recordinghelper.asr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Who spoke when, for the pieces of one recording, on the phone's CPU while the NPU transcribes.
 *
 * Per piece (≤ 10 s from [Segmenter]): an energy VAD finds speech runs (10 ms frames whose int16 RMS is at least
 * max([FLOOR], 4 × noise floor), gaps under 0.3 s bridged, runs under 0.3 s dropped); every run gets 1.5 s windows
 * every 0.75 s (a run of 0.5–1.5 s gets one window, its frames repeated up to the model's 148); each window becomes
 * a CAM++ voiceprint ([Embedder]). Voiceprints join online micro-clusters (cosine ≥ [MICRO]); [recluster] groups
 * the micro-clusters by average linkage (cosine distance ≤ [MERGE]) and folds speakers with less than
 * max(15 s, 3 %) of the windows into the nearest big one. Speaker numbers stay stable from one recluster to the next.
 *
 * Checked in Python on 8 Tencent Meeting recordings with the participants' own labels (2–3 speakers): speaker count
 * right in all 8, 1.4–4.4 % of single-speaker time on the wrong speaker (mean 2.7 %); re-clustering every minute
 * while transcribing (the live view) gave 1.7–7.8 %.
 */
class SpeakerTracker(private val embedder: Embedder) {

    /** CAM++ on one window: [FRAMES] × 80 features in, L2-normalised voiceprint out. */
    fun interface Embedder {
        fun embed(features: FloatArray): FloatArray

        /** Windows the model takes per call; the tracker queues this many before calling [embedBatch]. */
        val batch: Int get() = 1

        /** Voiceprints of up to [batch] windows at once (one model call amortises its fixed per-call cost). */
        fun embedBatch(features: List<FloatArray>): List<FloatArray> = features.map { embed(it) }
    }

    private class Pending(val piece: Int, val run: Int, val center: Long, val features: FloatArray)

    private val pending = ArrayList<Pending>()

    /** Embeds the queued windows (in order) and adds them to the micro-clusters. */
    private fun flush() {
        if (pending.isEmpty()) return
        val t0 = System.nanoTime()
        val es = embedder.embedBatch(pending.map { it.features })
        embedMs += (System.nanoTime() - t0) / 1_000_000
        for ((p, e) in pending.zip(es)) windows.add(Window(p.piece, p.run, p.center, e, joinMicro(e)))
        pending.clear()
    }

    private class Window(val piece: Int, val run: Int, val center: Long, val e: FloatArray, val micro: Int)

    private class Run(val piece: Int, val start: Long, val n: Int)

    private val pieces = ArrayList<LongArray>() // [start, n]
    private val runs = ArrayList<Run>()
    private val windows = ArrayList<Window>()
    private val microSum = ArrayList<FloatArray>()
    private val microCount = ArrayList<Int>()
    private var labels = IntArray(0) // per window, after the last recluster (1-based speaker numbers)
    private var labelledUpTo = 0
    private val rmsHist = IntArray(RMS_BINS) // frame-RMS histogram (log scale) for the running noise floor
    private var rmsTotal = 0L

    /** Number of distinct speakers after the last [recluster]. */
    var speakers = 0
        private set

    /** Milliseconds spent in [embedder] so far. */
    var embedMs = 0L
        private set

    val windowCount: Int get() = windows.size

    /**
     * Adds piece number [index] (in order): [pcm] (int16 scale) holds its [n] samples, which start at sample [start]
     * of the recording.
     */
    fun addPiece(index: Int, start: Long, pcm: FloatArray, n: Int) {
        require(index == pieces.size) { "pieces must be added in order" }
        pieces.add(longArrayOf(start, n.toLong()))
        val frames = n / 160
        val rms = DoubleArray(frames) { f ->
            var s = 0.0
            for (k in f * 160 until (f + 1) * 160) s += pcm[k].toDouble() * pcm[k]
            sqrt(s / 160)
        }
        for (r in rms) { rmsHist[bin(r)]++; rmsTotal++ }
        val gate = max(FLOOR, 4 * noiseFloor())
        // speech runs in 10 ms frames
        val spans = ArrayList<IntArray>()
        var f = 0
        while (f < frames) {
            if (rms[f] >= gate) {
                var g = f
                while (g < frames && rms[g] >= gate) g++
                if (spans.isNotEmpty() && f - spans.last()[1] < BRIDGE) spans.last()[1] = g else spans.add(intArrayOf(f, g))
                f = g
            } else f++
        }
        for (sp in spans) {
            if (sp[1] - sp[0] < MIN_RUN) continue
            val runIdx = runs.size
            val rs = sp[0] * 160
            val rn = (sp[1] - sp[0]) * 160
            runs.add(Run(index, start + rs, rn))
            val starts = ArrayList<Int>()
            var a = 0
            while (a <= max(0, rn - WIN)) { starts.add(a); a += HOP }
            if (rn > WIN && starts.last() + WIN < rn) starts.add(rn - WIN)
            for (a0 in starts) {
                val len = min(WIN, rn - a0)
                if (len < MIN_WIN) continue
                pending.add(Pending(index, runIdx, start + rs + a0 + len / 2, features(pcm, rs + a0, len)))
                if (pending.size >= embedder.batch) flush()
            }
        }
    }

    /** CMN fbank of [len] samples from [off], frames repeated cyclically up to [FRAMES] (the model's fixed input). */
    private fun features(pcm: FloatArray, off: Int, len: Int): FloatArray {
        val fb = Frontend.kaldiFbank(pcm, len, Frontend.POVEY, off)
        val k = fb.size
        val mean = FloatArray(MELS)
        for (row in fb) for (m in 0 until MELS) mean[m] += row[m]
        for (m in 0 until MELS) mean[m] /= k
        val out = FloatArray(FRAMES * MELS)
        for (t in 0 until FRAMES) {
            val row = fb[t % k]
            for (m in 0 until MELS) out[t * MELS + m] = row[m] - mean[m]
        }
        return out
    }

    private fun joinMicro(e: FloatArray): Int {
        var best = -1
        var bestS = -2.0
        for (j in microSum.indices) {
            val s = cosine(microSum[j], e)
            if (s > bestS) { bestS = s; best = j }
        }
        if (best >= 0 && bestS >= MICRO) {
            val c = microSum[best]
            for (i in c.indices) c[i] += e[i]
            microCount[best] = microCount[best] + 1
            return best
        }
        microSum.add(e.copyOf()); microCount.add(1)
        return microSum.size - 1
    }

    /**
     * Groups everything seen so far into speakers. Windows that were labelled before keep their speaker numbers
     * wherever the new grouping agrees with the old one; new speakers get the next free numbers.
     */
    fun recluster() {
        flush()
        val nw = windows.size
        if (nw == 0) return
        val m = microSum.size
        val microLab = averageLinkage(Array(m) { normalized(microSum[it]) }, MERGE)
        var lab = IntArray(nw) { microLab[windows[it].micro] }
        // fold small speakers into the nearest big one
        val count = HashMap<Int, Int>()
        for (l in lab) count[l] = (count[l] ?: 0) + 1
        val minWindows = max(ABSORB_S / 0.75, ABSORB_SHARE * nw)
        val big = count.filter { it.value >= minWindows }.keys.ifEmpty { setOf(count.maxByOrNull { it.value }!!.key) }
        val cent = big.associateWith { l ->
            val c = FloatArray(DIM)
            for (i in 0 until nw) if (lab[i] == l) for (d in 0 until DIM) c[d] += windows[i].e[d]
            normalized(c)
        }
        lab = IntArray(nw) { i ->
            if (lab[i] in big) lab[i] else cent.maxByOrNull { (_, c) -> dot(c, windows[i].e) }!!.key
        }
        // keep earlier numbers: match new groups to old speakers by the windows they share
        val mapping = HashMap<Int, Int>()
        if (labelledUpTo > 0) {
            val pairs = HashMap<Long, Int>()
            for (i in 0 until labelledUpTo) {
                val key = lab[i].toLong() shl 32 or labels[i].toLong()
                pairs[key] = (pairs[key] ?: 0) + 1
            }
            val usedOld = HashSet<Int>()
            for ((key, _) in pairs.entries.sortedByDescending { it.value }) {
                val nl = (key shr 32).toInt()
                val ol = (key and 0xffffffffL).toInt()
                if (nl !in mapping && ol !in usedOld) { mapping[nl] = ol; usedOld.add(ol) }
            }
        }
        var next = (labels.take(labelledUpTo).maxOrNull() ?: 0) + 1
        val out = IntArray(nw)
        for (i in 0 until nw) {
            val l = lab[i]
            out[i] = mapping.getOrPut(l) { next++ }
        }
        labels = out
        labelledUpTo = nw
        speakers = out.toSet().size
    }

    /** Majority speaker of piece [index] after the last [recluster]; 0 when it had no speech windows (yet). */
    fun pieceSpeaker(index: Int): Int {
        val votes = HashMap<Int, Int>()
        for (i in 0 until labelledUpTo) if (windows[i].piece == index) votes[labels[i]] = (votes[labels[i]] ?: 0) + 1
        return votes.maxByOrNull { it.value }?.key ?: 0
    }

    /**
     * Speaker of every model output frame of piece [index] (frame j covers samples j × 960 of the piece), after a
     * final [recluster]. Speech runs take their windows' labels (3-window majority, split halfway between windows
     * that differ); frames outside any run take the nearest run's speaker. 0 only when the recording had no speech.
     */
    fun frameSpeakers(index: Int, frames: Int): IntArray {
        val start = pieces[index][0]
        val segs = ArrayList<LongArray>() // [from, to, label] in recording samples, sorted
        for (ri in runs.indices) {
            val r = runs[ri]
            if (r.piece != index) continue
            val idx = (0 until labelledUpTo).filter { windows[it].run == ri }
            if (idx.isEmpty()) {
                segs.add(longArrayOf(r.start, r.start + r.n, nearestLabel(r.start + r.n / 2).toLong()))
                continue
            }
            val ls = IntArray(idx.size) { labels[idx[it]] }
            if (ls.size >= 3) {
                val sm = ls.copyOf()
                for (i in 1 until ls.size - 1) sm[i] = majority(ls[i - 1], ls[i], ls[i + 1])
                System.arraycopy(sm, 0, ls, 0, ls.size)
            }
            var from = r.start
            for (i in idx.indices) {
                val to = if (i + 1 < idx.size) (windows[idx[i]].center + windows[idx[i + 1]].center) / 2 else r.start + r.n
                segs.add(longArrayOf(from, to, ls[i].toLong()))
                from = to
            }
        }
        val out = IntArray(frames)
        if (segs.isEmpty()) {
            val l = nearestLabel(start + frames * 480L)
            out.fill(l)
            return out
        }
        for (j in 0 until frames) {
            val t = start + j * 960L + 480
            var best = segs[0]
            var bestD = Long.MAX_VALUE
            for (s in segs) {
                val d = if (t < s[0]) s[0] - t else if (t >= s[1]) t - s[1] + 1 else 0
                if (d < bestD) { bestD = d; best = s }
                if (d == 0L) break
            }
            out[j] = best[2].toInt()
        }
        return out
    }

    private fun nearestLabel(t: Long): Int {
        var best = 0
        var bestD = Long.MAX_VALUE
        for (i in 0 until labelledUpTo) {
            val d = kotlin.math.abs(windows[i].center - t)
            if (d < bestD) { bestD = d; best = labels[i] }
        }
        return best
    }

    /**
     * Display names: a speaker takes a library name when its voiceprint's best match is ≥ [threshold] and beats the
     * runner-up person by ≥ [margin] (each name used once); otherwise "说话人N". [library]: name → one or more
     * voiceprints (e.g. one from calls, one from meetings).
     */
    fun names(library: Map<String, List<FloatArray>>, threshold: Double = 0.55, margin: Double = 0.08): Map<Int, String> {
        val out = HashMap<Int, String>()
        val used = HashSet<String>()
        for (l in labels.take(labelledUpTo).toSortedSet()) {
            out[l] = "说话人$l"
            if (library.isEmpty()) continue
            val c = FloatArray(DIM)
            for (i in 0 until labelledUpTo) if (labels[i] == l) for (d in 0 until DIM) c[d] += windows[i].e[d]
            val cn = normalized(c)
            val sims = library.map { (name, vs) -> name to vs.maxOf { cosine(it, cn) } }.sortedByDescending { it.second }
            val (p1, s1) = sims[0]
            val s2 = if (sims.size > 1) sims[1].second else -1.0
            if (s1 >= threshold && s1 - s2 >= margin && p1 !in used) { out[l] = p1; used.add(p1) }
        }
        return out
    }

    private fun noiseFloor(): Double {
        if (rmsTotal == 0L) return 0.0
        val target = rmsTotal / 10
        var acc = 0L
        for (b in 0 until RMS_BINS) {
            acc += rmsHist[b]
            if (acc > target) return binValue(b)
        }
        return binValue(RMS_BINS - 1)
    }

    companion object {
        const val FRAMES = 148 // 1.5 s at 10 ms
        const val MELS = 80
        const val DIM = 192
        const val WIN = 24000
        const val HOP = 12000
        const val MIN_WIN = 8000
        const val FLOOR = 200.0
        const val BRIDGE = 30 // 0.3 s
        const val MIN_RUN = 30
        const val MICRO = 0.7
        const val MERGE = 0.65
        const val ABSORB_S = 15.0
        const val ABSORB_SHARE = 0.03
        private const val RMS_BINS = 200

        // log-spaced RMS bins from 1 to ~32768
        private fun bin(r: Double): Int = min(RMS_BINS - 1, max(0, (kotlin.math.ln(max(r, 1.0)) / kotlin.math.ln(32768.0) * RMS_BINS).toInt()))
        private fun binValue(b: Int): Double = Math.exp((b + 1).toDouble() / RMS_BINS * kotlin.math.ln(32768.0))

        private fun majority(a: Int, b: Int, c: Int): Int = if (a == c) a else b

        fun dot(a: FloatArray, b: FloatArray): Double {
            var s = 0.0
            for (i in a.indices) s += a[i].toDouble() * b[i]
            return s
        }

        fun cosine(a: FloatArray, b: FloatArray): Double {
            var s = 0.0; var na = 0.0; var nb = 0.0
            for (i in a.indices) { s += a[i].toDouble() * b[i]; na += a[i].toDouble() * a[i]; nb += b[i].toDouble() * b[i] }
            return s / (sqrt(na) * sqrt(nb) + 1e-12)
        }

        fun normalized(v: FloatArray): FloatArray {
            var n = 0.0
            for (x in v) n += x.toDouble() * x
            val k = (1.0 / (sqrt(n) + 1e-12)).toFloat()
            return FloatArray(v.size) { v[it] * k }
        }

        /**
         * Average-linkage (UPGMA) clustering of unit vectors on cosine distance, merging while the closest pair is
         * within [maxDist] (scipy linkage(method="average", metric="cosine") + fcluster(criterion="distance")).
         * Returns a group id per point.
         */
        fun averageLinkage(p: Array<FloatArray>, maxDist: Double): IntArray {
            val n = p.size
            val d = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 0.0 else 1.0 - dot(p[i], p[j]) } }
            val size = IntArray(n) { 1 }
            val alive = BooleanArray(n) { true }
            val group = IntArray(n) { it }
            while (true) {
                var bi = -1; var bj = -1; var bd = Double.MAX_VALUE
                for (i in 0 until n) if (alive[i]) for (j in i + 1 until n) if (alive[j] && d[i][j] < bd) { bd = d[i][j]; bi = i; bj = j }
                if (bi < 0 || bd > maxDist) break
                for (k in 0 until n) if (alive[k] && k != bi && k != bj) {
                    val v = (size[bi] * d[bi][k] + size[bj] * d[bj][k]) / (size[bi] + size[bj])
                    d[bi][k] = v; d[k][bi] = v
                }
                size[bi] += size[bj]
                alive[bj] = false
                for (k in 0 until n) if (group[k] == bj) group[k] = bi
            }
            return group
        }
    }
}

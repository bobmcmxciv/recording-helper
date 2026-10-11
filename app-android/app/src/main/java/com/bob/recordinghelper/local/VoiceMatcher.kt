package com.bob.recordinghelper.local

import com.bob.recordinghelper.asr.SpeakerTracker

/** Cosine similarity, not a calibrated probability and never an authentication decision. */
object VoiceMatcher {
    const val THRESHOLD = 0.70
    const val MARGIN = 0.10
    data class Match(val name: String?, val score: Double, val margin: Double)
    fun valid(v: FloatArray): Boolean = v.size == SpeakerTracker.DIM && v.all { it.isFinite() } && v.any { kotlin.math.abs(it) > 1e-6 }
    fun center(vectors: List<FloatArray>): FloatArray {
        require(vectors.isNotEmpty() && vectors.all(::valid)) { "声纹样本无效" }
        val sum = FloatArray(SpeakerTracker.DIM)
        for (v in vectors) { val u = SpeakerTracker.normalized(v); for (i in sum.indices) sum[i] += u[i] }
        require(sum.any { kotlin.math.abs(it) > 1e-6 }) { "声纹样本互相冲突" }
        return SpeakerTracker.normalized(sum)
    }
    fun score(a: FloatArray, b: FloatArray): Double = if (valid(a) && valid(b)) SpeakerTracker.cosine(a, b).coerceIn(-1.0, 1.0) else -1.0
    fun match(vector: FloatArray, library: Map<String, List<FloatArray>>): Match {
        if (!valid(vector)) return Match(null, -1.0, 0.0)
        val ranked = library.mapNotNull { (name, vs) ->
            val clean = vs.filter(::valid)
            if (clean.isEmpty()) null else name to score(vector, center(clean))
        }.sortedByDescending { it.second }
        val first = ranked.firstOrNull() ?: return Match(null, -1.0, 0.0)
        val margin = first.second - (ranked.getOrNull(1)?.second ?: -1.0)
        return Match(first.first.takeIf { first.second >= THRESHOLD && margin >= MARGIN }, first.second, margin)
    }
    fun coherent(vectors: List<FloatArray>): Boolean {
        if (vectors.size < 6 || !vectors.all(::valid)) return false
        val c = try { center(vectors) } catch (_: Exception) { return false }
        return vectors.count { score(it, c) >= 0.75 } >= kotlin.math.ceil(vectors.size * .85).toInt()
    }
}

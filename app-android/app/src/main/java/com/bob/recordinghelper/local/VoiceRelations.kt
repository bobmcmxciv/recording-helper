package com.bob.recordinghelper.local

/** Conservative, evidence-only heuristic. Neither an overlap detector nor proof of identity. */
object VoiceRelations {
    const val WINDOW = 24000L
    const val CROSS_MATCH = .78
    data class Window(val start: Long, val vector: FloatArray)
    data class Evidence(val sourceId: String, val selfScore: Double, val otherSelfScore: Double,
        val selfSeconds: Double, val otherSeconds: Double, val other: FloatArray,
        val samples: List<FloatArray>, val previewStart: Double, val previewSeconds: Double)
    data class Result(val evidence: Evidence?, val reason: String)
    data class Candidate(val calls: List<Evidence>, val vector: FloatArray, val minimumSimilarity: Double,
        val ambiguous: Boolean, val ready: Boolean) {
        val samples: List<FloatArray> get() = calls.flatMap { it.samples }
    }

    fun analyze(sourceId: String, windows: List<Window>, self: FloatArray, knownOthers: Map<String, List<FloatArray>> = emptyMap()): Result {
        require(VoiceMatcher.valid(self))
        val clean = windows.filter { it.start >= 0 && VoiceMatcher.valid(it.vector) }.distinctBy { it.start }.sortedBy { it.start }
        if (clean.size < 14) return Result(null, "有效声音太少，需本人和另一人各有至少约 6 秒清晰语音")
        // Do not force k=2 or absorb a third speaker into a large cluster.
        val micro = arrayListOf<MutableList<Window>>()
        val centers = arrayListOf<FloatArray>()
        for (w in clean) {
            val best = centers.indices.maxByOrNull { VoiceMatcher.score(w.vector, centers[it]) }
            if (best != null && VoiceMatcher.score(w.vector, centers[best]) >= .78) {
                micro[best].add(w); centers[best] = VoiceMatcher.center(micro[best].map { it.vector })
            } else if (micro.size < 48) { micro.add(mutableListOf(w)); centers.add(w.vector) }
        }
        val groups = completeGroups(centers, .72).map { indices -> indices.flatMap { micro[it] } }
        val stable = groups.filter { it.size >= 6 && seconds(it) >= 6 && VoiceMatcher.coherent(it.map { w -> w.vector }) }
        if (stable.size != 2) return Result(null, "未得到两个稳定声音组（可能单人、多人、噪声或说话太短），不按排除法强行建档")
        if (groups.any { it !in stable && it.size >= 3 && seconds(it) >= 3 })
            return Result(null, "存在第三个持续声音组，不把其声音并入对方")
        if (stable.sumOf { it.size }.toDouble() / clean.size < .85) return Result(null, "未归属声音过多，可能混入第三人或重叠说话")
        val vectors = stable.map { VoiceMatcher.center(it.map { w -> w.vector }) }
        val order = vectors.indices.sortedByDescending { VoiceMatcher.score(vectors[it], self) }
        val me = order[0]; val them = order[1]
        val score = VoiceMatcher.score(vectors[me], self); val otherScore = VoiceMatcher.score(vectors[them], self)
        val library = linkedMapOf("self-anchor" to listOf(self)).apply {
            knownOthers.entries.forEachIndexed { i, e -> put("known-$i", e.value) }
        }
        if (score < .72 || score - otherScore < .15 || VoiceMatcher.match(vectors[me], library).name != "self-anchor")
            return Result(null, "未可靠找到本人的声音；电话音质变化也可能导致此结果")
        if (otherScore > .55 || VoiceMatcher.score(vectors[me], vectors[them]) > .55)
            return Result(null, "两个声音与本人过于相似，保留未知，不强行区分")
        val other = stable[them].sortedBy { it.start }
        val span = longestRun(other)
        if (span.second < 3) return Result(null, "缺少至少 3 秒的连续对方片段，无法提供可靠试听依据")
        val samples = representative(other.map { it.vector })
        if (!VoiceMatcher.coherent(samples)) return Result(null, "代表样本不稳定，请换一段更清晰的对话")
        return Result(Evidence(sourceId, score, otherScore, seconds(stable[me]), seconds(other),
            VoiceMatcher.center(samples), samples, span.first, minOf(6.0, span.second)), "检测到疑似本人和另一位说话人，等待跨录音佐证")
    }

    fun candidates(evidence: List<Evidence>): List<Candidate> {
        val distinct = evidence.distinctBy { it.sourceId }.sortedBy { it.sourceId }
        if (distinct.isEmpty()) return emptyList()
        val groups = completeGroups(distinct.map { it.other }, CROSS_MATCH).map { ids -> ids.map { distinct[it] } }
        val centers = groups.map { VoiceMatcher.center(it.map { e -> e.other }) }
        return groups.mapIndexed { index, group ->
            // A near competing cluster may be a split identity or lookalike: neither is auto-accepted.
            val ambiguous = centers.indices.any { it != index && VoiceMatcher.score(centers[index], centers[it]) >= .68 }
            val minimum = group.indices.flatMap { a -> (a + 1 until group.size).map { b -> VoiceMatcher.score(group[a].other, group[b].other) } }.minOrNull() ?: 1.0
            Candidate(group, centers[index], minimum, ambiguous, group.size >= 2 && !ambiguous &&
                group.sumOf { it.otherSeconds } >= 12 && VoiceMatcher.coherent(group.flatMap { it.samples }))
        }.sortedWith(compareByDescending<Candidate> { it.calls.size }.thenBy { it.calls.first().sourceId })
    }

    /** Complete-link prevents A≈B≈C from transitively merging A and C when A≉C. */
    internal fun completeGroups(vectors: List<FloatArray>, threshold: Double): List<List<Int>> {
        val scores = Array(vectors.size) { i -> DoubleArray(vectors.size) { j -> VoiceMatcher.score(vectors[i], vectors[j]) } }
        val groups = vectors.indices.map { mutableListOf(it) }.toMutableList()
        while (true) {
            var best = threshold; var a = -1; var b = -1
            for (i in groups.indices) for (j in i + 1 until groups.size) {
                val score = groups[i].minOf { x -> groups[j].minOf { y -> scores[x][y] } }
                if (score >= best) { best = score; a = i; b = j }
            }
            if (a < 0) break
            groups[a].addAll(groups[b]); groups.removeAt(b)
        }
        return groups
    }
    internal fun seconds(windows: List<Window>): Double {
        var total = 0L; var end = -1L
        for (w in windows.sortedBy { it.start }) { total += (w.start + WINDOW - maxOf(w.start, end)).coerceAtLeast(0); end = maxOf(end, w.start + WINDOW) }
        return total / 16000.0 // overlapping windows never double-count evidence duration
    }
    private fun longestRun(windows: List<Window>): Pair<Double, Double> {
        var start = -1L; var end = -1L; var bestStart = 0L; var bestLength = 0L
        for (w in windows) {
            if (start < 0 || w.start > end) start = w.start
            end = w.start + WINDOW
            if (end - start > bestLength) { bestStart = start; bestLength = end - start }
        }
        return bestStart / 16000.0 to bestLength / 16000.0
    }
    private fun representative(vs: List<FloatArray>): List<FloatArray> = if (vs.size <= 12) vs else List(12) { vs[it * (vs.size - 1) / 11] }
}

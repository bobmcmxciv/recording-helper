package com.bob.recordinghelper.llm

/**
 * Transcript polishing with a local LLM: fixes homophone / recognition errors, punctuation, filler words and
 * stutters, nothing else. The text goes in chunks of about [CHUNK_CHARS] characters (whole "名字：文字" lines, long
 * lines split at sentence ends); each chunk sees the end of the previous polished chunk for context.
 *
 * Safety: a chunk whose output does not keep the same speaker lines, or whose length is outside 0.5-1.5x of the
 * input, is kept as it was (counted in [Result.keptOriginal]) - a wrong "polish" is worse than none.
 */
class Polisher(private val model: TextModel) {

    data class Progress(val chunk: Int, val chunks: Int, val partial: String)

    data class Result(
        val text: String,
        val chunks: Int,
        /** chunks left as they were because the output failed the checks or the model failed */
        val keptOriginal: Int,
        val ms: Long,
        /** model outputs of the kept-original chunks, for diagnosis (raw, before cleaning) */
        val rejected: List<String> = emptyList(),
    )

    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        model.cancel()
    }

    /**
     * [text]: one transcript; lines of the form "名字：文字" keep their speaker prefix. [onProgress] gets the output of the
     * current chunk as it grows (any thread).
     */
    fun polish(text: String, onProgress: (Progress) -> Unit = {}): Result {
        val t0 = System.currentTimeMillis()
        val chunks = chunk(text)
        val out = StringBuilder()
        var kept = 0
        val rejected = ArrayList<String>()
        var context = ""
        for ((i, c) in chunks.withIndex()) {
            if (cancelled) throw InterruptedException("polishing cancelled")
            val partial = StringBuilder()
            val maxTokens = (model.countTokens(c) * 1.6).toInt() + 64
            onProgress(Progress(i, chunks.size, ""))
            val raw = model.chat(SYSTEM, user(context, c), maxTokens) { piece ->
                partial.append(piece)
                onProgress(Progress(i, chunks.size, partial.toString()))
            }
            if (cancelled) throw InterruptedException("polishing cancelled")
            val cleaned = raw?.let { clean(it) }
            val good = cleaned != null && acceptable(c, cleaned)
            val piece = if (good) cleaned!! else c.also { kept++; rejected.add(raw ?: "(no output)") }
            if (out.isNotEmpty()) out.append('\n')
            out.append(piece)
            context = piece.takeLast(CONTEXT_CHARS)
            onProgress(Progress(i, chunks.size, piece))
        }
        return Result(out.toString(), chunks.size, kept, System.currentTimeMillis() - t0, rejected)
    }

    companion object {
        const val CHUNK_CHARS = 350
        private const val CONTEXT_CHARS = 120

        private const val SYSTEM = "你是录音转写稿的校对员。转写由语音识别生成，可能有同音错字、识别错误、缺标点、口头禅和重复。\n" +
            "只做这些修改：改正同音错别字和明显的识别错误；补全、改正标点；删去没有意义的口头禅（如“嗯”“呃”“那个那个”）和结巴重复。\n" +
            "严格禁止：增加原文没有的内容；删除有意义的内容；改变原意；总结、解释、续写或翻译。\n" +
            "保持原来的行数和顺序。如果一行以“名字：”开头，这个“名字：”原样保留。\n" +
            "只输出校对后的文本，不要输出任何说明。"

        fun user(context: String, chunk: String): String = buildString {
            if (context.isNotBlank()) append("上文（只作参考，不要输出）：\n").append(context).append("\n\n")
            append("需要校对的文本：\n").append(chunk)
        }

        /** Qwen ChatML with thinking switched off (an empty think block). */
        fun prompt(context: String, chunk: String): String {
            val user = buildString {
                if (context.isNotBlank()) append("上文（只作参考，不要输出）：\n").append(context).append("\n\n")
                append("需要校对的文本：\n").append(chunk)
            }
            return "<|im_start|>system\n$SYSTEM<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n" +
                "<|im_start|>assistant\n<think>\n\n</think>\n\n"
        }

        private val SPEAKER = Regex("^([^：:\\n]{1,20})[：:]")

        /** Speaker prefix of a line ("张三：..." -> "张三"), or null. */
        fun speakerOf(line: String): String? = SPEAKER.find(line)?.groupValues?.get(1)?.takeIf { '，' !in it && '。' !in it }

        /** Whole lines up to about [CHUNK_CHARS]; a longer line is split at sentence ends, keeping its speaker prefix. */
        fun chunk(text: String): List<String> {
            val lines = ArrayList<String>()
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line.length <= CHUNK_CHARS) { lines.add(line); continue }
                val who = speakerOf(line)
                val body = if (who != null) line.substring(who.length + 1) else line
                val prefix = if (who != null) "$who：" else ""
                var cur = StringBuilder()
                for (s in sentences(body)) {
                    if (cur.isNotEmpty() && cur.length + s.length > CHUNK_CHARS) {
                        lines.add(prefix + cur); cur = StringBuilder()
                    }
                    cur.append(s)
                }
                if (cur.isNotEmpty()) lines.add(prefix + cur)
            }
            val chunks = ArrayList<String>()
            val cur = StringBuilder()
            for (l in lines) {
                if (cur.isNotEmpty() && cur.length + 1 + l.length > CHUNK_CHARS) {
                    chunks.add(cur.toString()); cur.setLength(0)
                }
                if (cur.isNotEmpty()) cur.append('\n')
                cur.append(l)
            }
            if (cur.isNotEmpty()) chunks.add(cur.toString())
            return chunks
        }

        /** Sentences ending in 。？！?! (the end mark stays with its sentence); pieces over [CHUNK_CHARS] are cut. */
        private fun sentences(s: String): List<String> {
            val out = ArrayList<String>()
            var start = 0
            for (i in s.indices) {
                if (s[i] in "。？！?!" ) { out.add(s.substring(start, i + 1)); start = i + 1 }
            }
            if (start < s.length) out.add(s.substring(start))
            return out.flatMap { p -> p.chunked(CHUNK_CHARS) }
        }

        // spaces the model puts between Chinese and digits / Latin ("说话人 1：", "7 月"); the transcripts have none
        private val CJK_SPACE = Regex("(?<=[\\u4e00-\\u9fff，。？！、：；“”（）])[ \\t]+|[ \\t]+(?=[\\u4e00-\\u9fff，。？！、：；“”（）])")

        /** Drops a leftover think block, any echo of the instructions' headings, and spaces next to Chinese text. */
        fun clean(out: String): String {
            var s = out
            val endThink = s.indexOf("</think>")
            if (endThink >= 0) s = s.substring(endThink + 8)
            s = s.replace("需要校对的文本：", "").replace("校对后的文本：", "")
            return s.lines().map { it.trim().replace(CJK_SPACE, "") }.filter { it.isNotEmpty() }.joinToString("\n")
        }

        /** Same speaker lines in the same order (when the input has speakers), and a plausible length. */
        fun acceptable(input: String, output: String): Boolean {
            if (output.isBlank()) return false
            val ratio = output.length.toDouble() / input.length
            if (ratio < 0.5 || ratio > 1.5) return false
            val inSpk = input.lines().filter { it.isNotBlank() }.map { speakerOf(it)?.filterNot(Char::isWhitespace) }
            if (inSpk.all { it == null }) return true
            val outSpk = output.lines().filter { it.isNotBlank() }.map { speakerOf(it)?.filterNot(Char::isWhitespace) }
            return inSpk == outSpk
        }
    }
}

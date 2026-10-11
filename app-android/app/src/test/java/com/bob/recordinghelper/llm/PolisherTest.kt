package com.bob.recordinghelper.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolisherTest {
    /** Fake model: echoes the chunk after "需要校对的文本：" with a transformation. */
    private class Fake(val f: (String) -> String) : TextModel {
        val prompts = ArrayList<String>()
        override fun generate(prompt: String, maxTokens: Int, onText: (String) -> Unit): String? {
            prompts.add(prompt)
            val chunk = prompt.substringAfter("需要校对的文本：\n").substringBefore("<|im_end|>")
            val out = f(chunk)
            out.chunked(5).forEach(onText)
            return out
        }
        override fun countTokens(text: String) = text.length
    }

    private val transcript = (1..12).joinToString("\n") { i ->
        (if (i % 2 == 0) "庞博：" else "张聪：") + "嗯这个这个我们第${i}次会议讨论一下接口的的设计方案。".repeat(3)
    }

    @Test
    fun chunksKeepLinesAndSpeakers() {
        val chunks = Polisher.chunk(transcript)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= Polisher.CHUNK_CHARS })
        assertEquals(transcript.lines().size, chunks.sumOf { it.lines().size })
        // a single line longer than a chunk is split at sentence ends and keeps its speaker on every part
        val long = "庞博：" + "第一句话很长很长。".repeat(60)
        val parts = Polisher.chunk(long)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.startsWith("庞博：") && it.length <= Polisher.CHUNK_CHARS })
    }

    @Test
    fun goodOutputIsUsedAndContextFlows() {
        val fake = Fake { c -> c.replace("嗯", "").replace("这个这个", "这个").replace("的的", "的") }
        val r = Polisher(fake).polish(transcript)
        assertEquals(0, r.keptOriginal)
        assertFalse(r.text.contains("嗯"))
        assertEquals(transcript.lines().size, r.text.lines().size)
        assertTrue(fake.prompts[0].contains("<think>\n\n</think>"))
        assertFalse(fake.prompts[0].contains("上文"))
        assertTrue(fake.prompts[1].contains("上文（只作参考，不要输出）"))
    }

    @Test
    fun badOutputFallsBackToOriginal() {
        // drops a speaker / summarises -> must keep the original chunk
        val r1 = Polisher(Fake { "这次会议讨论了接口设计。" }).polish(transcript)
        assertEquals(r1.chunks, r1.keptOriginal)
        assertEquals(transcript, r1.text)
        // speaker label changed -> rejected
        val r2 = Polisher(Fake { c -> c.replace("庞博：", "庞老师：") }).polish(transcript)
        assertEquals(r2.chunks, r2.keptOriginal)
        // model failure -> original
        val r3 = Polisher(object : TextModel {
            override fun generate(prompt: String, maxTokens: Int, onText: (String) -> Unit): String? = null
            override fun countTokens(text: String) = text.length
        }).polish(transcript)
        assertEquals(transcript, r3.text)
    }

    @Test
    fun cleanStripsThinkAndHeadings() {
        assertEquals("张聪：好的。", Polisher.clean("<think>想一想</think>\n校对后的文本：\n张聪：好的。\n"))
        assertTrue(Polisher.acceptable("张聪：嗯好的好的。", "张聪：好的。"))
        assertFalse(Polisher.acceptable("张聪：嗯好的好的。\n庞博：行。", "张聪：好的。"))
    }

    @Test
    fun spacesTheModelAddsAroundChineseAreRemoved() {
        // Qwen3.5-9B on the Mi Pad wrote "说话人 1：" and "7 月", which used to fail the speaker check
        val input = "说话人1：哦，每年7月份，那就是\n庞博：现在已经租两年了\n说话人1：溢价率大概在5%左右啊"
        val out = Polisher.clean("说话人 1：哦，每年 7 月份，那就是\n庞博：现在已经租两年了。\n说话人 1：溢价率大概在 5% 左右啊。")
        assertEquals("说话人1：哦，每年7月份，那就是\n庞博：现在已经租两年了。\n说话人1：溢价率大概在5%左右啊。", out)
        assertTrue(Polisher.acceptable(input, out))
        assertEquals("用AI模型GPT 4", Polisher.clean("用 AI 模型 GPT 4"))
    }
}

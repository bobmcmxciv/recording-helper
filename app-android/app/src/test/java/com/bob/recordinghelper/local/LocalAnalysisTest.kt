package com.bob.recordinghelper.local

import com.bob.recordinghelper.llm.TextModel
import org.junit.Assert.*
import org.junit.Test

class LocalAnalysisTest {
    @Test fun acceptsOnlyGroundedTodos() {
        val result = LocalAnalysis.parse("""{"summary":"项目安排","todos":[{"title":"提交报告","due":"明天","evidence":"明天交报告"}]}""", "请明天交报告。")
        assertEquals(1, result.getJSONArray("todos").length())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInventedEvidence() {
        LocalAnalysis.parse("""{"summary":"安排","todos":[{"title":"转账","evidence":"汇款给我"}]}""", "明天开会。")
    }
    @Test fun copiesNumberedEvidenceWithoutRewritingAsr() {
        val result = LocalAnalysis.parse("""{"summary":"安排","todos":[{"title":"提交报告","evidenceId":2}]}""", "测试会议。请小李明天下午3点之前提交测试报告。好的。")
        val todo = result.getJSONArray("todos").getJSONObject(0)
        assertEquals("请小李明天下午3点之前提交测试报告。", todo.getString("evidence"))
        assertFalse(todo.has("evidenceId"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMissingSentenceId() {
        LocalAnalysis.parse("""{"summary":"安排","todos":[{"title":"报告","evidenceId":99}]}""", "明天交报告。")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsFractionalSentenceId() {
        LocalAnalysis.parse("""{"summary":"安排","todos":[{"title":"报告","evidenceId":1.2}]}""", "明天交报告。")
    }
    @Test fun doesNotTruncateLongTranscript() {
        var calls = 0
        val model = object : TextModel {
            override fun countTokens(text: String) = text.length
            override fun generate(prompt: String, maxTokens: Int, onText: (String) -> Unit): String {
                calls++; return """{"summary":"第${calls}段","todos":[]}"""
            }
        }
        val result = LocalAnalysis.analyze(model, "测".repeat(3000), "2026-10-11")
        assertEquals(3, calls)
        assertTrue(result.getString("summary").contains("第3段"))
    }
    @Test(expected = InterruptedException::class) fun honorsCancellation() {
        val model = object : TextModel {
            override fun countTokens(text: String) = 1
            override fun generate(prompt: String, maxTokens: Int, onText: (String) -> Unit) = error("must not run")
        }
        LocalAnalysis.analyze(model, "test", "today") { true }
    }
}

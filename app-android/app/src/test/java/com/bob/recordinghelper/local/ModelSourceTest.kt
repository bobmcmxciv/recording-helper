package com.bob.recordinghelper.local

import com.bob.recordinghelper.llm.TextModel
import org.junit.Assert.*
import org.junit.Test

class ModelSourceTest {
    private val sha = "0c1bf068c38f58a9ca6d70e1180a3f7923804aec08e7f892e87d2f218ba7155b"

    @Test fun parsesManifestAndDefaultsRequiredFlags() {
        val list = ModelSource.parse("""{"schema":1,"files":[
            {"slot":"sv.tflite","name":"ASR","url":"https://x.example/sv.tflite","sha256":"$sha","size":10},
            {"slot":"model.gguf","url":"https://x.example/m.gguf","mirror":"https://y.example/m.gguf","sha256":"$sha","size":20,"required":false}]}""")
        assertEquals(2, list.size)
        assertTrue(list[0].required); assertFalse(list[1].required)
        assertEquals("https://y.example/m.gguf", list[1].mirror)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownSlot() {
        ModelSource.parse("""{"files":[{"slot":"evil.so","url":"https://x.example/e","sha256":"$sha"}]}""")
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsPlainHttp() {
        ModelSource.parse("""{"files":[{"slot":"am.mvn","url":"http://x.example/am.mvn","sha256":"$sha"}]}""")
    }

    @Test fun categoryNormalization() {
        assertEquals("work", LocalAnalysis.category("Work"))
        assertEquals("work", LocalAnalysis.category("公事"))
        assertEquals("personal", LocalAnalysis.category("personal"))
        assertEquals("personal", LocalAnalysis.category("生活"))
        assertEquals("", LocalAnalysis.category("unknown"))
        assertEquals("", LocalAnalysis.category(null))
    }

    @Test fun extractsJsonFromProseAndFences() {
        assertEquals("""{"a":1}""", LocalAnalysis.extractJson("好的，结果如下：\n```json\n{\"a\":1}\n```\n以上。"))
        assertEquals("""{"a":1}""", LocalAnalysis.extractJson("<think>x</think>{\"a\":1}"))
    }

    @Test fun digestParsesOverviewAndLimitsSuggestions() {
        val model = object : TextModel {
            override fun countTokens(text: String) = text.length
            override fun generate(prompt: String, maxTokens: Int, onText: (String) -> Unit) =
                """{"overview":"本周共 3 件事，公事 2 件，私事 1 件。","first":["交报告","订会议室","买牛奶","多余"]}"""
        }
        val d = LocalAnalysis.digest(model, "本周", listOf("[公事] 交报告（明天）", "[私事] 买牛奶"))
        assertTrue(d.getString("overview").contains("3 件事"))
        assertEquals(3, d.getJSONArray("first").length())
    }

    @Test fun analysisKeepsCategoryOnTodos() {
        val result = LocalAnalysis.parse("""{"summary":"安排","todos":[{"title":"交报告","category":"工作","evidenceId":1}]}""", "明天交报告。")
        assertEquals("work", result.getJSONArray("todos").getJSONObject(0).getString("category"))
    }
}

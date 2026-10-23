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

    @Test fun streamsTokensAndCapturesRawTraceDuringAnalyzeAndDigest() {
        val model = object : TextModel {
            override fun countTokens(text: String) = 42
            override fun generate(prompt: String, maxTokens: Int, onText: (String) -> Unit): String {
                val reply = if (prompt.contains("个人事务助理")) {
                    """<think>先盘点一下紧急事项</think>{"overview":"共1件事，需今日完成","first":["提交报告"]}"""
                } else {
                    """<think>定位第1句为行动项</think>{"summary":"项目安排","todos":[{"title":"提交报告","due":"明天","category":"work","evidenceId":1}]}"""
                }
                reply.chunked(8).forEach(onText)
                return reply
            }
        }
        val streamed = StringBuilder()
        var prefillTokens = 0
        val res = LocalAnalysis.analyze(
            model = model,
            text = "请明天提交报告。",
            date = "2026-10-22",
            onToken = { streamed.append(it) },
            onPrefill = { idx, total, tokens ->
                assertEquals(1, idx)
                assertEquals(1, total)
                prefillTokens = tokens
            },
        )
        assertEquals(42, prefillTokens)
        assertEquals("项目安排", res.getString("summary"))
        assertTrue(streamed.toString().contains("定位第1句为行动项"))
        assertTrue(res.optString("rawTrace").contains("<think>定位第1句为行动项</think>"))

        val digestStream = StringBuilder()
        var digestPrefill = 0
        val digestRes = LocalAnalysis.digest(
            model = model,
            period = "今天",
            lines = listOf("待办[公事] 提交报告（明天）"),
            onToken = { digestStream.append(it) },
            onPrefill = { digestPrefill = it },
        )
        assertEquals(42, digestPrefill)
        assertEquals("共1件事，需今日完成", digestRes.getString("overview"))
        assertTrue(digestStream.toString().contains("先盘点一下紧急事项"))
        assertTrue(digestRes.optString("rawTrace").contains("先盘点一下紧急事项"))
    }

    @Test fun remoteTextModelStreamsOpenAiAndAnthropicSseDeltas() {
        var usageLine = ""
        val openai = com.bob.recordinghelper.llm.RemoteTextModel(
            api = com.bob.recordinghelper.llm.LlmApi.OPENAI,
            baseUrl = "https://example.com/v1",
            apiKey = "sk-test",
            model = "deepseek-reasoner",
            onUsage = { usageLine = it },
        )
        val sseOpenAi = listOf(
            """data: {"choices":[{"delta":{"reasoning_content":"先分析第1句"}}]}""",
            """data: {"choices":[{"delta":{"reasoning_content":"，提取待办"}}]}""",
            """data: {"choices":[{"delta":{"content":"{\"summary\":\"纪要\""}}]}""",
            """data: {"choices":[{"delta":{"content":",\"todos\":[]}"}}],"usage":{"prompt_tokens":120,"completion_tokens":35}}""",
            """data: [DONE]"""
        ).joinToString("\n")
        val pieces = ArrayList<String>()
        val reader = sseOpenAi.byteInputStream().bufferedReader()
        val firstLine = reader.readLine()!!
        val full = openai.readSseStream(firstLine, reader) { pieces.add(it) }
        assertTrue(full.contains("<think>\n先分析第1句，提取待办\n</think>\n"))
        assertTrue(full.endsWith("""{"summary":"纪要","todos":[]}"""))
        assertTrue(pieces.size >= 4)
        assertTrue(usageLine.contains("120") && usageLine.contains("35"))

        val anthropic = com.bob.recordinghelper.llm.RemoteTextModel(
            api = com.bob.recordinghelper.llm.LlmApi.ANTHROPIC,
            baseUrl = "https://example.com",
            apiKey = "sk-ant",
            model = "claude-sonnet",
        )
        val sseAnthropic = listOf(
            """event: content_block_delta""",
            """data: {"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"思考步骤A"}}""",
            """data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"{\"overview\":\"总结\",\"first\":[]}"}}"""
        ).joinToString("\n")
        val antPieces = ArrayList<String>()
        val antReader = sseAnthropic.byteInputStream().bufferedReader()
        val antFirst = antReader.readLine()!!
        val antFull = anthropic.readSseStream(antFirst, antReader) { antPieces.add(it) }
        assertTrue(antFull.contains("<think>\n思考步骤A\n</think>\n"))
        assertTrue(antFull.endsWith("""{"overview":"总结","first":[]}"""))
    }

    @Test fun analysisStateTracksPrefillThinkingAndStreamingPhases() {
        AnalysisState.clearAll()
        AnalysisState.onPhase("rec-1", "正在加载本地大模型 qwen3.5（2.4GB）…", title = "晨会录音", model = "本地大模型 qwen3.5")
        assertEquals("正在加载本地大模型 qwen3.5（2.4GB）…", AnalysisState.statusLine())
        AnalysisState.onPrefill("rec-1", 1, 2, "正在预填充阅读第 1/2 段原文（约 680 tokens · CPU 4 核计算中）…", title = "晨会录音", model = "本地大模型 qwen3.5")
        assertTrue(AnalysisState.statusLine()!!.contains("正在预填充阅读第 1/2 段原文"))
        AnalysisState.onToken("rec-1", "<think>正在推理待办")
        assertTrue(AnalysisState.statusLine()!!.contains("正在深度思考推理（第 1/2 段"))
        AnalysisState.onToken("rec-1", "</think>{\"summary\":\"完成\"}")
        assertTrue(AnalysisState.statusLine()!!.contains("正在流式生成纪要与待办（第 1/2 段"))
        AnalysisState.onJobFinished("rec-1")
        AnalysisState.clearAll()
    }

    @Test fun quickExtractProducesImmediateSummaryAndGroundedTodosWithoutLlm() {
        val transcript = "说话人1：大家早上好，今天简单对齐一下进度。请小张明天下午3点前把项目预算报告发我。另外下周三之前需要确认客户合同条款。下班顺路去超市买牛奶。"
        val res = LocalAnalysis.quickExtract(transcript)
        assertTrue(res.getBoolean("quickExtracted"))
        assertTrue(res.getString("summary").isNotBlank())
        assertEquals("本机规则快筛（免大模型）", res.getString("analysisModel"))
        val todos = res.getJSONArray("todos")
        assertTrue("Expected at least 2 rule-extracted todos", todos.length() >= 2)
        for (i in 0 until todos.length()) {
            val t = todos.getJSONObject(i)
            val evidence = t.getString("evidence")
            assertTrue("Every quick-extracted todo must carry an exact source substring", transcript.contains(evidence))
        }
        val first = todos.getJSONObject(0)
        assertEquals("work", first.getString("category"))
        assertTrue(first.getString("due").contains("明天"))
    }
}

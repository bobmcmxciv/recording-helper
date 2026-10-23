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

    @Test fun ordersCoreSpeechSlotsBeforeOptionalSpeakerAndLargeLlm() {
        val slots = listOf("model.gguf", "spk.tflite", "sv.tflite", "am.mvn", "tokens.json")
            .sortedBy { ModelSource.slotPriority(it) }
        assertEquals(listOf("tokens.json", "am.mvn", "sv.tflite", "spk.tflite", "model.gguf"), slots)
    }

    @Test fun orderedUrlsSynthesizesAndPrioritizesDomesticHfMirror() {
        val hfPrimary = ModelSource.Entry(
            slot = "model.gguf",
            name = "LLM",
            url = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_0.gguf",
            mirror = "",
            sha256 = sha,
            size = 100L,
            required = false,
            note = ""
        )
        val (domPrimary, domFallback) = ModelSource.orderedUrls(hfPrimary, preferDomestic = true)
        assertEquals("https://hf-mirror.com/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_0.gguf", domPrimary)
        assertEquals("https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_0.gguf", domFallback)

        val (intlPrimary, intlFallback) = ModelSource.orderedUrls(hfPrimary, preferDomestic = false)
        assertEquals("https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_0.gguf", intlPrimary)
        assertEquals("https://hf-mirror.com/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_0.gguf", intlFallback)
    }

    @Test fun downloadHttpsWithResumeSupportsHttp206PartialContentAndMirrorFailover() {
        val payload = """{"hello":"world","tokens":["a","b","c","d","e","f","g"]}""".toByteArray(Charsets.UTF_8)
        val expectedSha = java.security.MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }
        val splitAt = payload.size / 2
        val rangeHeaderObserved = java.util.concurrent.atomic.AtomicReference("")
        val server = java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val serverThread = Thread {
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (_: Exception) { break }
                try {
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(sock.getInputStream(), Charsets.US_ASCII))
                    val reqLine = reader.readLine() ?: continue
                    val path = reqLine.split(" ").getOrElse(1) { "/" }
                    var range = ""
                    while (true) {
                        val line = reader.readLine()
                        if (line.isNullOrEmpty()) break
                        if (line.lowercase().startsWith("range:")) {
                            range = line.substringAfter(":").trim()
                        }
                    }
                    val out = sock.getOutputStream()
                    if (path.startsWith("/primary/")) {
                        out.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        out.flush()
                    } else {
                        rangeHeaderObserved.set(range)
                        if (range.startsWith("bytes=")) {
                            val offset = range.removePrefix("bytes=").removeSuffix("-").toInt()
                            val rest = payload.copyOfRange(offset, payload.size)
                            val headers = "HTTP/1.1 206 Partial Content\r\nContent-Length: ${rest.size}\r\nConnection: close\r\n\r\n"
                            out.write(headers.toByteArray(Charsets.US_ASCII))
                            out.write(rest)
                            out.flush()
                        } else {
                            val headers = "HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                            out.write(headers.toByteArray(Charsets.US_ASCII))
                            out.write(payload)
                            out.flush()
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { sock.close() }
                }
            }
        }.apply { isDaemon = true; start() }
        val tmpDir = java.nio.file.Files.createTempDirectory("rh-resume-test").toFile()
        try {
            val part = java.io.File(tmpDir, "tokens.json.part")
            val meta = java.io.File(tmpDir, "tokens.json.part.sha256")
            // Simulate an interrupted first attempt that already wrote the first half of bytes to .part
            part.writeBytes(payload.copyOfRange(0, splitAt))
            meta.writeText(expectedSha)

            var sawResumed = false
            val actualSha = ModelFiles.downloadHttpsWithResume(
                slot = "tokens.json",
                part = part,
                meta = meta,
                expectedHash = expectedSha,
                candidates = listOf("http://127.0.0.1:$port/primary/tokens.json", "http://127.0.0.1:$port/mirror/tokens.json"),
                allowLoopbackHttp = true,
                onProgress = { _, _, resumed -> if (resumed) sawResumed = true }
            )
            assertEquals(expectedSha, actualSha)
            assertEquals("bytes=$splitAt-", rangeHeaderObserved.get())
            assertTrue(sawResumed)
            assertArrayEquals(payload, part.readBytes())

            // Verify that changing expectedHash invalidates stale .part cache and deletes corrupt completed file on mismatch
            val wrongSha = "f".repeat(64)
            try {
                ModelFiles.downloadHttpsWithResume(
                    slot = "tokens.json",
                    part = part,
                    meta = meta,
                    expectedHash = wrongSha,
                    candidates = listOf("http://127.0.0.1:$port/mirror/tokens.json"),
                    allowLoopbackHttp = true,
                )
                fail("Should have rejected mismatched SHA-256")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("SHA-256"))
                assertFalse("Corrupt completed .part must be deleted on hash mismatch", part.exists())
            }
        } finally {
            runCatching { server.close() }
            serverThread.join(1000)
            tmpDir.deleteRecursively()
        }
    }
}

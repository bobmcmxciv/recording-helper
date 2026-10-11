package com.bob.recordinghelper.local

import androidx.test.platform.app.InstrumentationRegistry
import com.bob.recordinghelper.*
import com.bob.recordinghelper.llm.LlmEngine
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

/** Uses synthetic text/audio only. Does not read the user's recordings or enable sync. */
class OfflinePipelineTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun shortForegroundWorkDoesNotCrash() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val activity = ins.startActivitySync(android.content.Intent(ctx, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        repeat(20) {
            KeepAwake.acquire(ctx, "短任务回归测试")
            // Let the service request be posted, then release before / around delivery.
            ins.waitForIdleSync()
            KeepAwake.release(ctx)
        }
        ins.waitForIdleSync()
        Thread.sleep(12000)
        assertFalse(KeepAwake.active())
        ins.runOnMainSync { activity.finish() }
    }
    @Test fun nativeLayoutSnapshot() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val activity = ins.startActivitySync(android.content.Intent(ctx, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        ins.waitForIdleSync()
        // Draw only our own native view hierarchy. No lock-screen dismissal or other-app capture.
        ins.runOnMainSync {
            val decor = activity.window.decorView
            val metrics = activity.resources.displayMetrics
            if (decor.width == 0 || decor.height == 0) {
                decor.measure(android.view.View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, android.view.View.MeasureSpec.EXACTLY))
                decor.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            }
            fun assertNative(view: android.view.View) {
                assertFalse(view.javaClass.name.contains("WebView"))
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) assertNative(view.getChildAt(i))
            }
            assertNative(decor)
            val bitmap = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
            decor.draw(android.graphics.Canvas(bitmap))
            File(ctx.getExternalFilesDir(null), "v2-native-home.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            activity.finish()
        }
    }
    @Test fun localModelToPersistentTodos() = KeepAwake.hold(ctx, "离线模型验收（合成音频）") {
        assertFalse("Real-device suite must run with sync disabled", LocalSettings.sync(ctx))
        val txBefore = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())
        val model = LlmEngine.modelIn(LocalAsr.llmDir(ctx)) ?: error("LLM missing")
        val audio = File(ctx.cacheDir, "v2-synthetic-meeting.wav")
        InstrumentationRegistry.getInstrumentation().context.assets.open("meeting.wav").use { input -> audio.outputStream().use { input.copyTo(it) } }
        val started = System.currentTimeMillis()
        val text = try { ModelGate.hold { LocalAsr.open(ctx).use { it.transcribe(audio).result.uploadText } } } finally { audio.delete() }
        assertTrue("Synthetic speech recognition missed the expected topic", text.contains("报告"))
        val db = LocalStore.of(ctx)
        val id = db.transcript("v2-synthetic-instrumentation", "2.0 离线验收（测试数据）", text, "2026-10-11T10:00:00Z")
        val result = try {
            ModelGate.hold { LlmEngine.load(model).use { LocalAnalysis.analyze(it, text, "2026-10-11") } }
        } catch (e: Exception) {
            // Do not leave failed synthetic data pending for the next app-start worker.
            db.status(id, "error", e.message ?: "验收失败")
            throw e
        }
        db.analyzed(id, text, result)
        assertEquals("ready", db.get(id)!!.getString("status"))
        assertTrue(db.get(id)!!.getString("summary").isNotBlank())
        val todos = db.all("todo").filter { it.optString("recordingId") == id }
        assertTrue("Expected an explicit task from the synthetic meeting", todos.isNotEmpty())
        val todo = todos.first(); db.toggle(todo.getString("id"))
        assertNotEquals(todo.optBoolean("done"), db.get(todo.getString("id"))!!.optBoolean("done"))
        assertTrue(JSONObject(db.export()).getJSONArray("recordings").length() > 0)
        File(ctx.getExternalFilesDir(null), "v2-test-result.json").writeText(JSONObject().put("transcript", text)
            .put("analysis", result).put("elapsedMs", System.currentTimeMillis() - started).put("syncEnabled", LocalSettings.sync(ctx)).toString(2))
        val txAfter = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())
        if (txBefore >= 0 && txAfter >= 0) assertEquals("Offline pipeline sent network traffic", txBefore, txAfter)
    }
    @Test fun asrImportsSyntheticSilenceLocally() = KeepAwake.hold(ctx, "静音导入验收（合成音频）") {
        assertTrue("ASR model missing", LocalAsr.modelReady(ctx))
        val f = File(ctx.cacheDir, "v2-synthetic-silence.wav")
        // 1 second of 16 kHz mono PCM16 silence.
        val b = java.nio.ByteBuffer.allocate(32044).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(32036).put("WAVEfmt ".toByteArray()).putInt(16)
        b.putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(32000)
        f.writeBytes(b.array())
        try {
            ModelGate.hold { PhoneAsrBatch(ctx).use { batch ->
                val outcome = batch.process(f, "v2-silence-test", "2.0 静音测试.wav", null) {}
                assertTrue("ASR failed: ${batch.unavailableReason}", outcome == PhoneAsrBatch.Outcome.SILENT || outcome == PhoneAsrBatch.Outcome.SENT)
            } }
        } finally { f.delete() }
    }
    @Test fun syncAcknowledgementDoesNotLoseNewLocalEdit() {
        val db = LocalStore.of(ctx)
        val id = "todo-instrumentation-cas"
        db.put("todo", JSONObject().put("id", id).put("title", "同步冲突测试").put("done", false))
        val sent = db.pending().first { it.getString("id") == id }
        db.toggle(id)
        db.receive(JSONObject().put("id", id).put("kind", "todo").put("rev", 123).put("body", sent.getJSONObject("body")), sent.getLong("localVersion"))
        assertTrue(db.get(id)!!.getBoolean("done"))
    }
}

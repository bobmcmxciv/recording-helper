package com.bob.recordinghelper.local

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.bob.recordinghelper.LocalAsr
import com.bob.recordinghelper.ModelGate
import com.bob.recordinghelper.KeepAwake
import com.bob.recordinghelper.asr.AudioDecoder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Known fixture input only. Never opens the microphone or enrolls the owner's actual voice. */
class MicrophonePipelineTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    @Test fun privateLibraryRoundTripDeletionAndInvalidSampleProtection() {
        val file = File(ctx.cacheDir, "voice-library-test-${java.util.UUID.randomUUID()}.json")
        fun v(axis: Int) = FloatArray(192) { if (it == axis) 1f else 0f }
        try {
            val library = VoiceLibrary(file)
            assertTrue(library.all().isEmpty())
            library.put("我的声音", true, "a".repeat(64), List(8) { v(0) })
            val other = library.put("测试说话人", false, "a".repeat(64), List(8) { v(1) })
            assertEquals(2, VoiceLibrary(file).all().size)
            try { library.put("我的声音", true, "a".repeat(64), emptyList()); fail() } catch (_: IllegalArgumentException) {}
            assertEquals(2, library.all().size)
            assertEquals(1.0, VoiceMatcher.score(v(0), library.all().first { it.self }.vectors.first()), .00001)
            library.delete(other.id); assertEquals(1, library.all().size)
            library.delete("self"); assertTrue(library.all().isEmpty())
        } finally { file.delete(); File(file.path + ".bak").delete(); File(file.path + ".new").delete() }
    }
    @Test fun recordingRecoveryKeepsAudioAndDiscardsOnlyTemporaryEnrollment() {
        val root = File(ctx.cacheDir, "mic-archive-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val wrapped = object : ContextWrapper(ctx) { override fun getFilesDir() = root }
        try {
            val rec = MicArchive.create(wrapped, "record", ""); val id = rec.getString("id")
            PcmWave(MicArchive.audio(wrapped, id)).use { it.write(ShortArray(16000) { 1000 }, 16000) }
            val sample = MicArchive.create(wrapped, "self", "").getString("id")
            PcmWave(MicArchive.audio(wrapped, sample)).use { it.write(ShortArray(16000) { 1000 }, 16000) }
            MicArchive.recover(wrapped)
            assertTrue(MicArchive.audio(wrapped, id).exists()); assertFalse(MicArchive.audio(wrapped, sample).exists())
            assertEquals("waiting", MicArchive.get(wrapped, id).getString("state"))
            assertEquals(1, MicArchive.recordings(wrapped).size)
        } finally { root.walkBottomUp().forEach { it.delete() } }
    }
    @Test(timeout = 180_000) fun knownFixtureTranscribesInMicrophoneChunksAndRejectsMarginalEnrollment() {
        org.junit.Assume.assumeFalse("Do not disturb an active recording", MicState.busy)
        val fixture = File(ctx.cacheDir, "mic-fixture-${java.util.UUID.randomUUID()}.wav")
        val before = AutoImportSettings.status(ctx).toString()
        val profilesBefore = VoiceLibrary.of(ctx).all().map { it.id }
        try {
            ins.context.assets.open("meeting.wav").use { input -> fixture.outputStream().use { input.copyTo(it) } }
            val pcm = arrayListOf<Float>()
            AudioDecoder.decode(fixture) { x, n -> for (i in 0 until n) pcm.add(x[i]) }
            var text = ""; var windows = 0; var similarity = -1.0
            val started = System.currentTimeMillis()
            withModels {
                LocalAsr.open(ctx, cpuOnly = true, loadSpeakers = false).use { asr ->
                    for (start in pcm.indices step MicrophoneService.CHUNK) {
                        val end = minOf(pcm.size, start + MicrophoneService.CHUNK)
                        text += asr.transcribePcm(FloatArray(end - start) { pcm[start + it] })
                    }
                }
                VoiceEngine(File(LocalAsr.modelDir(ctx), "spk.tflite")).use { voice ->
                    val all = voice.extract(pcm.toFloatArray()); windows = all.vectors.size
                    val centroid = VoiceMatcher.center(all.vectors)
                    ins.sendStatus(0, android.os.Bundle().apply { putString("fixture_quality", JSONObject()
                        .put("windows", windows).put("coherent", VoiceMatcher.coherent(all.vectors))
                        .put("voicedSeconds", all.voicedSeconds).put("similarities", org.json.JSONArray(all.vectors.map { VoiceMatcher.score(it, centroid) }))
                        .put("characters", text.length).put("topicPresent", text.contains("报告")).toString()) })
                    assertTrue("No usable voice windows", windows >= 6)
                    // This old, short ASR fixture has two outlying windows (about .74), so it is not a
                    // suitable enrollment reference. Keep the quality threshold; do not tune it to pass.
                    assertFalse("Marginal enrollment must be rejected even if its overall centroid matches", VoiceMatcher.coherent(all.vectors))
                    val even = all.vectors.filterIndexed { i, _ -> i % 2 == 0 }
                    val odd = all.vectors.filterIndexed { i, _ -> i % 2 != 0 }
                    val match = VoiceMatcher.match(VoiceMatcher.center(odd), mapOf("fixture-speaker" to listOf(VoiceMatcher.center(even))))
                    similarity = match.score; assertEquals("fixture-speaker", match.name)
                    assertTrue(voice.extract(FloatArray(16000 * 5)).vectors.isEmpty())
                }
            }
            assertTrue("Expected topic missing from segmented transcript", text.contains("报告"))
            assertEquals(before, AutoImportSettings.status(ctx).toString())
            assertEquals(profilesBefore, VoiceLibrary.of(ctx).all().map { it.id })
            ins.sendStatus(0, BundleOf.result(JSONObject().put("ok", true).put("seconds", pcm.size / 16000.0).put("elapsedMs", System.currentTimeMillis() - started)
                .put("characters", text.length).put("voiceWindows", windows).put("sameFixtureSimilarity", similarity)
                .put("microphoneOpened", false).put("personalVoiceEnrolled", false).toString()))
        } finally { fixture.delete() }
    }
    private object BundleOf { fun result(value: String) = android.os.Bundle().apply { putString("microphone_fixture", value) } }

    @Test(timeout = 180_000) fun syntheticDifferentVoiceAndHeldOutSentenceComparison() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("syntheticVoice") == "yes")
        org.junit.Assume.assumeFalse(MicState.busy)
        val before = VoiceLibrary.of(ctx).all().map { it.id }
        val dir = File(ctx.getExternalFilesDir(null), "verification-voices")
        fun decode(name: String): FloatArray {
            val pcm = arrayListOf<Float>(); val f = File(dir, name)
            require(f.isFile && f.length() < 2_000_000) { "Explicit synthetic fixture missing" }
            AudioDecoder.decode(f) { x, n -> for (i in 0 until n) pcm.add(x[i]) }
            return pcm.toFloatArray()
        }
        val report = JSONObject().put("syntheticOnly", true).put("personalProfilesUnchanged", true)
        withModels {
            VoiceEngine(File(LocalAsr.modelDir(ctx), "spk.tflite")).use { engine ->
                val a = engine.extract(decode("speaker-a-enroll.wav"))
                val a2 = engine.extract(decode("speaker-a-check.wav"))
                val b = engine.extract(decode("speaker-b-check.wav"))
                val center = VoiceMatcher.center(a.vectors)
                val same = VoiceMatcher.match(VoiceMatcher.center(a2.vectors), mapOf("syntheticA" to listOf(center)))
                val other = VoiceMatcher.match(VoiceMatcher.center(b.vectors), mapOf("syntheticA" to listOf(center)))
                report.put("enrollmentWindows", a.vectors.size).put("voicedSeconds", a.voicedSeconds)
                    .put("enrollmentCoherent", VoiceMatcher.coherent(a.vectors)).put("sameVoiceNewSentence", same.score)
                    .put("differentSyntheticVoice", other.score).put("sameMatched", same.name != null).put("differentRejected", other.name == null)
                ins.sendStatus(0, android.os.Bundle().apply { putString("synthetic_voice_comparison", report.toString()) })
                assertTrue("Synthetic enrollment did not pass quality checks", a.voicedSeconds >= 8 && VoiceMatcher.coherent(a.vectors))
                assertEquals("syntheticA", same.name)
                assertNull("Different synthetic voice should stay unknown", other.name)
            }
        }
        assertEquals(before, VoiceLibrary.of(ctx).all().map { it.id })
    }
    private fun withModels(block: () -> Unit) {
        // Do not change preferences or cancel the owner's existing work for a fixture test.
        org.junit.Assume.assumeFalse("Unlock the phone; do not bypass its lock screen",
            ctx.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked)
        // Starting instrumentation replaces the app process, so opening the launcher *before*
        // am instrument is insufficient. Keep a real foreground Activity during this CPU test.
        ins.uiAutomation.executeShellCommand("am start -W -n ${ctx.packageName}/.MainActivity").use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
        KeepAwake.hold(ctx, "本地语音模型验收（合成音频，不开启麦克风）") { ModelGate.hold(block) }
    }
}

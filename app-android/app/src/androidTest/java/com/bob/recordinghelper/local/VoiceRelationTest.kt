package com.bob.recordinghelper.local

import androidx.test.platform.app.InstrumentationRegistry
import com.bob.recordinghelper.KeepAwake
import com.bob.recordinghelper.LocalAsr
import com.bob.recordinghelper.ModelGate
import com.bob.recordinghelper.asr.AudioDecoder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException

/** Synthetic conversations and isolated cache stores. Never reads private recordings or enrolls a real person. */
class VoiceRelationTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    @Test(timeout = 180_000) fun twoSyntheticConversationsCreateCandidateWithoutMutatingPersonalLibrary() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("syntheticVoice") == "yes")
        org.junit.Assume.assumeFalse(MicState.busy || VoiceRelationState.busy)
        org.junit.Assume.assumeFalse(ctx.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked)
        ins.uiAutomation.executeShellCommand("am start -W -n ${ctx.packageName}/.MainActivity").use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
        val profilesBefore = VoiceLibrary.of(ctx).all().map { it.id }
        val root = File(ctx.cacheDir, "relation-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        fun fixture(name: String): FloatArray {
            val f = File(ctx.getExternalFilesDir(null), "verification-voices/$name")
            require(f.isFile && f.length() < 2_000_000) { "Synthetic fixture not readable" }
            val samples = arrayListOf<Float>()
            AudioDecoder.decode(f) { x, n -> for (i in 0 until n) samples.add(x[i]) }
            return samples.toFloatArray()
        }
        fun conversation(name: String, a: FloatArray, b: FloatArray): File {
            val f = File(root, name)
            PcmWave(f).use { wave ->
                for (voice in listOf(a, b)) {
                    // A complete paragraph supplies enough clean speech after pauses and boundary filtering.
                    val n = minOf(voice.size, 16000 * 20)
                    val pcm = ShortArray(n) { voice[it].toInt().coerceIn(-32768, 32767).toShort() }
                    wave.write(pcm, n)
                }
            }
            return f
        }
        try { KeepAwake.hold(ctx, "合成双人对话关联验收，不开启麦克风") { ModelGate.hold {
            VoiceEngine(File(LocalAsr.modelDir(ctx), "spk.tflite")).use { engine ->
                val a = fixture("speaker-a-enroll.wav"); val a2 = fixture("speaker-a-check.wav")
                val b = fixture("speaker-b-enroll.wav"); val b2 = fixture("speaker-b-check.wav")
                val self = VoiceMatcher.center(engine.extract(a).vectors)
                val first = conversation("first.wav", a, b); val second = conversation("second.wav", a2, b2)
                val d1 = VoiceRelationRunner.decode(first, engine, { true }); val d2 = VoiceRelationRunner.decode(second, engine, { true })
                val r1 = VoiceRelations.analyze(d1.id, d1.windows, self); val r2 = VoiceRelations.analyze(d2.id, d2.windows, self)
                fun dense(d: VoiceRelationRunner.Decoded, f: File): List<VoiceRelations.Window> {
                    val pcm = PcmWave.read(f, 0, PcmWave.count(f).toInt())
                    return d.windows.filter { w ->
                        var audible = 0
                        for (frame in 0 until 150) {
                            var energy = 0.0
                            for (i in w.start.toInt() + frame * 160 until w.start.toInt() + (frame + 1) * 160) energy += pcm[i].toDouble() * pcm[i]
                            if (kotlin.math.sqrt(energy / 160) >= 200) audible++
                        }
                        audible >= 100
                    }
                }
                val filtered1 = dense(d1, first); val filtered2 = dense(d2, second)
                val filteredResult1 = VoiceRelations.analyze(d1.id, filtered1, self)
                val filteredResult2 = VoiceRelations.analyze(d2.id, filtered2, self)
                ins.sendStatus(0, android.os.Bundle().apply { putString("relation_diagnostics", JSONObject()
                    .put("firstAccepted", r1.evidence != null).put("secondAccepted", r2.evidence != null)
                    .put("firstReason", r1.reason).put("secondReason", r2.reason)
                    .put("firstWindows", d1.windows.size).put("secondWindows", d2.windows.size)
                    .put("denseFirst", filtered1.size).put("denseSecond", filtered2.size)
                    .put("denseFirstResult", filteredResult1.reason).put("denseSecondResult", filteredResult2.reason)
                    .put("denseFirstAccepted", filteredResult1.evidence != null).put("denseSecondAccepted", filteredResult2.evidence != null).toString()) })
                assertEquals("Pause-dominated windows reached clustering", d1.windows.size, filtered1.size)
                assertEquals("Pause-dominated windows reached clustering", d2.windows.size, filtered2.size)
                assertNotNull(r1.evidence); assertNotNull(r2.evidence)
                assertNotEquals(d1.id, d2.id)
                val copied = File(root, "renamed.wav"); first.copyTo(copied)
                val duplicate = VoiceRelationRunner.decode(copied, engine, { true })
                assertEquals(d1.id, duplicate.id)
                val evidence = listOf(r1.evidence!!, r2.evidence!!)
                val candidates = VoiceRelations.candidates(evidence + r1.evidence!!)
                assertEquals(1, candidates.size); val c = candidates.single()
                assertEquals(2, c.calls.size); assertTrue(c.ready)
                val store = VoiceRelationStore(File(root, "evidence.json"))
                val report = VoiceRelationStore.Report("a".repeat(64), "test-anchor", listOf(
                    VoiceRelationStore.Entry(d1.id, "合成对话一", "content://synthetic/one", d1.seconds, false, r1.reason, r1.evidence),
                    VoiceRelationStore.Entry(d2.id, "合成对话二", "content://synthetic/two", d2.seconds, false, r2.reason, r2.evidence)))
                store.write(report)
                assertEquals(2, store.read()!!.entries.size)
                assertTrue(VoiceRelations.candidates(store.read()!!.entries.mapNotNull { it.evidence }).single().ready)
                val library = VoiceLibrary(File(root, "isolated-voice-library.json"))
                assertTrue(library.all().isEmpty()) // proposing a candidate never creates a profile
                library.put("合成测试者", false, report.model, c.samples) // explicit test confirmation, isolated file only
                assertEquals(1, library.all().size); assertFalse(library.all().single().self)
                try { VoiceRelationRunner.decode(first, engine, { false }); fail("Cancellation ignored") } catch (_: CancellationException) {}
                assertEquals(2, store.read()!!.entries.size)
                store.clear(); assertNull(store.read())
                ins.sendStatus(0, android.os.Bundle().apply { putString("voice_relations", JSONObject().put("ok", true)
                    .put("independentConversations", 2).put("candidates", candidates.size).put("minimumSimilarity", c.minimumSimilarity)
                    .put("duplicateDidNotAddVote", true).put("cancelPreservedEvidence", true).put("syntheticOnly", true)
                    .put("microphoneOpened", false).put("personalLibraryChanged", false).toString()) })
            }
        } } } finally { root.walkBottomUp().forEach { it.delete() } }
        assertEquals(profilesBefore, VoiceLibrary.of(ctx).all().map { it.id })
    }
}

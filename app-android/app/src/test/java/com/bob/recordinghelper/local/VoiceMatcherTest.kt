package com.bob.recordinghelper.local

import org.junit.Assert.*
import org.junit.Test

class VoiceMatcherTest {
    private fun vector(axis: Int) = FloatArray(192) { if (it == axis) 1f else 0f }
    @Test fun matchesExplicitSelfTemplate() {
        val m = VoiceMatcher.match(vector(0), mapOf("我的声音" to listOf(vector(0)), "另一人" to listOf(vector(1))))
        assertEquals("我的声音", m.name); assertEquals(1.0, m.score, .00001)
    }
    @Test fun differentVoiceStaysUnknown() {
        assertNull(VoiceMatcher.match(vector(1), mapOf("我的声音" to listOf(vector(0)))).name)
    }
    @Test fun ambiguousSimilarProfilesDoNotForceAnIdentity() {
        assertNull(VoiceMatcher.match(vector(0), mapOf("甲" to listOf(vector(0)), "乙" to listOf(vector(0)))).name)
    }
    @Test fun rejectsInvalidVectorsAndEmptyLibrary() {
        assertFalse(VoiceMatcher.valid(FloatArray(192)))
        assertFalse(VoiceMatcher.valid(FloatArray(192) { Float.NaN }))
        assertFalse(VoiceMatcher.valid(FloatArray(191) { 1f }))
        assertNull(VoiceMatcher.match(vector(0), emptyMap()).name)
        assertNull(VoiceMatcher.match(vector(0), mapOf("bad" to emptyList())).name)
    }
    @Test fun shortOrMixedEnrollmentIsRejected() {
        assertFalse(VoiceMatcher.coherent(List(5) { vector(0) }))
        assertFalse(VoiceMatcher.coherent(List(9) { vector(it % 3) }))
        assertFalse(VoiceMatcher.coherent(List(8) { vector(it % 2) }))
        assertTrue(VoiceMatcher.coherent(List(8) { vector(0) }))
    }
    @Test fun amplitudeDoesNotChangeSimilarity() {
        val v = vector(0); v[0] = 900f
        assertEquals(1.0, VoiceMatcher.score(v, vector(0)), .00001)
    }
}

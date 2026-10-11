package com.bob.recordinghelper.local

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class VoiceRelationsTest {
    private fun v(i: Int) = FloatArray(192) { if (it == i) 1f else 0f }
    private fun part(speaker: Int, start: Long, n: Int = 12) = List(n) { VoiceRelations.Window(start + it * 12000L, v(speaker)) }
    private fun call(id: String, other: Int = 1) = VoiceRelations.analyze(id, part(0, 0) + part(other, 200000), v(0)).evidence!!
    @Test fun extractsOtherOnlyWhenSelfIsReliablyPresent() {
        val e = call("one")
        assertEquals(1.0, e.selfScore, .00001); assertEquals(0.0, e.otherSelfScore, .00001)
        assertEquals(9.75, e.otherSeconds, .00001); assertEquals(12.5, e.previewStart, .00001)
        assertEquals(1.0, VoiceMatcher.score(v(1), e.other), .00001)
    }
    @Test fun twoCallsFindRecurringOtherWhileDifferentOthersStaySeparate() {
        val all = VoiceRelations.candidates(listOf(call("a"), call("b"), call("c", 2)))
        assertEquals(2, all.size); assertTrue(all[0].ready); assertEquals(2, all[0].calls.size)
        assertFalse(all[1].ready); assertEquals(1, all[1].calls.size)
    }
    @Test fun neitherSpeakerIsSelfDoesNotUseElimination() {
        assertNull(VoiceRelations.analyze("x", part(1, 0) + part(2, 200000), v(0)).evidence)
    }
    @Test fun singleSpeakerIsNotForcedIntoTwo() {
        assertNull(VoiceRelations.analyze("x", part(0, 0, 36), v(0)).evidence)
    }
    @Test fun thirdSpeakerNotAbsorbedEvenWhenShort() {
        assertNull(VoiceRelations.analyze("x", part(0, 0) + part(1, 200000) + part(2, 400000), v(0)).evidence)
        assertNull(VoiceRelations.analyze("x", part(0, 0) + part(1, 200000) + part(2, 400000, 3), v(0)).evidence)
    }
    @Test fun shortOtherDoesNotSupplyEnoughEvidence() {
        assertNull(VoiceRelations.analyze("x", part(0, 0) + part(1, 200000, 5), v(0)).evidence)
    }
    @Test fun nearDuplicateKnownProfileMakesSelfAmbiguous() {
        assertNull(VoiceRelations.analyze("x", part(0, 0) + part(1, 200000), v(0), mapOf("lookalike" to listOf(v(0)))).evidence)
    }
    @Test fun duplicateRecordingDoesNotAddIndependentVotes() {
        val c = VoiceRelations.candidates(listOf(call("same"), call("same"))).single()
        assertEquals(1, c.calls.size); assertFalse(c.ready)
    }
    @Test fun overlappingWindowsAreNotDoubleCounted() {
        assertEquals(6.75, VoiceRelations.seconds(part(0, 0, 8)), .00001)
        assertEquals(6.75, VoiceRelations.seconds(part(0, 0, 8) + part(0, 0, 8)), .00001)
    }
    @Test fun completeLinkDoesNotTransitivelyMergeLookalikes() {
        fun angle(deg: Double) = FloatArray(192).also { it[0] = cos(Math.toRadians(deg)).toFloat(); it[1] = sin(Math.toRadians(deg)).toFloat() }
        val groups = VoiceRelations.completeGroups(listOf(angle(0.0), angle(30.0), angle(60.0)), .78)
        assertEquals(2, groups.size); assertTrue(groups.all { it.size < 3 })
    }
    @Test fun invalidAndTooFewWindowsDoNotCreateAProfile() {
        assertNull(VoiceRelations.analyze("x", emptyList(), v(0)).evidence)
        assertNull(VoiceRelations.analyze("x", List(50) { VoiceRelations.Window(it * 12000L, FloatArray(192) { Float.NaN }) }, v(0)).evidence)
    }
    @Test fun twoIndependentRecurringOthersAreBothCandidatesNotOnePerson() {
        val cs = VoiceRelations.candidates(listOf(call("a"), call("b"), call("c", 2), call("d", 2)))
        assertEquals(2, cs.size); assertTrue(cs.all { it.ready && !it.ambiguous })
    }
    private fun entry(id: String, uri: String) = VoiceRelationStore.Entry(id, "synthetic", uri, 0.0, false, "test", null)
    @Test fun rewrittenUriReplacesOldEvidenceInsteadOfAddingAVote() {
        val result = VoiceRelationStore.mergeEntries(listOf(entry("old", "content://one"), entry("other", "content://two")), entry("new", "content://one"))
        assertEquals(listOf("other", "new"), result.map { it.id })
    }
    @Test fun failedReadPreservesOldEvidenceAndSuccessfulRetryClearsFailure() {
        val old = listOf(entry("old", "content://one"))
        val failed = VoiceRelationStore.mergeEntries(old, entry("failed-one", "content://one"))
        assertEquals(2, failed.size)
        val fixed = VoiceRelationStore.mergeEntries(failed, entry("new", "content://one"))
        assertEquals(listOf("new"), fixed.map { it.id }); assertEquals("old", old.single().id)
    }
    @Test fun duplicatedPcmUpdatesReferenceAndLimitDoesNotMutatePriorEntries() {
        val old = listOf(entry("same", "content://original"))
        assertEquals("content://copy", VoiceRelationStore.mergeEntries(old, entry("same", "content://copy")).single().uri)
        val full = List(40) { entry("id-$it", "content://$it") }
        try { VoiceRelationStore.mergeEntries(full, entry("41", "content://41")); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(40, full.size)
    }
}

package com.bob.recordinghelper.asr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks against tools/generate_synthetic_reference.py (spk_ref.json):
 * CAM++ front-end on deterministic synthetic signals, and known separated synthetic vector clusters.
 */
class SpeakerTrackerTest {
    private val ref = JSONObject(javaClass.classLoader!!.getResource("spk_ref.json")!!.readText())

    @Test
    fun fbankMatchesPython() {
        val clips = ref.getJSONArray("clips")
        for (c in 0 until clips.length()) {
            val clip = clips.getJSONObject(c)
            val a = clip.getJSONArray("pcm16")
            val pcm = FloatArray(a.length()) { a.getInt(it).toFloat() }
            val fb = Frontend.kaldiFbank(pcm, pcm.size, Frontend.POVEY)
            assertEquals(clip.getInt("frames"), fb.size)
            val mean = FloatArray(80)
            for (row in fb) for (m in 0 until 80) mean[m] += row[m] / fb.size
            val rows = clip.getJSONArray("rows")
            var worst = 0.0
            for ((k, t) in listOf(0, fb.size / 2, fb.size - 1).withIndex()) {
                val want = rows.getJSONArray(k)
                for (m in 0 until 80) worst = maxOf(worst, Math.abs(fb[t][m] - mean[m] - want.getDouble(m)))
            }
            println("clip $c frames ${fb.size} max |kotlin - python| = $worst")
            assertTrue("fbank+CMN differs by $worst", worst < 2e-3)
        }
    }

    @Test
    fun averageLinkageMatchesScipy() {
        val p = ref.getJSONArray("points")
        val pts = Array(p.length()) { i -> val r = p.getJSONArray(i); FloatArray(r.length()) { r.getDouble(it).toFloat() } }
        val got = SpeakerTracker.averageLinkage(pts, 0.65)
        val want = ref.getJSONArray("groups").let { g -> IntArray(g.length()) { g.getInt(it) } }
        // same partition (ids may differ)
        val map = HashMap<Int, Int>()
        for (i in got.indices) {
            val prev = map.putIfAbsent(got[i], want[i])
            assertTrue("point $i: kotlin group ${got[i]} vs scipy ${want[i]}", prev == null || prev == want[i])
        }
        assertEquals(want.toSet().size, got.toSet().size)
    }
}

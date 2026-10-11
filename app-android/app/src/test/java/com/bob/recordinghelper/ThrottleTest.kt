package com.bob.recordinghelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThrottleTest {
    @Test
    fun atMostOncePerInterval() {
        var t = 0L
        val th = Throttle(1000) { t }
        assertTrue(th.allow())
        t = 999; assertFalse(th.allow())
        t = 1000; assertTrue(th.allow())
        t = 1500; assertFalse(th.allow())
        t = 2000; assertTrue(th.allow())
    }

    @Test
    fun segmentRateDropsBelowTheSystemLimit() {
        // progress callbacks every 160 ms (~6 per second, the rate the system shed) for one minute
        var t = 0L
        val th = Throttle(1000) { t }
        val posted = ArrayList<Long>()
        while (t < 60_000) { if (th.allow()) posted.add(t); t += 160 }
        // every 7th callback passes (7 x 160 ms = 1120 ms >= 1 s): 54 posts in the minute instead of ~375
        assertTrue(posted.zipWithNext { a, b -> b - a }.all { it >= 1000 })
        assertEquals(54, posted.size)
    }

    @Test
    fun forceAlwaysPassesAndRestartsTheInterval() {
        var t = 0L
        val th = Throttle(1000) { t }
        assertTrue(th.allow())
        t = 100; assertTrue(th.allow(force = true))
        t = 900; assertFalse(th.allow())
        t = 1100; assertTrue(th.allow())
    }
}

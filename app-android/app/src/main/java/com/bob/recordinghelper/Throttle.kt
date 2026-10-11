package com.bob.recordinghelper

/**
 * 简单限流：两次放行之间至少隔 [intervalMs]。[allow] 传 force=true 时总是放行（也算一次放行，重新计时）。
 */
class Throttle(private val intervalMs: Long, private val now: () -> Long = System::currentTimeMillis) {
    private var last = 0L
    private var started = false

    @Synchronized
    fun allow(force: Boolean = false): Boolean {
        val t = now()
        if (!force && started && t - last < intervalMs) return false
        last = t
        started = true
        return true
    }
}

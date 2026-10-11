package com.bob.recordinghelper

import java.util.concurrent.Semaphore

/**
 * Lets only one set of large models live in the process at a time: the ASR models (SenseVoice + CAM++) or the local
 * LLM. HyperOS force-stops an app above ~6 GB PSS, and a 9B LLM next to the ASR models goes over it.
 */
object ModelGate {
    private val gate = Semaphore(1, true)

    fun interactive(keepWaiting: () -> Boolean, block: () -> Unit): Boolean {
        while (keepWaiting()) {
            if (!gate.tryAcquire(250, java.util.concurrent.TimeUnit.MILLISECONDS)) continue
            try { if (keepWaiting()) block() } finally { gate.release() }
            return true
        }
        return false
    }

    fun <T> hold(block: () -> T): T {
        gate.acquire()
        try {
            return block()
        } finally {
            gate.release()
        }
    }
}

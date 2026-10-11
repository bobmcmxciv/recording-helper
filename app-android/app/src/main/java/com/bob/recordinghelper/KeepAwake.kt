package com.bob.recordinghelper

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * 长任务（本地转写、试转、润色）期间不让手机把 App 冻住：持有部分唤醒锁（CPU 不睡），并在 [BusyService]
 * 这个前台服务里挂一条通知。熄屏后 HyperOS / ColorOS 会冻结没有前台服务、没有唤醒锁的进程（实测平板熄屏几秒内
 * 转写线程就停了，加电池白名单也一样）。可以嵌套：最后一个任务结束才释放。
 */
object KeepAwake {
    private var count = 0
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile var text = ""
        private set

    fun <T> hold(ctx: Context, what: String, block: () -> T): T {
        acquire(ctx, what)
        try {
            return block()
        } finally {
            release(ctx)
        }
    }

    @Synchronized
    fun acquire(ctx: Context, what: String) {
        val app = ctx.applicationContext
        text = what
        if (count++ == 0) {
            val pm = app.getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "recordinghelper:busy").apply {
                setReferenceCounted(false)
                acquire(6 * 3600_000L) // safety net: released explicitly long before this
            }
        }
        BusyService.reconcile(app)
    }

    @Synchronized
    fun release(ctx: Context) {
        if (count == 0) return
        if (--count == 0) {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            BusyService.reconcile(ctx.applicationContext)
        }
    }

    @Synchronized fun active(): Boolean = count > 0

    /** Whether the system lets this app ignore battery optimisations (needed for reliable work with the screen off). */
    fun unrestricted(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
}

/** Foreground service that only carries the "busy" notification while [KeepAwake] holds work. */
class BusyService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always promote a requested service before stopping it, even if a short job
        // finished before Android delivered this callback.
        promote()
        current = this
        startRequested = false
        stopIfIdle()
        return START_NOT_STICKY
    }

    private fun promote() {
        val n: Notification = NotificationCompat.Builder(this, App.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("录音助手")
            .setContentText(KeepAwake.text.ifEmpty { "后台处理中" })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 1, Intent(this, TranscribeLiveActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(BUSY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(BUSY_ID, n)
    }

    private fun stopIfIdle() {
        if (KeepAwake.active()) return
        current = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ dataSync quota: stop promptly rather than causing an ANR.
        if (current === this) current = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val BUSY_ID = 4203
        private val main = Handler(Looper.getMainLooper())
        // Accessed only on the main thread, including all Service callbacks.
        private var current: BusyService? = null
        private var startRequested = false

        fun reconcile(ctx: Context) {
            main.post {
                val service = current
                if (!KeepAwake.active()) {
                    // Never cancel a pending startForegroundService request. Its
                    // onStartCommand will promote and immediately stop if idle.
                    service?.stopIfIdle()
                } else if (service != null) {
                    service.promote()
                } else if (!startRequested) {
                    try {
                        ctx.startForegroundService(Intent(ctx, BusyService::class.java))
                        startRequested = true
                    } catch (_: Exception) {
                        // Background launch restrictions can refuse the request.
                        // WorkManager foreground mode / the wake lock still apply.
                        startRequested = false
                    }
                }
            }
        }
    }
}

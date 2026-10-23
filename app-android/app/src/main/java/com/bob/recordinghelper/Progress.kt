package com.bob.recordinghelper

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat

// 上传/扫描进度通知（前台+完成两类），集中管理通知 id
object Progress {
    const val ONGOING_ID = 4201   // 进行中（前台通知，会被不断更新）
    const val DONE_ID = 4202      // 完成后的一次性提示

    fun ongoing(ctx: Context, title: String, text: String, max: Int, cur: Int, indeterminate: Boolean = false): Notification {
        return NotificationCompat.Builder(ctx, App.CHANNEL)
            .setSmallIcon(R.drawable.ic_auto)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(max, cur, indeterminate)
            .apply {
                // 点通知直接查看转写与大模型总结推理过程
                setContentIntent(android.app.PendingIntent.getActivity(ctx, 0,
                    android.content.Intent(ctx, TranscribeLiveActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
            }
            .build()
    }

    // 系统对一个应用的通知更新超过约每秒 5 次就直接丢弃（HyperOS 日志 "Package enqueue rate … Shedding"），
    // 扫描结束的汇总也会跟着被丢；逐段转写的进度原来每秒能更新五六次，所以进行中的进度最多每秒更新一次
    private val ongoingThrottle = Throttle(1000)

    /** 更新进行中的进度通知（最多每秒一次，[force] 的总是更新） */
    fun notifyOngoing(ctx: Context, title: String, text: String, max: Int, cur: Int, force: Boolean = false) {
        if (!ongoingThrottle.allow(force)) return
        try {
            androidx.core.app.NotificationManagerCompat.from(ctx).notify(ONGOING_ID, ongoing(ctx, title, text, max, cur))
        } catch (_: Exception) { }
    }

    fun done(ctx: Context, text: String) {
        val n = NotificationCompat.Builder(ctx, App.CHANNEL)
            .setSmallIcon(R.drawable.ic_check_circle)
            .setContentTitle("录音助手")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        try {
            androidx.core.app.NotificationManagerCompat.from(ctx).notify(DONE_ID, n)
        } catch (_: Exception) { }
    }

    fun human(bytes: Long): String =
        if (bytes >= 1024 * 1024) String.format("%.1fMB", bytes / 1024.0 / 1024.0)
        else String.format("%dKB", bytes / 1024)
}

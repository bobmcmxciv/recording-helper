package com.bob.recordinghelper.local

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.work.WorkManager
import com.bob.recordinghelper.ModelGate
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object VoiceRelationState {
    data class Snapshot(val busy: Boolean = false, val message: String = "选择由本人参与、已获同意用于声纹分析的对话录音")
    @Volatile var snapshot = Snapshot(); private set
    val busy get() = snapshot.busy
    @Synchronized fun set(busy: Boolean, message: String) { snapshot = Snapshot(busy, message) }
}

/** Explicit selection only. No microphone, scheduled scan, contacts or network operations. */
class VoiceRelationService : Service() {
    private val cancel = AtomicBoolean(false)
    private var wake: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { cancel.set(true); if (!VoiceRelationState.busy) stopSelf(); return START_NOT_STICKY }
        if (VoiceRelationState.busy) return START_NOT_STICKY
        val uris = intent?.getStringArrayListExtra("uris").orEmpty().distinct().map(Uri::parse)
        try {
            require(uris.size in 1..8 && !MicState.busy) { "请先停止录音，再选择 1 到 8 段对话" }
            cancel.set(false)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "本地跨录音声纹分析", NotificationManager.IMPORTANCE_LOW))
            val n = notification("等待模型资源；只分析你刚选择的录音")
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(NOTICE, n)
            VoiceRelationState.set(true, "等待本地模型资源，当前正在转写的文件结束后继续；可随时取消")
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "recordinghelper:relations").apply {
                setReferenceCounted(false); acquire(45 * 60_000L)
            }
            WorkManager.getInstance(this).cancelUniqueWork("local-analysis")
            WorkManager.getInstance(this).cancelUniqueWork("local-analysis-now")
            thread(name = "rh-voice-relations") {
                var message = "已取消，之前的候选与声纹库保持不变"
                try {
                    val alive = { !cancel.get() && !MicState.busy }
                    ModelGate.interactive(alive) {
                        val report = VoiceRelationRunner.run(this, uris, alive) { text ->
                            VoiceRelationState.set(true, text)
                            nm.notify(NOTICE, notification(text))
                        }
                        if (!alive()) throw CancellationException()
                        VoiceRelationStore.of(this).write(report)
                        val candidates = VoiceRelations.candidates(report.entries.mapNotNull { it.evidence })
                        message = "已保存 ${report.entries.size} 段依据 · ${candidates.size} 个候选 · ${candidates.count { it.ready }} 个可人工确认；未自动写入声纹库"
                    }
                } catch (_: CancellationException) { message = "已取消或为麦克风让出资源，之前的候选与声纹库保持不变" }
                catch (e: Exception) { message = e.message?.take(180) ?: "分析失败，之前的候选与声纹库保持不变" }
                finally {
                    val result = message
                    Handler(Looper.getMainLooper()).post {
                        VoiceRelationState.set(false, result)
                        if (wake?.isHeld == true) wake?.release()
                        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                        ImportWorker.schedule(this); RecordingQueueWorker.schedule(this); AnalysisWorker.schedule(this)
                    }
                }
            }
        } catch (e: Exception) {
            VoiceRelationState.set(false, e.message ?: "无法开始分析")
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 820, Intent(this, VoiceRelationActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 821, Intent(this, VoiceRelationService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(com.bob.recordinghelper.R.drawable.ic_mic).setContentTitle("本地对话声纹关联")
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(com.bob.recordinghelper.R.drawable.ic_close, "取消", stop).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { cancel.set(true); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { cancel.set(true); if (wake?.isHeld == true) wake?.release(); super.onDestroy() }
    companion object { const val STOP = "rh.relations.STOP"; private const val CHANNEL = "voice-relations"; private const val NOTICE = 820 }
}

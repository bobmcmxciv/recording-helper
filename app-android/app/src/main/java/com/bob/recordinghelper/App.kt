package com.bob.recordinghelper

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val localPrefs = com.bob.recordinghelper.local.LocalSettings.prefs(this)
        if (!localPrefs.getBoolean("initialized", false)) {
            // Upgrading does not silently reprocess the user's entire historical archive.
            Prefs.save(this, Prefs.baseUrl(this), Prefs.token(this), false, Prefs.scanDirs(this).joinToString("\n"), Prefs.scanDays(this).toString())
            localPrefs.edit().putBoolean("initialized", true).apply()
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "本地处理与同步", NotificationManager.IMPORTANCE_LOW)
        )
        val installed = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        if (localPrefs.getLong("asrRecoveryInstall", 0) != installed) {
            // Retry engine-unavailable references once after an APK upgrade, not on every launch.
            com.bob.recordinghelper.local.RecordingQueue.of(this).retryUnavailable()
            localPrefs.edit().putLong("asrRecoveryInstall", installed).commit()
        }
        if (Prefs.autoScan(this)) scheduleScan(this) else cancelScan(this)
        // 1.x network jobs must not wake up after an offline-first upgrade.
        WorkManager.getInstance(this).cancelUniqueWork("polish")
        com.bob.recordinghelper.local.AnalysisWorker.schedule(this)
        com.bob.recordinghelper.local.ImportWorker.schedule(this)
        if (Prefs.autoScan(this)) com.bob.recordinghelper.local.RecordingQueueWorker.schedule(this)
        com.bob.recordinghelper.local.SyncWorker.schedule(this)
    }

    companion object {
        const val CHANNEL = "upload"

        fun scheduleScan(app: Application) {
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                "auto-scan",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AutoScanWorker>(15, TimeUnit.MINUTES).build()
            )
        }

        fun cancelScan(app: Application) {
            WorkManager.getInstance(app).cancelUniqueWork("auto-scan")
        }
    }
}

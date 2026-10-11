package com.bob.recordinghelper.local

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Environment
import com.bob.recordinghelper.App
import com.bob.recordinghelper.Prefs
import org.json.JSONObject
import java.io.File

/** Explicit opt-in. Never enables network sync or changes server credentials. */
object AutoImportSettings {
    fun scheduledStatus(ctx: Context): JSONObject {
        val jobs = androidx.work.WorkManager.getInstance(ctx).getWorkInfosForUniqueWork("auto-scan")
            .get(15, java.util.concurrent.TimeUnit.SECONDS).filter { !it.state.isFinished }
        return status(ctx).put("scheduledJobs", jobs.size).put("workStates", jobs.joinToString { it.state.name })
    }

    fun hasAccess(ctx: Context): Boolean {
        val grants = ctx.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        if (LocalSettings.treeUris(ctx).any { it in grants }) return true
        return hasFileAccess(ctx)
    }
    fun hasFileAccess(ctx: Context): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun configure(ctx: Context, enabled: Boolean, includeExisting: Boolean = false) {
        if (enabled) enable(ctx, if (includeExisting) "all" else "new") else pause(ctx, true)
    }

    fun enable(ctx: Context, scope: String, days: Int = 0) {
        require(hasAccess(ctx)) { "请先授权录音目录或文件访问权限" }
        ImportScope.select(scope, days, System.currentTimeMillis()) // Validate before touching the queue.
        if (scope != Prefs.scanScope(ctx) || (scope == "recent" && days != Prefs.scanDays(ctx)))
            RecordingQueue.of(ctx).resetUnstartedAutomatic()
        Prefs.setScanScope(ctx, scope, days)
        App.scheduleScan(ctx.applicationContext as Application)
        requestScan(ctx)
    }
    fun disable(ctx: Context) {
        Prefs.disableAutoScan(ctx)
        pause(ctx, true)
    }
    fun pause(ctx: Context, paused: Boolean) {
        if (!paused) require(hasAccess(ctx)) { "请重新授权录音目录" }
        Prefs.pauseAutoScan(ctx, paused)
        if (paused) {
            App.cancelScan(ctx.applicationContext as Application)
            androidx.work.WorkManager.getInstance(ctx).cancelUniqueWork("manual-scan")
        } else {
            App.scheduleScan(ctx.applicationContext as Application)
            requestScan(ctx); RecordingQueueWorker.schedule(ctx)
        }
    }

    fun requestScan(ctx: Context, manual: Boolean = false) {
        if (!Prefs.autoScan(ctx) && !manual) return
        androidx.work.WorkManager.getInstance(ctx).enqueueUniqueWork("manual-scan", androidx.work.ExistingWorkPolicy.KEEP,
            androidx.work.OneTimeWorkRequestBuilder<com.bob.recordinghelper.AutoScanWorker>()
                .setInputData(androidx.work.workDataOf("manual" to manual)).build())
    }

    fun scopeLabel(ctx: Context): String = when (Prefs.scanScope(ctx)) {
        "new" -> "仅开启后新增录音"
        "recent" -> "最近 ${Prefs.scanDays(ctx)} 天及新增录音"
        "all" -> "全部已有及新增录音"
        "off" -> "暂不开启，仅手动导入 / 分享"
        else -> "尚未选择处理范围"
    }

    /** Safe device diagnostics: no recording filenames, transcripts, server URL, or credentials. */
    fun status(ctx: Context): JSONObject = JSONObject()
        .put("enabled", Prefs.autoScan(ctx))
        .put("since", Prefs.scanSince(ctx))
        .put("days", Prefs.scanDays(ctx))
        .put("choice", Prefs.scanScope(ctx) ?: JSONObject.NULL)
        .put("hasAccess", hasAccess(ctx))
        .put("authorizedTrees", LocalSettings.treeUris(ctx).size)
        .put("readableDirectoryCount", Prefs.scanDirs(ctx).count { relative ->
            val dir = if (relative.startsWith('/')) File(relative) else File(Environment.getExternalStorageDirectory(), relative)
            dir.isDirectory && dir.canRead()
        })
        .put("syncEnabled", LocalSettings.sync(ctx))
}

object ScanWindow {
    fun cutoff(now: Long, days: Int, since: Long): Long = maxOf(since,
        if (days > 0) now - days.toLong() * 86_400_000L else 0L)

    fun eligible(modified: Long, size: Long, now: Long, cutoff: Long): Boolean =
        size > 0L && modified <= now - 60_000L && (cutoff <= 0L || modified >= cutoff)
}

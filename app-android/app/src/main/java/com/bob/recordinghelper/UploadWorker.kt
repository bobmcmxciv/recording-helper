package com.bob.recordinghelper

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Legacy WorkManager type, deliberately never uploads in 2.0. Staged originals are left intact. */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = Result.success()
    companion object { const val KEY_FILES = "files" }
}

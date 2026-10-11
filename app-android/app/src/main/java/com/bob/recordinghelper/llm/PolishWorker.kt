package com.bob.recordinghelper.llm

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Legacy WorkManager type: old queue files remain, but no background network request is allowed. */
class PolishWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = Result.success()
    companion object { fun schedule(ctx: Context) = Unit }
}

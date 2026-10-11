package com.bob.recordinghelper.ui

import android.content.Context
import android.net.Uri
import com.bob.recordinghelper.local.LocalImport

object Imports {
    val AUDIO_TYPES = arrayOf("audio/*", "video/mp4", "application/octet-stream")

    /** Copies every picked file into private storage and queues it; returns a one-line result. */
    fun audio(ctx: Context, uris: List<Uri>): String {
        var ok = 0
        var failed: String? = null
        for (uri in uris) try { LocalImport.audio(ctx, uri); ok++ } catch (e: Exception) { failed = e.message }
        return when {
            ok == 0 -> "导入失败：${failed ?: "无法读取文件"}"
            failed != null -> "已保存 $ok 个文件，另有文件失败：$failed"
            else -> "已保存 $ok 个文件，将在本机离线转写"
        }
    }
}

package com.bob.recordinghelper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import com.bob.recordinghelper.local.LocalImport

/** No token check or remote fallback: all accepted shares are persisted locally. */
class ShareActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { listOf(it) } ?: emptyList()
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            else -> emptyList()
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        Thread {
            val message = try {
                if (uris.isNotEmpty()) uris.forEach { LocalImport.audio(this, it) }
                else if (!text.isNullOrBlank()) LocalImport.text(this, intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "分享文字", text)
                else error("分享内容为空")
                "已保存在手机，等待离线处理"
            } catch (e: Exception) { "导入失败：${e.message}" }
            runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); finish() }
        }.start()
    }
}

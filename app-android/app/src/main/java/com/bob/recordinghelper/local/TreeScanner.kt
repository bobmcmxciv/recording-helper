package com.bob.recordinghelper.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.bob.recordinghelper.AutoScanWorker
import com.bob.recordinghelper.Prefs

/** Persisted SAF grants support scoped storage without requesting all-files access. */
object TreeScanner {
    fun scan(ctx: Context) {
        for (raw in LocalSettings.treeUris(ctx)) {
            try {
                val tree = Uri.parse(raw)
                visit(ctx, tree, DocumentsContract.getTreeDocumentId(tree), 0)
            } catch (e: Exception) {
                LocalSettings.prefs(ctx).edit().putString("scanStatus", "目录不可读，请重新授权：${e.message}").apply()
            }
        }
    }
    private fun visit(ctx: Context, tree: Uri, parent: String, depth: Int) {
        if (depth > 5) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val columns = arrayOf("document_id", "_display_name", "mime_type", "_size", "last_modified")
        ctx.contentResolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0); val name = c.getString(1).orEmpty()
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) { visit(ctx, tree, id, depth + 1); continue }
                val size = c.getLong(3); val modified = c.getLong(4)
                if (name.substringAfterLast('.', "").lowercase() !in AutoScanWorker.AUDIO_EXTS || size <= 0) continue
                val now = System.currentTimeMillis()
                val cutoff = ScanWindow.cutoff(now, Prefs.scanDays(ctx), Prefs.scanSince(ctx))
                // Unknown modification times cannot safely be treated as newly-created recordings.
                if (!ScanWindow.eligible(modified, size, now, cutoff)) continue
                val key = "saf:$id|$size|$modified"
                if (Prefs.ledgerContains(ctx, key)) continue
                LocalImport.audio(ctx, DocumentsContract.buildDocumentUriUsingTree(tree, id))
                Prefs.ledgerAdd(ctx, key) // durable import copy is now owned by ImportWorker
            }
        }
    }
}

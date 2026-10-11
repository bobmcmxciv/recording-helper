package com.bob.recordinghelper.local

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.bob.recordinghelper.AutoScanWorker
import com.bob.recordinghelper.Prefs
import java.io.File

data class RecordingSource(val source: String, val key: String, val title: String, val bytes: Long, val modified: Long)
data class SourceInventory(val files: List<RecordingSource>, val directCount: Int, val treeCount: Int,
    val errors: Int, val truncated: Boolean)

/** Merge authorized trees AND readable recorder paths. An empty tree must not hide real recordings. */
object RecordingSources {
    const val LIMIT = 30_000
    fun fileKey(path: String, bytes: Long, modified: Long) = "$path|$bytes|$modified"
    fun aliases(path: String, bytes: Long, modified: Long, primary: String): List<String> {
        val paths = if (path.startsWith(primary.trimEnd('/') + "/")) {
            val suffix = path.removePrefix(primary.trimEnd('/'))
            listOf(path, "/sdcard$suffix", "/storage/self/primary$suffix")
        } else listOf(path)
        return paths.distinct().map { fileKey(it, bytes, modified) }
    }
    fun savedRecording(ctx: Context, item: RecordingSource): org.json.JSONObject? {
        val path = item.key.removeSuffix("|${item.bytes}|${item.modified}")
        val keys = aliases(path, item.bytes, item.modified, Environment.getExternalStorageDirectory().canonicalPath)
        val owner = LocalSettings.device(ctx)
        return keys.firstNotNullOfOrNull { LocalStore.of(ctx).get("rec-" + LocalStore.hash("$owner:$it")) }
    }
    fun file(file: File): RecordingSource {
        val path = file.canonicalPath
        return RecordingSource(path, fileKey(path, file.length(), file.lastModified()), file.name, file.length(), file.lastModified())
    }
    fun candidates(ctx: Context): List<File> = (Prefs.scanDirs(ctx) + LocalSettings.paths.values.flatten()).distinct().map {
        if (it.startsWith('/')) File(it) else File(Environment.getExternalStorageDirectory(), it)
    }.distinctBy { it.absolutePath }

    fun collect(ctx: Context, stopped: () -> Boolean = { false }): SourceInventory {
        val found = linkedMapOf<String, RecordingSource>()
        val visited = hashSetOf<String>()
        var errors = 0; var treeCount = 0; var truncated = false
        fun full(): Boolean { if (found.size >= LIMIT) truncated = true; return truncated || stopped() }
        fun visit(dir: File, depth: Int) {
            if (depth > 5 || full() || !visited.add(dir.canonicalPath)) return
            val entries = dir.listFiles() ?: run { errors++; return }
            for (f in entries) {
                if (full()) break
                if (f.isDirectory) visit(f, depth + 1)
                else if (f.extension.lowercase() in AutoScanWorker.AUDIO_EXTS && f.isFile && f.length() > 0) {
                    val item = file(f); found.putIfAbsent(item.source, item)
                }
            }
        }
        if (AutoImportSettings.hasFileAccess(ctx)) for (dir in candidates(ctx)) {
            try { if (dir.isDirectory) visit(dir, 0) } catch (_: Exception) { errors++ }
        }
        val directCount = found.size
        val documents = hashSetOf<String>()
        fun visitTree(tree: Uri, parent: String, depth: Int) {
            if (depth > 5 || full() || !documents.add("${tree.authority}:$parent")) return
            try {
                val cursor = ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent),
                    arrayOf("document_id", "_display_name", "mime_type", "_size", "last_modified"), null, null, null)
                    ?: run { errors++; return }
                cursor.use { c -> while (c.moveToNext() && !full()) {
                    val id = c.getString(0); val name = c.getString(1).orEmpty()
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) { visitTree(tree, id, depth + 1); continue }
                    val bytes = c.getLong(3); val modified = c.getLong(4)
                    if (name.substringAfterLast('.', "").lowercase() !in AutoScanWorker.AUDIO_EXTS || bytes <= 0) continue
                    treeCount++
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    // The same primary-storage file selected via SAF and scanned by path has one identity.
                    val path = if (tree.authority == "com.android.externalstorage.documents" && id.startsWith("primary:")) {
                        File(Environment.getExternalStorageDirectory(), id.substringAfter(':')).canonicalPath
                    } else null
                    val identity = path ?: uri.toString()
                    found.putIfAbsent(identity, RecordingSource(uri.toString(),
                        fileKey(path ?: "saf:$id", bytes, modified), name, bytes, modified))
                } }
            } catch (_: Exception) { errors++ }
        }
        for (raw in LocalSettings.treeUris(ctx)) try {
            val tree = Uri.parse(raw); visitTree(tree, DocumentsContract.getTreeDocumentId(tree), 0)
        } catch (_: Exception) { errors++ }
        return SourceInventory(found.values.toList(), directCount, treeCount, errors, truncated)
    }
}

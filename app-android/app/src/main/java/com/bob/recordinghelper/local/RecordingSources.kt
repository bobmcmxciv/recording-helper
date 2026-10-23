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
    fun allAliases(path: String, bytes: Long, modified: Long, primary: String): List<String> {
        val base = primary.trimEnd('/')
        val suffix = when {
            path.startsWith("$base/") -> path.removePrefix(base)
            path.startsWith("/sdcard/") -> path.removePrefix("/sdcard")
            path.startsWith("/storage/self/primary/") -> path.removePrefix("/storage/self/primary")
            path.startsWith("saf:primary:") -> "/" + path.removePrefix("saf:primary:").trimStart('/')
            else -> null
        }
        val paths = if (suffix != null) {
            listOf("$base$suffix", "/sdcard$suffix", "/storage/self/primary$suffix", "saf:primary:${suffix.trimStart('/')}", path).distinct()
        } else listOf(path)
        val times = if (modified >= 1000L) listOf(modified, (modified / 1000L) * 1000L).distinct() else listOf(modified)
        return paths.flatMap { p -> times.map { t -> fileKey(p, bytes, t) } }.distinct()
    }
    fun resolveLocalFile(item: RecordingSource, primaryDir: File = Environment.getExternalStorageDirectory()): File? {
        val rawPath = item.key.removeSuffix("|${item.bytes}|${item.modified}")
        val candidates = buildList {
            if (!item.source.startsWith("content://")) add(File(item.source))
            if (rawPath.isNotEmpty() && !rawPath.startsWith("saf:") && !rawPath.startsWith("media:") && !rawPath.startsWith("content://")) {
                add(File(rawPath))
            }
            if (rawPath.startsWith("saf:primary:")) add(File(primaryDir, rawPath.removePrefix("saf:primary:").trimStart('/')))
            if (item.source.startsWith("content://com.android.externalstorage.documents/")) {
                val decoded = runCatching { java.net.URLDecoder.decode(item.source, "UTF-8") }.getOrDefault("")
                val rel = decoded.substringAfterLast("/document/primary:", "").takeIf { it.isNotEmpty() }
                if (rel != null) add(File(primaryDir, rel.trimStart('/')))
            }
        }
        return candidates.firstOrNull { f ->
            f.isFile && f.canRead() && f.length() == item.bytes &&
                (f.lastModified() == item.modified || (f.lastModified() / 1000L) == (item.modified / 1000L))
        }
    }
    fun savedRecording(ctx: Context, item: RecordingSource): org.json.JSONObject? {
        val path = item.key.removeSuffix("|${item.bytes}|${item.modified}")
        val keys = allAliases(path, item.bytes, item.modified, Environment.getExternalStorageDirectory().canonicalPath)
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

    /** Folder names used by phone recorders; keeps music and downloads out of the queue. */
    val FOLDER_HINTS = listOf("sound_recorder", "recordings", "recording", "recorder", "/record/", "callrecord", "call_rec", "/sounds/", "voice recorder", "通话录音", "录音")

    /** Audio the system indexed inside recorder-like folders, as content URIs the queue can open. */
    fun mediaStore(ctx: Context, found: LinkedHashMap<String, RecordingSource>, full: () -> Boolean): Int {
        var errors = 0
        val uri = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(android.provider.MediaStore.Audio.Media._ID, android.provider.MediaStore.Audio.Media.DISPLAY_NAME,
            android.provider.MediaStore.Audio.Media.SIZE, android.provider.MediaStore.Audio.Media.DATE_MODIFIED, android.provider.MediaStore.Audio.Media.DATA)
        try {
            val cursor = ctx.contentResolver.query(uri, projection, null, null, android.provider.MediaStore.Audio.Media.DATE_MODIFIED + " DESC")
            if (cursor == null) return 1
            cursor.use { c -> while (c.moveToNext() && !full()) {
                val id = c.getLong(0); val name = c.getString(1).orEmpty(); val bytes = c.getLong(2); val modified = c.getLong(3) * 1000; val data = c.getString(4).orEmpty()
                if (name.substringAfterLast('.', "").lowercase() !in AutoScanWorker.AUDIO_EXTS || bytes <= 0) continue
                val lower = data.lowercase().replace('\\', '/')
                if (FOLDER_HINTS.none { lower.contains(it) }) continue
                val path = if (data.isNotBlank()) runCatching { File(data).canonicalPath }.getOrDefault(data) else "media:$id"
                val item = RecordingSource(android.content.ContentUris.withAppendedId(uri, id).toString(), fileKey(path, bytes, modified), name, bytes, modified)
                found.putIfAbsent(path, item)
            } }
        } catch (_: Exception) { errors++ }
        return errors
    }

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
        if (!AutoImportSettings.hasFileAccess(ctx) && AutoImportSettings.hasMediaAccess(ctx)) errors += mediaStore(ctx, found) { full() }
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

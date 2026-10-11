package com.bob.recordinghelper

import java.io.File
import java.io.RandomAccessFile

/**
 * 已处理台账：一行一个 path|size|mtime，追加写，不设上限。
 * 1.8.0 及以前存在 SharedPreferences 里、只留最近 3000 条：手机上录音多于 3000 个时，每次扫描都会把被挤掉的
 * 那批当新录音再处理一遍。台账文件还不存在时用 [legacy] 返回的旧记录建一份（只在第一次）。
 * 进程在写半行时被杀：下次加载先补上换行，那半行只会让对应的录音再处理一次。
 *
 * 文件名里带录音时间（…_YYYYMMDDHHMMSS.扩展名，小米录音机的通话/应用/会议录音都是这样）时，同路径同大小即视为
 * 同一个录音，不看 mtime：录音的创建时间已经写在名字里，同步工具（如 Syncthing）只改 mtime 时不该重转。
 * 名字里没有录音时间的文件仍要求 path|size|mtime 三者都相同。
 */
class ScanLedger(private val file: File, private val legacy: () -> List<String>) {
    private var keys: HashSet<String>? = null
    private val pathSize = HashSet<String>()

    @Synchronized
    fun contains(key: String): Boolean {
        val set = load()
        if (set.contains(key)) return true
        val ps = pathSizeOf(key) ?: return false
        return hasRecordingTime(ps.substringBeforeLast('|')) && pathSize.contains(ps)
    }

    @Synchronized
    fun add(key: String) {
        if (key.isEmpty() || !load().add(key)) return
        pathSizeOf(key)?.let { pathSize.add(it) }
        if ('\n' !in key) file.appendText(key + "\n")
    }

    @Synchronized
    fun size(): Int = load().size

    private fun load(): HashSet<String> {
        keys?.let { return it }
        val set = HashSet<String>()
        if (file.exists()) {
            file.forEachLine { if (it.isNotEmpty()) set.add(it) }
            if (file.length() > 0 && !endsWithNewline()) file.appendText("\n")
        } else {
            legacy().filterTo(set) { it.isNotEmpty() && '\n' !in it }
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(set.joinToString("") { it + "\n" })
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
        set.forEach { k -> pathSizeOf(k)?.let { pathSize.add(it) } }
        keys = set
        return set
    }

    private fun endsWithNewline(): Boolean = RandomAccessFile(file, "r").use {
        it.seek(it.length() - 1)
        it.read() == '\n'.code
    }

    companion object {
        private val RECORDING_TIME = Regex("""_20\d{12}\.[A-Za-z0-9]+$""")

        /** "path|size" of a "path|size|mtime" key, null when the key has no such shape. */
        fun pathSizeOf(key: String): String? {
            val i = key.lastIndexOf('|')
            return if (i > 0 && key.lastIndexOf('|', i - 1) > 0) key.substring(0, i) else null
        }

        /** The file name ends with the recording time (…_YYYYMMDDHHMMSS.ext). */
        fun hasRecordingTime(path: String): Boolean = RECORDING_TIME.containsMatchIn(path.substringAfterLast('/'))
    }
}

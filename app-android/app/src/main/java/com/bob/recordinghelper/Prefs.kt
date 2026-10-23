package com.bob.recordinghelper

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Prefs {
    private const val NAME = "rh_prefs"
    const val DEFAULT_BASE = ""

    val DEFAULT_SCAN_DIRS = listOf(
        "MIUI/sound_recorder",
        "Music/Recordings",
        "Recordings",
        "Record",
        "Sounds",
        "Recorder",
        "Voice Recorder",
    )

    private fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun baseUrl(ctx: Context): String = sp(ctx).getString("baseUrl", DEFAULT_BASE)!!.trim().trimEnd('/')

    fun token(ctx: Context): String = sp(ctx).getString("token", "")!!

    fun autoScan(ctx: Context): Boolean = sp(ctx).getBoolean("autoScan", false)
    fun pauseAutoScan(ctx: Context, paused: Boolean) {
        require(paused || scanScope(ctx) in com.bob.recordinghelper.local.ImportScope.ranges) { "请先选择自动处理范围" }
        check(sp(ctx).edit().putBoolean("autoScan", !paused).commit())
    }

    fun scanScope(ctx: Context): String? = com.bob.recordinghelper.local.ImportScope.restore(
        sp(ctx).getString("scanScope", null), sp(ctx).contains("scanSince") || autoScan(ctx) || scanDays(ctx) > 0,
        scanSince(ctx), scanDays(ctx))

    fun recentScanDays(ctx: Context): Int = sp(ctx).getInt("recentScanDays", scanDays(ctx)).takeIf { it in 1..36500 } ?: 0

    /** Explicit opt-out, unlike a temporary pause. Keep remembered days, queue and completed records. */
    fun disableAutoScan(ctx: Context) {
        check(sp(ctx).edit().putBoolean("autoScan", false).putString("scanScope", "off").commit())
    }

    /** Fixed start time: unlike a rolling day window, a long offline period cannot lose new recordings. */
    fun scanSince(ctx: Context): Long = sp(ctx).getLong("scanSince", 0L)

    fun setAutoScan(ctx: Context, enabled: Boolean, includeExisting: Boolean = false) {
        if (enabled) setScanScope(ctx, if (includeExisting) "all" else "new") else pauseAutoScan(ctx, true)
    }

    /** Explicit, mutually exclusive range; never leave a hidden "new only" cutoff behind. */
    fun setScanScope(ctx: Context, scope: String, days: Int = 0) {
        val selected = com.bob.recordinghelper.local.ImportScope.select(scope, days, System.currentTimeMillis(), scanSince(ctx), scanScope(ctx) == "new")
        val edit = sp(ctx).edit().putBoolean("autoScan", true).putString("scanScope", scope)
            .putLong("scanSince", selected.since).putString("scanDays", selected.days.toString())
        if (scope == "recent") edit.putInt("recentScanDays", days)
        check(edit.commit()) { "无法保存自动处理设置" }
    }

    // 只扫描最近 N 天的录音（0=不限，全部）；用于避免首次把历史录音全传
    fun scanDays(ctx: Context): Int = sp(ctx).getString("scanDays", "0")!!.toIntOrNull() ?: 0

    // 本地转写：录音在手机上转成文字，只上传文字，录音不上传；转写失败下次扫描重试。
    // 没在设置里动过这个开关时，手机上放了模型就默认开启（走本地路线）；手动关掉的保持关闭
    fun localAsr(ctx: Context): Boolean = true // 2.0 never falls back to uploading audio.

    fun setLocalAsr(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("localAsr", on).apply()

    // How transcripts are polished: local = on-device LLM (while charging, text never leaves the phone); off = not at all.
    const val POLISH_LOCAL = "local"
    const val POLISH_OFF = "off"
    fun polishMode(ctx: Context): String = sp(ctx).getString("polishMode", POLISH_OFF)!!.let { if (it == POLISH_LOCAL) it else POLISH_OFF }
    fun setPolishMode(ctx: Context, mode: String) = sp(ctx).edit().putString("polishMode", mode).apply()

    /** 最近一次本地转写的摘要（引擎、耗时），设置页展示用 */
    fun lastAsr(ctx: Context): String? = sp(ctx).getString("lastAsr", null)

    fun setLastAsr(ctx: Context, summary: String) = sp(ctx).edit().putString("lastAsr", summary).apply()

    /** ETag of the voiceprint library last downloaded (legacy). */
    fun voiceprintEtag(ctx: Context): String? = sp(ctx).getString("voiceprintEtag", null)
    fun setVoiceprintEtag(ctx: Context, etag: String) = sp(ctx).edit().putString("voiceprintEtag", etag).apply()

    fun scanDirs(ctx: Context): List<String> =
        sp(ctx).getString("scanDirs", DEFAULT_SCAN_DIRS.joinToString("\n"))!!
            .lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun save(ctx: Context, baseUrl: String, token: String, autoScan: Boolean, scanDirs: String, scanDays: String) {
        sp(ctx).edit()
            .putString("baseUrl", baseUrl.trim().trimEnd('/'))
            .putString("token", token.trim())
            .putBoolean("autoScan", autoScan)
            .putString("scanDirs", scanDirs)
            .putString("scanDays", scanDays.trim().ifEmpty { "0" })
            .apply()
    }

    // 已处理台账：path|size|mtime，防止扫描重复上传/重复本地转写。存 filesDir/scan-ledger.txt，不设上限；
    // 旧版存在 SharedPreferences 的 "ledger"（只留 3000 条），第一次读时并进来，原键不删（降级回旧版仍可用）
    @Volatile private var ledgerStore: ScanLedger? = null

    private fun ledger(ctx: Context): ScanLedger = ledgerStore ?: synchronized(this) {
        val app = ctx.applicationContext
        ledgerStore ?: ScanLedger(File(app.filesDir, "scan-ledger-v2.txt")) { emptyList() }.also { ledgerStore = it }
    }

    fun ledgerContains(ctx: Context, key: String): Boolean = ledger(ctx).contains(key)

    fun ledgerAdd(ctx: Context, key: String) = ledger(ctx).add(key)

    // 本地转写连续失败次数（台账键 → 次数），到上限的文件不再重试
    fun asrFailures(ctx: Context, key: String): Int =
        try { JSONObject(sp(ctx).getString("asrFailures", "{}")!!).optInt(key, 0) } catch (_: Exception) { 0 }

    fun setAsrFailures(ctx: Context, key: String, n: Int) {
        val obj = try { JSONObject(sp(ctx).getString("asrFailures", "{}")!!) } catch (_: Exception) { JSONObject() }
        if (n > 0) obj.put(key, n) else obj.remove(key)
        sp(ctx).edit().putString("asrFailures", obj.toString()).apply()
    }

    private fun legacyLedger(ctx: Context): List<String> {
        val raw = sp(ctx).getString("ledger", "[]")!!
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { emptyList() }
    }
}

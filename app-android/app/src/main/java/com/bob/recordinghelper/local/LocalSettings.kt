package com.bob.recordinghelper.local

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.util.UUID

object LocalSettings {
    fun prefs(ctx: Context) = ctx.getSharedPreferences("local-v2", Context.MODE_PRIVATE)
    fun sync(ctx: Context) = prefs(ctx).getBoolean("sync", false)
    fun device(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString("device", null) ?: UUID.randomUUID().toString().also { p.edit().putString("device", it).commit() }
    }
    // Candidates, not guarantees. The user can grant any actual recorder folder using SAF.
    val paths = linkedMapOf(
        "HONOR / 华为" to listOf("Sounds/CallRecord", "Sounds", "Recordings"),
        "vivo / iQOO" to listOf("Recordings", "Record", "Recorder", "Music/Recordings"),
        "小米 / Redmi" to listOf("MIUI/sound_recorder", "Recordings"),
        "OPPO / OnePlus / realme" to listOf("Music/Recordings", "Recordings", "Record"),
        "Samsung / 通用" to listOf("Recordings", "Sounds", "Voice Recorder")
    )

    fun currentBrand(
        manufacturer: String = Build.MANUFACTURER.orEmpty(),
        brand: String = Build.BRAND.orEmpty(),
    ): String {
        val token = "${manufacturer.lowercase()} ${brand.lowercase()}"
        return when {
            listOf("xiaomi", "redmi", "poco", "blackshark").any { token.contains(it) } -> "小米 / Redmi"
            listOf("huawei", "honor", "hinova", "ptac").any { token.contains(it) } -> "HONOR / 华为"
            listOf("vivo", "iqoo").any { token.contains(it) } -> "vivo / iQOO"
            listOf("oppo", "oneplus", "realme").any { token.contains(it) } -> "OPPO / OnePlus / realme"
            else -> "Samsung / 通用"
        }
    }

    fun currentBrandDirs(
        manufacturer: String = Build.MANUFACTURER.orEmpty(),
        brand: String = Build.BRAND.orEmpty(),
    ): Pair<String, List<String>> {
        val b = currentBrand(manufacturer, brand)
        return b to (paths[b] ?: paths.values.first())
    }

    /** Opens the system folder picker directly inside the phone brand's default recorder directory when available. */
    fun initialTreeUri(root: File = Environment.getExternalStorageDirectory()): Uri? = runCatching {
        val (_, brandDirs) = currentBrandDirs()
        val candidates = (brandDirs + paths.values.flatten()).distinct()
        val rel = candidates.firstOrNull { File(root, it).isDirectory } ?: brandDirs.firstOrNull() ?: "Recordings"
        DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$rel")
    }.getOrNull()

    fun treeUris(ctx: Context) = prefs(ctx).getStringSet("trees", emptySet())!!.toSet()
    fun addTree(ctx: Context, uri: String) = prefs(ctx).edit().putStringSet("trees", treeUris(ctx) + uri).apply()
}

package com.bob.recordinghelper.local

import android.content.Context
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
    fun treeUris(ctx: Context) = prefs(ctx).getStringSet("trees", emptySet())!!.toSet()
    fun addTree(ctx: Context, uri: String) = prefs(ctx).edit().putStringSet("trees", treeUris(ctx) + uri).apply()
}

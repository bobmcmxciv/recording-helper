package com.bob.recordinghelper.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Which model turns transcripts into notes and todos. Local is the default; the online path only becomes active
 * after the user has read the data-sharing notice and confirmed it (recorded as [consentAt]).
 */
object LlmSettings {
    const val MODE_LOCAL = "local"
    const val MODE_REMOTE = "remote"

    private fun p(ctx: Context) = ctx.getSharedPreferences("llm-settings", Context.MODE_PRIVATE)

    fun mode(ctx: Context): String = p(ctx).getString("mode", MODE_LOCAL) ?: MODE_LOCAL
    fun providerId(ctx: Context): String = p(ctx).getString("provider", "") ?: ""
    fun baseUrl(ctx: Context): String = p(ctx).getString("baseUrl", "") ?: ""
    fun apiKey(ctx: Context): String = p(ctx).getString("apiKey", "") ?: ""
    fun model(ctx: Context): String = p(ctx).getString("model", "") ?: ""
    fun wifiOnly(ctx: Context): Boolean = p(ctx).getBoolean("wifiOnly", true)
    fun consentAt(ctx: Context): Long = p(ctx).getLong("consentAt", 0L)
    fun lastCallAt(ctx: Context): Long = p(ctx).getLong("lastCallAt", 0L)
    fun lastUsage(ctx: Context): String = p(ctx).getString("lastUsage", "") ?: ""
    fun totalCalls(ctx: Context): Int = p(ctx).getInt("totalCalls", 0)

    fun api(ctx: Context): LlmApi = LlmProviders.byId(providerId(ctx))?.api ?: LlmApi.OPENAI

    /** True only when the user chose the online service, confirmed the notice and filled in the connection. */
    fun remoteEnabled(ctx: Context): Boolean = mode(ctx) == MODE_REMOTE && consentAt(ctx) > 0 &&
        baseUrl(ctx).startsWith("https://") && apiKey(ctx).isNotBlank() && model(ctx).isNotBlank()

    fun useLocal(ctx: Context) { p(ctx).edit().putString("mode", MODE_LOCAL).apply() }

    /** Saves the connection and records the consent moment. */
    fun useRemote(ctx: Context, providerId: String, baseUrl: String, apiKey: String, model: String, wifiOnly: Boolean) {
        require(baseUrl.startsWith("https://")) { "接口地址必须是 HTTPS" }
        require(apiKey.isNotBlank() && model.isNotBlank()) { "请填写 API Key 和模型名" }
        p(ctx).edit().putString("mode", MODE_REMOTE).putString("provider", providerId).putString("baseUrl", baseUrl.trimEnd('/'))
            .putString("apiKey", apiKey.trim()).putString("model", model.trim()).putBoolean("wifiOnly", wifiOnly)
            .putLong("consentAt", System.currentTimeMillis()).apply()
    }

    fun saveConnection(ctx: Context, providerId: String, baseUrl: String, apiKey: String, model: String, wifiOnly: Boolean) {
        p(ctx).edit().putString("provider", providerId).putString("baseUrl", baseUrl.trimEnd('/'))
            .putString("apiKey", apiKey.trim()).putString("model", model.trim()).putBoolean("wifiOnly", wifiOnly).apply()
    }

    fun recordCall(ctx: Context, usage: String) {
        p(ctx).edit().putLong("lastCallAt", System.currentTimeMillis()).putString("lastUsage", usage)
            .putInt("totalCalls", totalCalls(ctx) + 1).apply()
    }

    fun forget(ctx: Context) { p(ctx).edit().clear().apply() }

    fun onUnmeteredNetwork(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Opens the configured online model; null when the online path is not active. */
    fun remoteModel(ctx: Context): RemoteTextModel? =
        if (remoteEnabled(ctx)) RemoteTextModel(api(ctx), baseUrl(ctx), apiKey(ctx), model(ctx)) { usage -> recordCall(ctx, usage) } else null

    /** The notice shown before the online path can be enabled. */
    const val NOTICE = "开启后，录音的转写文字会发送到你选择的在线 AI 服务商的服务器，用于生成纪要、待办和时段总结。\n\n" +
        "这些文字可能包含通话内容、人名、电话号码、地址和其他隐私信息。服务商会按它自己的隐私政策处理这些数据，可能保存在境外，也可能产生费用。\n\n" +
        "音频文件和声纹永远不会发送。你随时可以改回本地模型，但已经发送的内容无法撤回。"
}

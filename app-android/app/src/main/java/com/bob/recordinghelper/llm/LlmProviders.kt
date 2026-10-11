package com.bob.recordinghelper.llm

/** Online LLM services the user may opt into. Every preset is editable; two generic entries cover anything else. */
enum class LlmApi { OPENAI, ANTHROPIC }

data class LlmProvider(
    val id: String,
    val name: String,
    val api: LlmApi,
    val baseUrl: String,
    val defaultModel: String,
    val keyHint: String,
    val note: String,
)

object LlmProviders {
    val all: List<LlmProvider> = listOf(
        LlmProvider("openai", "OpenAI", LlmApi.OPENAI, "https://api.openai.com/v1", "gpt-5-mini", "sk-…", "服务器在境外"),
        LlmProvider("anthropic", "Anthropic", LlmApi.ANTHROPIC, "https://api.anthropic.com", "claude-haiku-5-5", "sk-ant-…", "服务器在境外"),
        LlmProvider("deepseek", "DeepSeek", LlmApi.OPENAI, "https://api.deepseek.com/v1", "deepseek-chat", "sk-…", "OpenAI 兼容接口"),
        LlmProvider("dashscope", "阿里云百炼", LlmApi.OPENAI, "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "sk-…", "OpenAI 兼容接口"),
        LlmProvider("zhipu", "智谱 AI", LlmApi.OPENAI, "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash", "", "OpenAI 兼容接口"),
        LlmProvider("moonshot", "Moonshot（Kimi）", LlmApi.OPENAI, "https://api.moonshot.cn/v1", "moonshot-v1-32k", "sk-…", "OpenAI 兼容接口"),
        LlmProvider("gemini", "Google Gemini", LlmApi.OPENAI, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", "AIza…", "OpenAI 兼容接口，服务器在境外"),
        LlmProvider("openai-compatible", "自定义 · OpenAI 兼容", LlmApi.OPENAI, "", "", "", "任何提供 /chat/completions 的服务"),
        LlmProvider("anthropic-compatible", "自定义 · Anthropic 兼容", LlmApi.ANTHROPIC, "", "", "", "任何提供 /v1/messages 的服务"),
    )

    fun byId(id: String?): LlmProvider? = all.firstOrNull { it.id == id }
}

package com.nek0ayaya.gradentranslator

import android.content.SharedPreferences

internal object ConfigKeys {
    const val REMOTE_GROUP = "gradentranslator"
    const val BASE_URL = "api_base_url"
    const val ENDPOINT_PATH = "endpoint_path"
    const val API_KEY = "api_key"
    const val MODEL_ID = "model_id"
    const val TARGET_LANGUAGE = "target_language"
    const val BATCH_SIZE = "batch_size"
    const val TIMEOUT_SECONDS = "timeout_seconds"
    const val TRANSLATE_UNKNOWN_TEXT = "translate_unknown_text"
}

internal data class TranslationConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val endpointPath: String = "/chat/completions",
    val apiKey: String = "",
    val modelId: String = "gpt-4o-mini",
    val targetLanguage: String = "简体中文",
    val batchSize: Int = 12,
    val timeoutSeconds: Int = 25,
    val translateUnknownText: Boolean = false
) {
    val isReady: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && modelId.isNotBlank() && endpointPath.isNotBlank()

    val endpointUrl: String
        get() {
            val base = baseUrl.trim().trimEnd('/')
            val path = endpointPath.trim().ifBlank { "/chat/completions" }
                .let { if (it.startsWith('/')) it else "/$it" }
            return base + path
        }

    fun validated(): TranslationConfig {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val cleanPath = endpointPath.trim().let { if (it.startsWith('/')) it else "/$it" }
        val cleanKey = apiKey.trim()
        val cleanModel = modelId.trim()
        require(cleanBase.startsWith("https://", ignoreCase = true) ||
            cleanBase.startsWith("http://", ignoreCase = true)) {
            "API Base URL 必须以 http:// 或 https:// 开头"
        }
        require(cleanKey.isNotBlank()) { "请填写 API Key" }
        require(cleanModel.isNotBlank()) { "请填写模型 ID" }
        require(cleanPath.length > 1 && !cleanPath.contains("?")) {
            "API 请求路径不正确，请填写类似 /chat/completions 的路径"
        }
        return copy(
            baseUrl = cleanBase,
            endpointPath = cleanPath,
            apiKey = cleanKey,
            modelId = cleanModel,
            targetLanguage = targetLanguage.trim().ifBlank { "简体中文" },
            batchSize = batchSize.coerceIn(1, 20),
            timeoutSeconds = timeoutSeconds.coerceIn(8, 120)
        )
    }

    companion object {
        fun fromPreferences(preferences: SharedPreferences): TranslationConfig = TranslationConfig(
            baseUrl = preferences.getString(ConfigKeys.BASE_URL, "https://api.openai.com/v1") ?: "",
            endpointPath = preferences.getString(ConfigKeys.ENDPOINT_PATH, "/chat/completions") ?: "/chat/completions",
            apiKey = preferences.getString(ConfigKeys.API_KEY, "") ?: "",
            modelId = preferences.getString(ConfigKeys.MODEL_ID, "gpt-4o-mini") ?: "",
            targetLanguage = preferences.getString(ConfigKeys.TARGET_LANGUAGE, "简体中文") ?: "简体中文",
            batchSize = preferences.getInt(ConfigKeys.BATCH_SIZE, 12),
            timeoutSeconds = preferences.getInt(ConfigKeys.TIMEOUT_SECONDS, 25),
            translateUnknownText = preferences.getBoolean(ConfigKeys.TRANSLATE_UNKNOWN_TEXT, false)
        )
    }
}

package com.nek0ayaya.gradentranslator

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** OpenAI Chat Completions-compatible client with structured-JSON fallbacks. */
internal object OpenAiApiClient {

    private enum class ResponseMode { JSON_SCHEMA, JSON_OBJECT, PLAIN_JSON }

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun translateSingle(config: TranslationConfig, source: String): String {
        return translateBatch(config, listOf(source))["0"]
            ?: throw ApiException("接口没有返回可用的翻译结果")
    }

    /**
     * Sends several UI strings in one request. Returned map keys are the original numeric IDs.
     * Missing IDs are deliberately omitted so the caller never displays a guessed translation.
     */
    fun translateBatch(config: TranslationConfig, sources: List<String>): Map<String, String> {
        val cleanConfig = config.validated()
        require(sources.isNotEmpty()) { "没有需要翻译的文本" }

        val input = JSONArray()
        sources.forEachIndexed { index, text ->
            input.put(JSONObject().put("id", index.toString()).put("text", text))
        }

        var lastError: Throwable? = null
        for (mode in ResponseMode.values()) {
            try {
                val response = requestWithRetry(cleanConfig, input, mode)
                val content = extractContent(response)
                val expectedIds = sources.indices.map { it.toString() }.toSet()
                val parsed = runCatching { parseTranslations(content, expectedIds) }.getOrDefault(emptyMap())
                val safe = parsed.filter { (id, value) ->
                    val source = sources.getOrNull(id.toIntOrNull() ?: -1)
                    source != null && value.length <= source.length * 5 + 80 &&
                        preservesPlaceholders(source, value)
                }
                if (safe.isNotEmpty()) return safe
                lastError = ApiException("模型返回了内容，但没有找到 translations 数组")
            } catch (unsupported: ResponseFormatUnsupported) {
                lastError = unsupported
                // Some OpenAI-compatible providers reject response_format. Try a simpler format.
                continue
            } catch (error: Exception) {
                lastError = error
                break
            }
        }
        throw ApiException(lastError?.message ?: "翻译请求失败", lastError)
    }

    private fun requestWithRetry(
        config: TranslationConfig,
        input: JSONArray,
        mode: ResponseMode
    ): String {
        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put("content", systemPrompt(config.targetLanguage))
            )
            .put(
                JSONObject()
                    .put("role", "user")
                    .put("content", input.toString())
            )

        val payload = JSONObject()
            .put("model", config.modelId)
            .put("temperature", 0)
            .put("messages", messages)

        when (mode) {
            ResponseMode.JSON_SCHEMA -> payload.put("response_format", jsonSchemaFormat())
            ResponseMode.JSON_OBJECT -> payload.put(
                "response_format",
                JSONObject().put("type", "json_object")
            )
            ResponseMode.PLAIN_JSON -> Unit
        }

        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(config.endpointUrl)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Accept", "application/json")
            .post(body)
            .build()

        val client = baseClient.newBuilder()
            .callTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()

        var lastIo: IOException? = null
        for (attempt in 0..2) {
            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (response.isSuccessful) return responseBody

                    val code = response.code
                    if (mode != ResponseMode.PLAIN_JSON && code in setOf(400, 404, 415, 422)) {
                        throw ResponseFormatUnsupported("服务端不支持当前 JSON 输出格式（HTTP $code）")
                    }

                    if ((code == 429 || code >= 500) && attempt < 2) {
                        val retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
                        val delayMs = retryAfterSeconds?.coerceIn(1L, 15L)?.times(1_000L)
                            ?: (600L shl attempt)
                        Thread.sleep(delayMs)
                        return@use
                    }

                    val detail = responseBody.replace(Regex("[\\r\\n]+"), " ").take(260)
                    throw ApiException("API 请求失败：HTTP $code${if (detail.isNotBlank()) "，$detail" else ""}")
                }
                if (attempt == 2) break
            } catch (unsupported: ResponseFormatUnsupported) {
                throw unsupported
            } catch (api: ApiException) {
                throw api
            } catch (io: IOException) {
                lastIo = io
                if (attempt < 2) Thread.sleep(500L * (attempt + 1))
            }
        }
        throw ApiException(lastIo?.message ?: "网络请求超时或连接失败", lastIo)
    }

    private fun systemPrompt(language: String): String = """
        You are a professional software UI localizer.
        Translate each supplied UI string into $language.
        Return exactly one JSON object with this shape:
        {"translations":[{"id":"0","text":"translated text"}]}
        Requirements:
        - Return one result for every input id. Never omit or invent ids.
        - Keep ids exactly unchanged and preserve the original order.
        - Preserve placeholders such as %s, %d, {name}, ${'$'}{name}, \\n, HTML tags, punctuation, numbers and keyboard shortcuts.
        - Do not translate filenames, product names, brand names or technical identifiers when they are clearly names.
        - Translate concise UI wording naturally. Do not add explanations, notes or Markdown fences.
        - If a string is already in the target language, keep it unchanged.
    """.trimIndent()

    private fun jsonSchemaFormat(): JSONObject {
        val stringSchema = JSONObject().put("type", "string")
        val itemSchema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put("required", JSONArray().put("id").put("text"))
            .put(
                "properties",
                JSONObject()
                    .put("id", stringSchema)
                    .put("text", stringSchema)
            )
        val schema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put("required", JSONArray().put("translations"))
            .put(
                "properties",
                JSONObject().put(
                    "translations",
                    JSONObject().put("type", "array").put("items", itemSchema)
                )
            )

        return JSONObject().put(
            "type",
            "json_schema"
        ).put(
            "json_schema",
            JSONObject()
                .put("name", "ui_translations")
                .put("strict", true)
                .put("schema", schema)
        )
    }

    private fun extractContent(responseBody: String): String {
        val root = JSONObject(responseBody)
        val choices = root.optJSONArray("choices")
            ?: throw ApiException("API 响应缺少 choices")
        if (choices.length() == 0) throw ApiException("模型没有返回内容")

        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: throw ApiException("API 响应缺少 message")
        return when (val content = message.opt("content")) {
            is String -> content
            is JSONArray -> buildString {
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    append(part.optString("text"))
                }
            }
            else -> throw ApiException("模型返回的 content 不是文本")
        }
    }

    private fun parseTranslations(content: String, expectedIds: Set<String>): Map<String, String> {
        var text = content.trim()
        text = text.replace(Regex("(?is)^```(?:json)?\\s*"), "")
            .replace(Regex("(?is)\\s*```$"), "")
            .trim()
        val first = text.indexOf('{')
        val last = text.lastIndexOf('}')
        if (first >= 0 && last > first) text = text.substring(first, last + 1)

        val root = JSONObject(text)
        val list = root.optJSONArray("translations")
            ?: root.optJSONArray("items")
            ?: throw ApiException("JSON 响应缺少 translations 数组")
        val result = LinkedHashMap<String, String>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val translation = item.optString("text").ifBlank { item.optString("translation") }.trim()
            if (id in expectedIds && translation.isNotBlank()) {
                result[id] = translation
            }
        }
        return result
    }

    private fun preservesPlaceholders(source: String, translated: String): Boolean {
        val pattern = Regex(
            """%(?:[0-9]+\$)?[-+# 0,(<]*[0-9]*(?:\.[0-9]+)?[a-zA-Z]|\\[nrt]|\{[A-Za-z_][A-Za-z0-9_.-]*}|${'$'}\{[^}]+}|<[^>]{1,80}>"""
        )
        fun tokens(value: String): Map<String, Int> = pattern.findAll(value)
            .map { it.value }
            .groupingBy { it }
            .eachCount()
        return tokens(source) == tokens(translated)
    }

    class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
    private class ResponseFormatUnsupported(message: String) : Exception(message)
}

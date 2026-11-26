package com.nek0ayaya.gradentranslator.hook

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object Translator {
    private val cache = ConcurrentHashMap<String, String>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newFixedThreadPool(2)
    private val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
    private val context: Context? by lazy {
        runCatching {
            val c = Class.forName("android.app.ActivityThread")
            c.getMethod("currentApplication").invoke(null) as? Context
        }.getOrNull()
    }

    fun translateCached(text: String): String {
        cache[text]?.let { return it }
        val p = context?.getSharedPreferences("translator", Context.MODE_PRIVATE)
        val stored = p?.getString("cache:$text", null)
        if (stored != null) { cache[text] = stored; return stored }
        if (pending.add(text)) executor.execute { translateOne(text) }
        return text
    }

    private fun translateOne(text: String) {
        try {
            val p = context?.getSharedPreferences("translator", Context.MODE_PRIVATE) ?: return
            val base = p.getString("base", "")?.trim().orEmpty().trimEnd('/')
            val model = p.getString("model", "")?.trim().orEmpty()
            val key = p.getString("key", "")?.trim().orEmpty()
            if (base.isBlank() || model.isBlank() || key.isBlank()) return
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", "Translate UI strings from English to Simplified Chinese. Preserve placeholders, numbers, punctuation and product names. Return only the translated text."))
                .put(JSONObject().put("role", "user").put("content", text))
            val body = JSONObject().put("model", model).put("temperature", 0).put("messages", messages)
                .toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("$base/chat/completions")
                .header("Authorization", "Bearer $key").post(body).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val content = JSONObject(response.body?.string().orEmpty())
                    .getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
                if (content.isNotBlank() && content.length <= text.length * 4 + 40) {
                    cache[text] = content
                    p.edit().putString("cache:$text", content).apply()
                }
            }
        } catch (_: Throwable) {
        } finally { pending.remove(text) }
    }
}

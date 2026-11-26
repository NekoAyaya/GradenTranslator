package com.nek0ayaya.gradentranslator.hook

import android.content.Context
import android.content.SharedPreferences
import com.nek0ayaya.gradentranslator.OpenAiApiClient
import com.nek0ayaya.gradentranslator.TranslationConfig
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object Translator {
    private data class CacheKey(val source: String, val targetLanguage: String)

    private val memoryCache = ConcurrentHashMap<CacheKey, String>()
    private val translatedValuesByLanguage = ConcurrentHashMap<String, MutableSet<String>>()
    private val inFlight = ConcurrentHashMap.newKeySet<CacheKey>()
    private val queue = ConcurrentLinkedQueue<CacheKey>()
    private val recomposeScopes = ConcurrentHashMap<CacheKey, CopyOnWriteArrayList<WeakReference<Any>>>()

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GradenTranslator-worker").apply { isDaemon = true }
    }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "GradenTranslator-batcher").apply { isDaemon = true }
    }
    private val scheduled = AtomicBoolean(false)

    @Volatile
    private var config = TranslationConfig()

    @Volatile
    private var hostContext: Context? = null

    @Volatile
    private var cacheDatabase: TranslationCache? = null

    fun attachHostContext(context: Context) {
        hostContext = context.applicationContext
    }

    fun updateConfig(preferences: SharedPreferences) {
        val updated = TranslationConfig.fromPreferences(preferences)
        config = updated
        if (updated.isReady && queue.isNotEmpty()) scheduleDrain()
    }

    fun isUnknownTextTranslationEnabled(): Boolean = config.translateUnknownText

    /** Prevents translated text passed through nested Compose Text wrappers from being translated again. */
    fun isAlreadyTranslated(source: String): Boolean =
        translatedValuesByLanguage[config.targetLanguage]?.contains(source) == true

    private fun rememberTranslation(language: String, translated: String) {
        translatedValuesByLanguage.computeIfAbsent(language) {
            ConcurrentHashMap.newKeySet<String>()
        }.add(translated)
    }

    /** Called synchronously from the Compose text hook. Cache misses never block rendering. */
    fun translateCached(source: String, recomposeScope: Any?): String {
        val currentConfig = config
        if (!currentConfig.isReady) return source

        val key = CacheKey(source, currentConfig.targetLanguage)
        memoryCache[key]?.let { return it }

        if (recomposeScope != null) rememberScope(key, recomposeScope)
        if (inFlight.add(key)) {
            queue.offer(key)
            scheduleDrain()
        }
        return source
    }

    private fun rememberScope(key: CacheKey, scope: Any) {
        val refs = recomposeScopes.computeIfAbsent(key) { CopyOnWriteArrayList() }
        val alreadyTracked = refs.any { it.get() === scope }
        if (!alreadyTracked) refs.add(WeakReference(scope))
    }

    private fun scheduleDrain() {
        if (!scheduled.compareAndSet(false, true)) return
        scheduler.schedule({
            scheduled.set(false)
            val currentConfig = config
            if (currentConfig.isReady) {
                val batch = ArrayList<CacheKey>(currentConfig.batchSize)
                repeat(currentConfig.batchSize) {
                    val item = queue.poll() ?: return@repeat
                    if (item.targetLanguage == currentConfig.targetLanguage) {
                        batch.add(item)
                    } else {
                        // A language change occurred while this item was waiting. Do not translate
                        // it with the new language; a future composition will enqueue the right key.
                        inFlight.remove(item)
                        recomposeScopes.remove(item)
                    }
                }
                if (batch.isNotEmpty()) {
                    worker.execute { processBatch(batch, currentConfig) }
                }
                if (queue.isNotEmpty()) scheduleDrain()
            }
        }, 220, TimeUnit.MILLISECONDS)
    }

    private fun processBatch(batch: List<CacheKey>, requestConfig: TranslationConfig) {
        val db = getCacheDatabase()
        val uncached = ArrayList<CacheKey>(batch.size)

        batch.forEach { key ->
            val stored = runCatching {
                db?.getTranslation(key.source, key.targetLanguage)
            }.getOrNull()
            if (!stored.isNullOrBlank()) {
                memoryCache[key] = stored
                rememberTranslation(key.targetLanguage, stored)
                finish(key, invalidate = true)
            } else {
                uncached.add(key)
            }
        }

        if (uncached.isNotEmpty()) {
            try {
                val sources = uncached.map { it.source }
                val translated = OpenAiApiClient.translateBatch(requestConfig, sources)
                val valid = LinkedHashMap<CacheKey, String>()

                uncached.forEachIndexed { index, key ->
                    val value = translated[index.toString()]?.trim()
                    // Reject suspiciously long results rather than letting a malformed response
                    // distort the UI. Only the exact expected numeric IDs are considered.
                    if (!value.isNullOrBlank() && value.length <= key.source.length * 5 + 80) {
                        valid[key] = value
                    }
                }

                val bySource = valid.entries.associate { it.key.source to it.value }
                runCatching { db?.putTranslations(bySource, requestConfig.targetLanguage) }
                valid.forEach { (key, value) ->
                    memoryCache[key] = value
                    rememberTranslation(key.targetLanguage, value)
                    finish(key, invalidate = true)
                }
            } catch (_: Throwable) {
                // Do not write request headers, API keys or provider response bodies to logs.
                // Failed strings remain eligible for a future retry when Compose draws them again.
            }

            // Release unresolved requests. They remain untranslated and can be enqueued again later.
            uncached.forEach { key ->
                if (inFlight.remove(key)) recomposeScopes.remove(key)
            }
        }

        if (queue.isNotEmpty()) scheduleDrain()
    }

    private fun finish(key: CacheKey, invalidate: Boolean) {
        inFlight.remove(key)
        val refs = recomposeScopes.remove(key) ?: return
        if (!invalidate) return

        refs.forEach { weak ->
            val scope = weak.get() ?: return@forEach
            runCatching {
                val invalidateMethod = scope.javaClass.methods.firstOrNull {
                    it.name == "invalidate" && it.parameterTypes.isEmpty()
                } ?: scope.javaClass.interfaces
                    .asSequence()
                    .flatMap { it.methods.asSequence() }
                    .firstOrNull { it.name == "invalidate" && it.parameterTypes.isEmpty() }

                invalidateMethod?.invoke(scope)
            }
        }
    }

    private fun getCacheDatabase(): TranslationCache? {
        cacheDatabase?.let { return it }
        val context = hostContext ?: return null
        return synchronized(this) {
            cacheDatabase ?: runCatching { TranslationCache(context).also { cacheDatabase = it } }.getOrNull()
        }
    }
}

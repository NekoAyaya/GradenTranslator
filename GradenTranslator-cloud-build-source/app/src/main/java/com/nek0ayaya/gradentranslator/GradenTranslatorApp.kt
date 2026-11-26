package com.nek0ayaya.gradentranslator

import android.app.Application
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.Volatile

class GradenTranslatorApp : Application(), XposedServiceHelper.OnServiceListener {

    interface ConnectionListener {
        fun onFrameworkConnectionChanged(connected: Boolean)
    }

    companion object {
        private const val DRAFT_PREFS = "gradentranslator_draft"
        private const val LEGACY_PREFS = "translator"

        @Volatile
        private var currentService: XposedService? = null

        @Volatile
        private var remotePreferences: SharedPreferences? = null

        private val listeners = CopyOnWriteArraySet<ConnectionListener>()

        fun isFrameworkConnected(): Boolean = remotePreferences != null

        fun addConnectionListener(listener: ConnectionListener) {
            listeners.add(listener)
            listener.onFrameworkConnectionChanged(isFrameworkConnected())
        }

        fun removeConnectionListener(listener: ConnectionListener) {
            listeners.remove(listener)
        }

        internal fun loadDraft(context: android.content.Context): TranslationConfig {
            val draft = context.getSharedPreferences(DRAFT_PREFS, android.content.Context.MODE_PRIVATE)
            return TranslationConfig.fromPreferences(draft)
        }

        /**
         * Saves a private local draft first, then publishes the same values to LSPosed
         * RemotePreferences. The API key is never written to logs or the target app's storage.
         */
        internal fun saveConfiguration(context: android.content.Context, config: TranslationConfig): Boolean {
            val clean = config.validated()
            val draft = context.getSharedPreferences(DRAFT_PREFS, android.content.Context.MODE_PRIVATE)
            if (!writeConfig(draft, clean).commit()) return false

            val remote = remotePreferences ?: runCatching {
                currentService?.getRemotePreferences(ConfigKeys.REMOTE_GROUP)
            }.getOrNull()

            remotePreferences = remote
            if (remote == null) return false
            return runCatching { writeConfig(remote, clean).commit() }.getOrDefault(false)
        }

        private fun writeConfig(
            preferences: SharedPreferences,
            config: TranslationConfig
        ): SharedPreferences.Editor = preferences.edit()
            .putString(ConfigKeys.BASE_URL, config.baseUrl)
            .putString(ConfigKeys.ENDPOINT_PATH, config.endpointPath)
            .putString(ConfigKeys.API_KEY, config.apiKey)
            .putString(ConfigKeys.MODEL_ID, config.modelId)
            .putString(ConfigKeys.TARGET_LANGUAGE, config.targetLanguage)
            .putInt(ConfigKeys.BATCH_SIZE, config.batchSize)
            .putInt(ConfigKeys.TIMEOUT_SECONDS, config.timeoutSeconds)
            .putBoolean(ConfigKeys.TRANSLATE_UNKNOWN_TEXT, config.translateUnknownText)

        private fun notifyConnectionChanged() {
            val connected = isFrameworkConnected()
            listeners.forEach { listener ->
                runCatching { listener.onFrameworkConnectionChanged(connected) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        migrateLegacyDraft()
        // The libxposed service library receives the binder from LSPosed when the module app opens.
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        currentService = service
        val prefs = runCatching {
            service.getRemotePreferences(ConfigKeys.REMOTE_GROUP)
        }.getOrNull()
        remotePreferences = prefs

        if (prefs != null) {
            val draft = getSharedPreferences(DRAFT_PREFS, android.content.Context.MODE_PRIVATE)
            val remoteConfig = TranslationConfig.fromPreferences(prefs)
            val draftConfig = TranslationConfig.fromPreferences(draft)

            if (!remoteConfig.isReady && draftConfig.isReady) {
                runCatching { writeConfig(prefs, draftConfig).commit() }
            } else if (remoteConfig.isReady) {
                runCatching { writeConfig(draft, remoteConfig).commit() }
            }
        }
        notifyConnectionChanged()
    }

    override fun onServiceDied(service: XposedService) {
        if (currentService === service) {
            currentService = null
            remotePreferences = null
            notifyConnectionChanged()
        }
    }

    private fun migrateLegacyDraft() {
        val draft = getSharedPreferences(DRAFT_PREFS, android.content.Context.MODE_PRIVATE)
        if (draft.contains(ConfigKeys.BASE_URL)) return

        // Migrate values saved by the first prototype build, so users do not have to re-enter them.
        val legacy = getSharedPreferences(LEGACY_PREFS, android.content.Context.MODE_PRIVATE)
        if (!legacy.contains("base") && !legacy.contains("key") && !legacy.contains("model")) return

        draft.edit()
            .putString(ConfigKeys.BASE_URL, legacy.getString("base", "https://api.openai.com/v1"))
            .putString(ConfigKeys.ENDPOINT_PATH, "/chat/completions")
            .putString(ConfigKeys.API_KEY, legacy.getString("key", ""))
            .putString(ConfigKeys.MODEL_ID, legacy.getString("model", "gpt-4o-mini"))
            .putString(ConfigKeys.TARGET_LANGUAGE, "简体中文")
            .putInt(ConfigKeys.BATCH_SIZE, 12)
            .putInt(ConfigKeys.TIMEOUT_SECONDS, 25)
            .putBoolean(ConfigKeys.TRANSLATE_UNKNOWN_TEXT, false)
            .apply()
    }
}

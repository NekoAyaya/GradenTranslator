package com.nek0ayaya.gradentranslator.hook

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.nek0ayaya.gradentranslator.ConfigKeys
import com.nek0ayaya.gradentranslator.TranslationConfig
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Modifier

class GradenEntry : XposedModule() {

    @Volatile
    private var loadedProcessName: String? = null

    private var remoteConfigListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        loadedProcessName = param.processName
        log(Log.INFO, TAG, "Loaded in process: ${param.processName}")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != TARGET) return
        if (loadedProcessName != TARGET) return

        try {
            initializeRemoteConfig()
            hookApplicationAttach()
            installComposeTextHooks(param.classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Hook initialization failed", t)
        }
    }

    private fun initializeRemoteConfig() {
        try {
            val preferences = getRemotePreferences(ConfigKeys.REMOTE_GROUP)
            Translator.updateConfig(preferences)

            val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, _ ->
                Translator.updateConfig(changed)
            }
            remoteConfigListener = listener
            preferences.registerOnSharedPreferenceChangeListener(listener)
            log(Log.INFO, TAG, "LSPosed RemotePreferences connected")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Cannot read LSPosed RemotePreferences; open the module app and check LSPosed service", t)
        }
    }

    /**
     * The module is loaded before Graden's Application is necessarily created.
     * Capture Application.attach so the async SQLite cache uses Graden's private app context,
     * while the API key itself remains in LSPosed RemotePreferences.
     */
    private fun hookApplicationAttach() {
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        hook(attach)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val app = chain.thisObject as? Application
                if (app?.packageName == TARGET) {
                    Translator.attachHostContext(app)
                }
                result
            }
    }

    private fun installComposeTextHooks(classLoader: ClassLoader) {
        val candidates = listOf(
            "androidx.compose.material3.TextKt",
            "androidx.compose.material.TextKt",
            "androidx.compose.foundation.text.BasicTextKt"
        )

        var hooked = 0
        candidates.forEach { className ->
            val clazz = runCatching { classLoader.loadClass(className) }.getOrNull()
                ?: return@forEach

            clazz.declaredMethods
                .filter { method ->
                    Modifier.isStatic(method.modifiers) &&
                        (method.name == "Text" || method.name == "BasicText")
                }
                .forEach methodLoop@{ method ->
                    val stringIndex = method.parameterTypes.indexOfFirst {
                        it == String::class.java
                    }
                    if (stringIndex < 0) return@methodLoop

                    val composerIndex = method.parameterTypes.indexOfFirst {
                        it.name == "androidx.compose.runtime.Composer"
                    }

                    runCatching {
                        hook(method)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept { chain ->
                                val args = chain.args.toTypedArray()
                                val original = args.getOrNull(stringIndex) as? String

                                if (original != null &&
                                    !Translator.isAlreadyTranslated(original) &&
                                    TextFilter.isUiText(
                                        original,
                                        allowUnknown = Translator.isUnknownTextTranslationEnabled()
                                    )) {
                                    val scope = if (composerIndex >= 0) {
                                        currentRecomposeScope(args.getOrNull(composerIndex))
                                    } else {
                                        null
                                    }
                                    val translated = Translator.translateCached(original, scope)
                                    if (translated != original) args[stringIndex] = translated
                                }

                                chain.proceed(args)
                            }
                        hooked++
                    }.onFailure { error ->
                        log(Log.WARN, TAG, "Hook failed for $method: $error")
                    }
                }
        }

        log(Log.INFO, TAG, "Compose String overload hooks installed: $hooked")
    }

    private fun currentRecomposeScope(composer: Any?): Any? {
        if (composer == null) return null
        return runCatching {
            val getter = composer.javaClass.methods.firstOrNull {
                it.name == "getRecomposeScope" && it.parameterTypes.isEmpty()
            } ?: composer.javaClass.interfaces
                .asSequence()
                .flatMap { it.methods.asSequence() }
                .firstOrNull { it.name == "getRecomposeScope" && it.parameterTypes.isEmpty() }

            val scope = getter?.invoke(composer) ?: return@runCatching null
            // Mirrors Compose's currentRecomposeScope implementation: record that this scope is
            // used before attempting to invalidate it later from the translation worker.
            val recordUsed = composer.javaClass.methods.firstOrNull {
                it.name == "recordUsed" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].isInstance(scope)
            } ?: composer.javaClass.interfaces
                .asSequence()
                .flatMap { it.methods.asSequence() }
                .firstOrNull {
                    it.name == "recordUsed" && it.parameterTypes.size == 1 &&
                        it.parameterTypes[0].isInstance(scope)
                }
            recordUsed?.invoke(composer, scope)
            scope
        }.getOrNull()
    }

    companion object {
        const val TARGET = "com.graden.editor"
        const val TAG = "GradenTranslator"
    }
}

internal object TextFilter {
    private val dynamic = Regex(
        """(?i)(/storage/|content://|https?://|\.(?:mp4|mov|mkv|png|jpe?g|webp|wav|mp3|m4a)|\d{1,2}:\d{2})"""
    )

    // High-confidence static labels extracted from Graden v0.5.0-beta's Compose UI source.
    // A strict allowlist is the default so project names, filenames and user-entered text are not
    // sent to the translation provider merely because they happen to be short English phrases.
    private val knownUiStrings = setOf(
        "%.2f× real time · %s",
        "%d%%",
        "A line break starts the second line on screen.",
        "Add music model",
        "Add speaker model from storage",
        "Apply to all cuts",
        "Auto shows nits for HDR and 10-bit levels for SDR.",
        "Cancel",
        "Captions need a phone with an ARM processor.",
        "Color",
        "Crossfade sound",
        "Custom",
        "Done",
        "Drag the line for level · the corners for fades",
        "Drag the text on the picture to move it; pinch to resize, twist to turn.",
        "Drag to scroll · pinch to zoom · tap a clip to trim it",
        "Drag to scrub · tap a clip to edit it",
        "Duration",
        "Edit caption",
        "Get a speech recognition model, or add one from storage, to begin.",
        "Graded",
        "Graden",
        "Hold a handle and slide below the strip for frame-accurate control.",
        "Hold a project for more",
        "Import",
        "Keeps these export settings for any project.",
        "Layout",
        "Lines after the first",
        "Move the playhead onto a clip",
        "Mute it",
        "New project",
        "No audio found",
        "No clip under the playhead",
        "No projects yet",
        "Nothing under the playhead",
        "Nothing was saved.",
        "Open Media (folder icon) and drag a clip here",
        "Open the phone or make the window bigger to edit",
        "Original",
        "Packing the project with its media…",
        "Pick a shape, size and frame rate",
        "Pitch is kept. Smooth slow motion (frame blending, optical flow) is coming.",
        "Profile",
        "Project saved",
        "Proxy",
        "Quality",
        "Recent",
        "Remove",
        "Remove from phone",
        "Reset this clip's sound",
        "Save",
        "Save preset",
        "Saved",
        "Select the clip at the playhead",
        "Set",
        "Shot in",
        "Size",
        "Sound",
        "Sound timeline",
        "Speed",
        "Spoken language",
        "Start one above: your edits will appear here",
        "Swap sides",
        "Tap a clip on the timeline for its tools.",
        "Tap the colour to select",
        "That isn't a number",
        "The colour space of this clip's file. Auto reads it from the file.",
        "Tools",
        "Type here",
        "Your projects",
        "project",
        "%.0f fps · %s",
        "%.2f – %.2f",
        "%.2fs",
        "A creative look: the picture stays in the space it was in",
        "Add",
        "Add a LUT",
        "Add to this project",
        "All set",
        "Analyse",
        "Analyse again",
        "Analyse clip",
        "Analysing…",
        "Analysis resolution",
        "As recorded",
        "Auto",
        "Balanced: the default, right for most footage",
        "Blur",
        "Built in · for gaps and fades",
        "Camera",
        "Camera motion",
        "Camera preset",
        "Can't open · tap to dismiss",
        "Cinema DOF",
        "Clip",
        "Colour Space Transform",
        "Colour space",
        "Colours outside the target are clipped",
        "Colours outside the target are eased in",
        "Coming soon",
        "Converts to",
        "Corrected",
        "Correction",
        "Crop",
        "Data levels",
        "Defaults",
        "Deflicker",
        "Develop again",
        "Developed",
        "Drag one in to use it here too",
        "Drag to scrub",
        "Every frame is tracked; this happens once per clip",
        "FINE",
        "Film look",
        "Finer detail and smaller shake followed; about %.1f× slower to analyse",
        "Follow what the clip is shot in",
        "From other projects",
        "From the input gamma",
        "From the output gamma",
        "Full",
        "Gamma",
        "Gamut mapping",
        "Getting everything ready",
        "Getting your clips…",
        "HDR master peak",
        "Hold and drag it onto the timeline",
        "Hue",
        "Import .cube",
        "Importing",
        "Input",
        "Input colour space",
        "Input gamma",
        "It leaves the LUT library on this phone. Clips using it lose it.",
        "Keep",
        "Keep Graden open while your clips get ready.",
        "Keeps the picture level all through",
        "LUTs",
        "LUTs, applied top to bottom",
        "Layer on",
        "Lens",
        "Limiter on",
        "Lock horizon",
        "Log / gamma",
        "Look",
        "Luminance Mapping",
        "Mapping",
        "Maps brightness and keeps colours as saturated as possible",
        "Maps brightness, keeps hue; very bright colours ease to white",
        "Max input",
        "Max input luminance",
        "Max output",
        "Max output luminance",
        "Media",
        "Move down",
        "Move up",
        "Neutral",
        "No tone mapping: light beyond the output is clipped",
        "Noise",
        "None",
        "Not MCRAW",
        "Nothing imported yet.",
        "Off",
        "On",
        "Order: this transform, then the correction, then the LUTs, then into the timeline output.",
        "Output",
        "Output colour space",
        "Output gamma",
        "Output tone mapping",
        "Pick a LUT on the left to adjust it.",
        "Pixel",
        "Press Analyse to measure the clip's motion first. Stabilizing starts once it is measured.",
        "Project",
        "Quicker (about %.1f× as fast), for steady or simple shots",
        "Real size of motion · drag to look around, double-tap to reset",
        "Remove from library",
        "Remove from project",
        "Reset",
        "Rolls the brightest channel off; quick and neutral",
        "Runs once per file on this phone's NPU",
        "Same as input (a look)",
        "Same black for all",
        "Saturation Preserving",
        "Secondaries",
        "Simple",
        "Smooth filmic roll-off with natural highlight desaturation",
        "Sort by",
        "Spatial",
        "Stabilize",
        "Starting",
        "Strength",
        "Tap to add at the playhead, or hold and drag",
        "Temporal",
        "The clip is gone",
        "The clip's motion is measured. Press Stabilize to apply it.",
        "The project's working space",
        "The usual HDR10 master",
        "This phone has no supported NPU, so analysis runs on the CPU and takes longer",
        "This recording says its black level is 0",
        "Timeline output",
        "Tone mapping",
        "Touch the line to add a point · drag to shape · double tap a point to remove",
        "Video",
        "What this LUT converts to",
        "Working",
        "Working space",
        "centre %.0f°  ·  width %.0f°"
    )

    fun isUiText(source: String, allowUnknown: Boolean = false): Boolean {
        val text = source.trim()
        if (text.length !in 2..120) return false
        if ('\n' in text || '\r' in text) return false
        if (dynamic.containsMatchIn(text)) return false
        if (text.count { it.isLetter() } < 2) return false
        if (text.matches(Regex("""[\p{Punct}\d\s]+"""))) return false
        return text in knownUiStrings || allowUnknown
    }
}

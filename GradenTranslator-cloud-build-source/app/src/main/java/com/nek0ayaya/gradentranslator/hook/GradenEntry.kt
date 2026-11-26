package com.nek0ayaya.gradentranslator.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Modifier

class GradenEntry : XposedModule() {
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != TARGET || param.processName != TARGET) return
        try {
            val cl = param.classLoader
            val candidates = listOf(
                "androidx.compose.material3.TextKt",
                "androidx.compose.material.TextKt",
                "androidx.compose.foundation.text.BasicTextKt"
            )
            var hooked = 0
            candidates.forEach { name ->
                val clazz = runCatching { cl.loadClass(name) }.getOrNull() ?: return@forEach
                clazz.declaredMethods.filter { it.name == "Text" || it.name == "BasicText" }.forEach methodLoop@{ method ->
                    if (!Modifier.isStatic(method.modifiers)) return@methodLoop
                    val index = method.parameterTypes.indexOfFirst { it == String::class.java }
                    if (index < 0) return@methodLoop
                    runCatching {
                        hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                            val args = chain.args.toTypedArray()
                            val original = args.getOrNull(index) as? String
                            if (original != null && TextFilter.isUiText(original)) {
                                val translated = Translator.translateCached(original)
                                if (translated != original) args[index] = translated
                            }
                            chain.proceed(args)
                        }
                        hooked++
                    }.onFailure { log(Log.WARN, TAG, "Hook failed: $method : $it") }
                }
            }
            log(Log.INFO, TAG, "Graden Compose candidate hooks installed: $hooked")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Hook initialization failed", t)
        }
    }

    companion object {
        const val TARGET = "com.graden.editor"
        const val TAG = "GradenTranslator"
    }
}

internal object TextFilter {
    private val dynamic = Regex("""(?i)(/storage/|content://|https?://|\\.mp4|\\.mov|\\.mkv|\\.png|\\.jpg|\\d{1,2}:\\d{2})""")
    fun isUiText(s: String): Boolean {
        val t = s.trim()
        if (t.length !in 2..120 || t.any { it == '\\n' } || dynamic.containsMatchIn(t)) return false
        if (t.count { it.isLetter() } < 2) return false
        if (t.matches(Regex("""[\\p{Punct}\\d\\s]+"""))) return false
        return true
    }
}

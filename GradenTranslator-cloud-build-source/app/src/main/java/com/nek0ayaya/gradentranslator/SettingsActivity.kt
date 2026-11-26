package com.nek0ayaya.gradentranslator

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.*

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = getSharedPreferences("translator", MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        fun field(label: String, key: String, hint: String = ""): EditText {
            root.addView(TextView(this).apply { text = label })
            return EditText(this).apply { this.hint = hint; setText(p.getString(key, "")); root.addView(this) }
        }
        root.addView(TextView(this).apply { text = "GradenTranslator · 开发版\n配置 OpenAI Chat Completions 兼容接口" })
        val base = field("API Base URL", "base", "https://api.openai.com/v1")
        val model = field("模型 ID", "model", "gpt-4o-mini")
        val key = field("API Key", "key", "sk-...").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        root.addView(Button(this).apply { text = "保存配置"; setOnClickListener {
            p.edit().putString("base", base.text.toString().trim())
                .putString("model", model.text.toString().trim())
                .putString("key", key.text.toString().trim()).apply()
            Toast.makeText(this@SettingsActivity, "配置已保存", Toast.LENGTH_SHORT).show()
        } })
        root.addView(TextView(this).apply { text = "\n启用方式：在 LSPosed 中勾选 Graden，并重启 Graden。\n开发版尚未完成最终 APK DEX 验证，暂不保证所有文本均可翻译。" })
        setContentView(ScrollView(this).apply { addView(root) })
    }
}

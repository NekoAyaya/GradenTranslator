package com.nek0ayaya.gradentranslator

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity(), GradenTranslatorApp.ConnectionListener {

    private lateinit var baseUrl: EditText
    private lateinit var endpointPath: EditText
    private lateinit var modelId: EditText
    private lateinit var apiKey: EditText
    private lateinit var targetLanguage: EditText
    private lateinit var batchSize: EditText
    private lateinit var timeoutSeconds: EditText
    private lateinit var translateUnknownText: CheckBox
    private lateinit var status: TextView
    private lateinit var connectionStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val draft = GradenTranslatorApp.loadDraft(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "GradenTranslator"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "LSPosed Compose 自动汉化 · 开发版 0.2.0"
            textSize = 13f
            setPadding(0, dp(4), 0, dp(12))
        })

        connectionStatus = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(6), 0, dp(14))
        }
        root.addView(connectionStatus)

        baseUrl = addField(root, "API Base URL", draft.baseUrl, "https://api.openai.com/v1")
        endpointPath = addField(root, "Chat Completions 路径", draft.endpointPath, "/chat/completions")
        modelId = addField(root, "模型 ID", draft.modelId, "gpt-4o-mini")
        apiKey = addField(
            root,
            "API Key",
            draft.apiKey,
            "输入服务商提供的 Key",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        targetLanguage = addField(root, "目标语言", draft.targetLanguage, "简体中文")
        batchSize = addField(
            root,
            "批量文本数（1–20）",
            draft.batchSize.toString(),
            "12",
            InputType.TYPE_CLASS_NUMBER
        )
        timeoutSeconds = addField(
            root,
            "请求超时秒数（8–120）",
            draft.timeoutSeconds.toString(),
            "25",
            InputType.TYPE_CLASS_NUMBER
        )

        translateUnknownText = CheckBox(this).apply {
            text = "翻译未知短文本（可能包含项目标题、文件名等动态内容）"
            textSize = 13f
            isChecked = draft.translateUnknownText
            setPadding(0, dp(8), 0, dp(8))
            root.addView(this)
        }

        root.addView(TextView(this).apply {
            text = "说明：Base URL 填 API 根地址，例如 https://api.openai.com/v1；请求路径单独填写 /chat/completions。API Key 保存在模块应用私有设置中，并通过 LSPosed RemotePreferences 交给目标进程读取。"
            textSize = 12f
            setPadding(0, dp(10), 0, dp(14))
        })

        root.addView(Button(this).apply {
            text = "保存并同步到 LSPosed"
            setOnClickListener { saveConfig() }
        })

        root.addView(Button(this).apply {
            text = "测试 API"
            setOnClickListener { testApi() }
        })

        status = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(status)

        root.addView(TextView(this).apply {
            text = "默认只翻译从 Graden 0.5.0-beta 源码识别出的固定 UI 文案，降低误翻译项目标题、文件名和用户内容的风险。勾选“翻译未知短文本”后会启用短文本启发式识别，但可能把动态内容也发送给 API。翻译在后台按批次请求，结果缓存到 SQLite，并尝试刷新对应 Compose 区域。"
            textSize = 12f
            setPadding(0, dp(18), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }

    override fun onResume() {
        super.onResume()
        GradenTranslatorApp.addConnectionListener(this)
    }

    override fun onPause() {
        GradenTranslatorApp.removeConnectionListener(this)
        super.onPause()
    }

    override fun onFrameworkConnectionChanged(connected: Boolean) {
        runOnUiThread {
            connectionStatus.text = if (connected) {
                "LSPosed 服务：已连接 ✓"
            } else {
                "LSPosed 服务：未连接。请确认模块已启用，然后重新打开本设置页。"
            }
        }
    }

    private fun saveConfig() {
        val config = readConfig() ?: return
        status.text = "正在保存并同步配置……"
        Thread {
            val result = runCatching {
                GradenTranslatorApp.saveConfiguration(this, config)
            }.getOrDefault(false)
            runOnUiThread {
                status.text = if (result) {
                    "配置已保存，并已同步到 LSPosed。重启 Graden 后生效。"
                } else {
                    "本机草稿已保存，但 LSPosed 服务尚未连接，配置还没有同步到 Graden。请检查 LSPosed 模块状态后重新打开设置页并保存。"
                }
                Toast.makeText(
                    this,
                    if (result) "配置同步成功" else "已保存草稿，等待 LSPosed 服务",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun testApi() {
        val config = readConfig() ?: return
        status.text = "正在请求模型翻译测试文本……"
        Thread {
            val result = runCatching {
                OpenAiApiClient.translateSingle(config, "New project")
            }
            runOnUiThread {
                status.text = result.fold(
                    onSuccess = { "API 测试成功：New project → $it" },
                    onFailure = { "API 测试失败：${it.message ?: "未知错误"}" }
                )
            }
        }.start()
    }

    private fun readConfig(): TranslationConfig? {
        val config = TranslationConfig(
            baseUrl = baseUrl.text.toString(),
            endpointPath = endpointPath.text.toString(),
            modelId = modelId.text.toString(),
            apiKey = apiKey.text.toString(),
            targetLanguage = targetLanguage.text.toString(),
            batchSize = batchSize.text.toString().toIntOrNull() ?: 12,
            timeoutSeconds = timeoutSeconds.text.toString().toIntOrNull() ?: 25,
            translateUnknownText = translateUnknownText.isChecked
        )
        return runCatching { config.validated() }.getOrElse {
            status.text = it.message ?: "配置格式不正确"
            null
        }
    }

    private fun addField(
        root: LinearLayout,
        label: String,
        value: String,
        hint: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT
    ): EditText {
        root.addView(TextView(this).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(7), 0, 0)
        })
        return EditText(this).apply {
            setText(value)
            this.hint = hint
            this.inputType = inputType
            singleLine = true
            textSize = 15f
            root.addView(
                this,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

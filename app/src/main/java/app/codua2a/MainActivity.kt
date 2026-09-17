package app.codua2a

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {
    private val ink = Color.rgb(28, 40, 49)
    private val teal = Color.rgb(18, 111, 100)
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var stop: Button
    private lateinit var scroll: ScrollView
    private lateinit var transcript: LinearLayout
    private val actions = mutableListOf<Button>()
    private val rendered = mutableListOf<Pair<AgentRuntime.Message, TextView>>()
    private var approvalDialog: AlertDialog? = null
    private var approvalId = -1
    private var exportFile: File? = null
    private var importing = false
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(243, 247, 246)
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        exportFile = savedInstanceState?.getString("exportFile")?.let { File(it) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            setBackgroundColor(Color.rgb(243, 247, 246))
        }
        root.addView(TextView(this).apply { text = "Codua2a"; textSize = 27f; setTextColor(ink); setTypeface(null, Typeface.BOLD) })
        status = TextView(this).apply { textSize = 12f; setTextColor(teal); setPadding(0, dp(4), 0, dp(8)) }
        root.addView(status)
        val toolbar = LinearLayout(this)
        fun action(label: String, block: () -> Unit) {
            val button = Button(this).apply { text = label; textSize = 12f; isAllCaps = false; setPadding(0, 0, 0, 0); setOnClickListener { block() } }
            toolbar.addView(button, LinearLayout.LayoutParams(0, dp(44), 1f)); actions.add(button)
        }
        action("新会话") { AgentRuntime.send(JSONObject().put("action", "new")) }
        action("历史") { AgentRuntime.send(JSONObject().put("action", "sessions")) }
        action("文件") { files() }
        action("设置") { settings() }
        root.addView(toolbar)
        transcript = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(transcript) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        input = EditText(this).apply {
            hint = "描述任务，或先导入要处理的文件…"
            textSize = 16f; setTextColor(ink)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2; maxLines = 5; gravity = Gravity.TOP
            background = shape(Color.WHITE); setPadding(dp(12), dp(10), dp(12), dp(10))
            setText(AgentRuntime.draft)
        }
        root.addView(input, LinearLayout.LayoutParams(-1, -2))
        val controls = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        controls.addView(TextView(this).apply { text = "文件更改需确认"; textSize = 11f; setTextColor(Color.GRAY) }, LinearLayout.LayoutParams(0, -2, 1f))
        stop = Button(this).apply { text = "停止"; setOnClickListener { AgentRuntime.send(JSONObject().put("action", "cancel")) } }
        send = Button(this).apply {
            text = "发送"; setTextColor(teal)
            setOnClickListener {
                if (!AgentRuntime.configured) settings()
                else if (AgentRuntime.submit(input.text.toString().trim())) { input.text.clear(); AgentRuntime.draft = "" }
            }
        }
        controls.addView(stop); controls.addView(send); root.addView(controls)
        setContentView(root)
        AgentRuntime.start(applicationContext)
    }

    override fun onStart() { super.onStart(); AgentRuntime.listener = { render() }; render() }
    override fun onStop() {
        AgentRuntime.draft = input.text.toString()
        AgentRuntime.listener = null
        approvalDialog?.dismiss(); approvalDialog = null; approvalId = -1
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("exportFile", exportFile?.absolutePath)
        outState.putString("draft", input.text.toString())
        super.onSaveInstanceState(outState)
    }
    override fun onRestoreInstanceState(state: Bundle) {
        super.onRestoreInstanceState(state); input.setText(state.getString("draft", AgentRuntime.draft))
    }

    private fun render() {
        status.text = AgentRuntime.status
        send.isEnabled = AgentRuntime.ready && !AgentRuntime.busy && !importing
        stop.isEnabled = AgentRuntime.busy
        actions.forEach { it.isEnabled = AgentRuntime.ready && !AgentRuntime.busy && !importing }
        val keepBottom = transcript.height - (scroll.scrollY + scroll.height) < dp(100)
        if (rendered.size > AgentRuntime.messages.size || rendered.indices.any { rendered[it].first !== AgentRuntime.messages[it] }) {
            rendered.clear(); transcript.removeAllViews()
        }
        if (AgentRuntime.messages.isEmpty()) {
            if (transcript.childCount == 0) transcript.addView(TextView(this).apply {
                text = "你的随身代码助手\n\n在设置中填写模型服务地址和密钥，然后开始对话。\n\n通过“文件”导入文档，助手可以读取、编辑并保存到应用工作区。"
                textSize = 16f; setTextColor(Color.rgb(99, 117, 120)); setPadding(dp(8), dp(40), dp(8), dp(24))
            })
        } else {
            if (rendered.isEmpty()) transcript.removeAllViews()
            AgentRuntime.messages.forEachIndexed { index, message ->
                if (index >= rendered.size) {
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        background = shape(if (message.role == "你") Color.rgb(224, 239, 234) else Color.WHITE)
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                    }
                    card.addView(TextView(this).apply {
                        text = message.role; textSize = 12f; setTextColor(teal); setTypeface(null, Typeface.BOLD)
                    })
                    val body = TextView(this).apply {
                        textSize = 15f; setTextColor(ink); setTextIsSelectable(true)
                        setPadding(0, dp(6), 0, 0); setLineSpacing(dp(3).toFloat(), 1f)
                    }
                    card.addView(body)
                    transcript.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
                    rendered.add(message to body)
                }
                if (rendered[index].second.text.toString() != message.text) rendered[index].second.text = message.text
            }
        }
        if (keepBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        AgentRuntime.sessions?.let { items ->
            AgentRuntime.sessions = null
            if (items.length() == 0) toast("还没有保存的会话")
            else AlertDialog.Builder(this).setTitle("历史会话")
                .setItems(Array(items.length()) { items.getJSONObject(it).optString("title").take(70) }) { _, n ->
                    AgentRuntime.send(JSONObject().put("action", "load").put("id", items.getJSONObject(n).getString("id")))
                }.setNegativeButton("关闭", null).show()
        }
        val request = AgentRuntime.pendingApproval
        if (request == null) { approvalDialog?.dismiss(); approvalDialog = null; approvalId = -1 }
        else if (request.optInt("id") != approvalId) {
            approvalDialog?.dismiss(); approvalId = request.optInt("id")
            val body = TextView(this).apply {
                text = request.optJSONObject("input")?.toString(2).orEmpty().take(18000)
                setPadding(dp(20), dp(12), dp(20), dp(12)); setTextIsSelectable(true)
            }
            approvalDialog = AlertDialog.Builder(this).setTitle("允许 ${request.optString("name")}？")
                .setView(ScrollView(this).apply { addView(body) }).setCancelable(false)
                .setPositiveButton("允许") { _, _ -> AgentRuntime.approve(true) }
                .setNegativeButton("拒绝") { _, _ -> AgentRuntime.approve(false) }.show()
        }
    }

    private fun settings() {
        val store = SecureSettings(this)
        val old = try { store.load() } catch (_: Exception) { null }
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fields.addView(TextView(this).apply { text = "使用 Anthropic Messages 兼容接口。地址填写服务根路径，程序自动追加 /v1/messages。"; textSize = 13f })
        fun field(label: String, value: String, secret: Boolean = false): EditText {
            fields.addView(TextView(this).apply { text = label; setPadding(0, dp(12), 0, 0) })
            return EditText(this).apply {
                setSingleLine(true); textSize = 15f
                inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setText(value); fields.addView(this)
            }
        }
        val endpoint = field("服务地址（HTTPS）", old?.optString("base_url") ?: "https://api.anthropic.com")
        val model = field("模型 ID", old?.optString("model").orEmpty())
        val token = field("API 密钥（在设备上加密保存）", old?.optString("api_key").orEmpty(), true)
        val bearer = CheckBox(this).apply { text = "使用 Bearer 认证"; isChecked = old?.optString("auth_style") == "bearer" }
        fields.addView(bearer)
        val dialog = AlertDialog.Builder(this).setTitle("模型设置")
            .setView(ScrollView(this).apply { addView(fields) }).setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val base = endpoint.text.toString().trim().trimEnd('/')
                val uri = android.net.Uri.parse(base)
                when {
                    uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null -> endpoint.error = "请填写有效 HTTPS 根地址"
                    base.endsWith("/v1/messages") -> endpoint.error = "请移除末尾的 /v1/messages"
                    model.text.isBlank() -> model.error = "填写模型 ID"
                    token.text.isBlank() -> token.error = "填写 API 密钥"
                    else -> try {
                        val config = JSONObject().put("base_url", base).put("model", model.text.toString().trim())
                            .put("api_key", token.text.toString().trim()).put("auth_style", if (bearer.isChecked) "bearer" else "x-api-key")
                        store.save(config)
                        if (AgentRuntime.send(JSONObject().put("action", "configure").put("config", config))) dialog.dismiss()
                    } catch (error: Exception) { toast("保存失败：${error.message}") }
                }
            }
        }
        dialog.show()
    }

    private fun workspace() = File(filesDir, "runtime/workspace").apply { mkdirs() }
    private fun files() {
        val root = workspace().canonicalFile
        val entries = root.walkTopDown().onEnter { it.canonicalPath.startsWith(root.path + File.separator) || it.canonicalFile == root }
            .filter { it.isFile && it.canonicalPath.startsWith(root.path + File.separator) }.take(300).toList()
        AlertDialog.Builder(this).setTitle("工作区文件")
            .setItems((listOf("＋ 从设备导入文件") + entries.map { it.relativeTo(root).path }).toTypedArray()) { _, index ->
                if (index == 0) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                }, 10)
                else {
                    exportFile = entries[index - 1]
                    AlertDialog.Builder(this).setTitle(exportFile!!.name)
                        .setItems(arrayOf("导出到设备", "将文件名填入消息")) { _, option ->
                            if (option == 0) startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"
                                putExtra(Intent.EXTRA_TITLE, exportFile!!.name)
                            }, 11)
                            else input.append(" ${entries[index - 1].relativeTo(root).path} ")
                        }.show()
                }
            }.setNegativeButton("关闭", null).show()
    }

    @Deprecated("Platform activity result bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK || requestCode !in listOf(10, 11)) return
        importing = true; render()
        val export = exportFile
        Thread {
            var temporary: File? = null
            val message = try {
                if (requestCode == 10) {
                    var name = "imported.txt"
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
                    }
                    name = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(120)
                    if (name.isBlank() || name == "." || name == "..") name = "imported.txt"
                    val root = workspace()
                    var output = File(root, name)
                    if (output.exists()) output = File(root, "${System.currentTimeMillis()}_$name")
                    temporary = File.createTempFile("import-", ".tmp", cacheDir)
                    contentResolver.openInputStream(uri).use { source ->
                        checkNotNull(source) { "无法读取文件" }
                        temporary.outputStream().use { target ->
                            val buffer = ByteArray(8192); var total = 0
                            while (true) {
                                val n = source.read(buffer); if (n < 0) break
                                total += n; check(total <= 16 * 1024 * 1024) { "文件超过 16 MiB" }
                                target.write(buffer, 0, n)
                            }
                        }
                    }
                    check(temporary.renameTo(output)) { "无法保存导入文件" }
                    "已导入：${output.name}"
                } else {
                    checkNotNull(export)
                    check(export.canonicalPath.startsWith(workspace().canonicalPath + File.separator))
                    contentResolver.openOutputStream(uri, "wt").use { target ->
                        checkNotNull(target); export.inputStream().use { it.copyTo(target) }
                    }
                    "已导出：${export.name}"
                }
            } catch (error: Exception) { "文件操作失败：${error.message}" }
            finally { temporary?.delete() }
            runOnUiThread { importing = false; toast(message); if (!isDestroyed) render() }
        }.start()
    }

    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
}

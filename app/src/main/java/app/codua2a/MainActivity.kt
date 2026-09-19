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
    private lateinit var send: ImageButton
    private lateinit var stop: ImageButton
    private lateinit var scroll: ScrollView
    private lateinit var transcript: LinearLayout
    private lateinit var attachmentButton: ImageButton
    private lateinit var attachmentCount: TextView
    private lateinit var modelSelector: TextView
    private lateinit var attachmentPanel: LinearLayout
    private lateinit var page: LinearLayout
    private val attachmentActions = mutableListOf<View>()
    private var pendingCapture: android.net.Uri? = null
    private val actions = mutableListOf<ImageButton>()
    private val rendered = mutableListOf<Pair<AgentRuntime.Message, TextView>>()
    private var approvalDialog: AlertDialog? = null
    private var approvalId = -1
    private var exportFile: File? = null
    private var importing = false
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }
    private fun iconButton(icon: String, label: String, filled: Boolean = false, block: () -> Unit) = ImageButton(this).apply {
        setImageDrawable(ChatIcon(icon, if (filled) Color.WHITE else ink))
        contentDescription = label; tooltipText = label
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.argb(35, 18, 111, 100)),
            shape(if (filled) teal else Color.TRANSPARENT), shape(Color.WHITE))
        setOnClickListener { block() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(243, 247, 246)
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        exportFile = savedInstanceState?.getString("exportFile")?.let { File(it) }
        pendingCapture = savedInstanceState?.getString("pendingCapture")?.let { android.net.Uri.parse(it) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Accept focus when dismissing the composer so EditText cannot reclaim it.
            isFocusableInTouchMode = true
            setPadding(dp(12), dp(6), dp(12), dp(8))
            setBackgroundColor(Color.rgb(243, 247, 246))
        }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val history = iconButton("history", "历史会话") { AgentRuntime.send(JSONObject().put("action", "sessions")) }
        toolbar.addView(history, LinearLayout.LayoutParams(dp(48), dp(48))); actions.add(history)
        toolbar.addView(Space(this), LinearLayout.LayoutParams(dp(48), dp(1)))
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(2), 0, dp(2), 0) }
        modelSelector = TextView(this).apply {
            text = AgentRuntime.modelName + " ⌄"; textSize = 17f; setTextColor(ink); setTypeface(null, Typeface.BOLD)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
            minHeight = dp(48); contentDescription = "选择模型"; tooltipText = "选择模型"
            background = getDrawable(android.R.drawable.list_selector_background)
            setOnClickListener { chooseModel() }
        }
        heading.addView(modelSelector, LinearLayout.LayoutParams(-1, -2))
        status = TextView(this).apply {
            textSize = 11f; setTextColor(teal); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        toolbar.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        val newChat = iconButton("new", "新会话") {
            if (AgentRuntime.send(JSONObject().put("action", "new"))) { AgentRuntime.attachments.clear(); render() }
        }
        toolbar.addView(newChat, LinearLayout.LayoutParams(dp(48), dp(48))); actions.add(newChat)
        val more = iconButton("more", "更多选项") { }
        more.setOnClickListener { showMore(more) }
        toolbar.addView(more, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(toolbar)
        status.gravity = Gravity.CENTER
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        transcript = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(transcript) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(Color.WHITE).apply { setStroke(dp(1), Color.rgb(222, 231, 227)) }
            setPadding(dp(6), dp(4), dp(6), dp(6))
        }
        input = EditText(this).apply {
            hint = "问点什么，或描述一个任务…"
            textSize = 16f; setTextColor(ink)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 1; maxLines = 5; gravity = Gravity.TOP
            background = null; setPadding(dp(10), dp(10), dp(10), dp(8))
            setText(AgentRuntime.draft)
            setOnFocusChangeListener { _, focused -> if (focused && ::attachmentPanel.isInitialized) setAttachmentsExpanded(false) }
        }
        composer.addView(input, LinearLayout.LayoutParams(-1, -2))
        val controls = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        attachmentButton = iconButton("plus", "添加图片或文件") { showAttachments() }
        controls.addView(attachmentButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        attachmentCount = TextView(this).apply {
            textSize = 12f; setTextColor(teal); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener {
                if (AgentRuntime.attachments.isNotEmpty() && !AgentRuntime.busy && !importing) {
                    AlertDialog.Builder(this@MainActivity).setTitle("图片附件")
                        .setMessage("已选择 ${AgentRuntime.attachments.size} 张图片")
                        .setPositiveButton("清空图片") { _, _ -> AgentRuntime.attachments.clear(); render() }
                        .setNegativeButton("取消", null).show()
                }
            }
        }
        controls.addView(attachmentCount, LinearLayout.LayoutParams(0, -2, 1f))
        stop = iconButton("stop", "停止任务", true) { AgentRuntime.send(JSONObject().put("action", "cancel")) }
        send = iconButton("send", "发送消息", true) {
            if (!AgentRuntime.configured) settings()
            else if (AgentRuntime.submit(input.text.toString().trim())) {
                dismissInputKeyboard()
                input.text.clear(); AgentRuntime.draft = ""
                setAttachmentsExpanded(false)
            }
        }
        controls.addView(stop, LinearLayout.LayoutParams(dp(48), dp(48)))
        controls.addView(send, LinearLayout.LayoutParams(dp(48), dp(48)))
        composer.addView(controls); root.addView(composer)
        page = root
        attachmentPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, dp(8)); visibility = View.GONE
        }
        fun attachmentCard(icon: String, title: String, block: () -> Unit) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = shape(Color.rgb(233, 239, 236)); minimumHeight = dp(96)
                contentDescription = title; isFocusable = true; setPadding(dp(8), dp(16), dp(8), dp(16))
                addView(ImageView(this@MainActivity).apply { setImageDrawable(ChatIcon(icon, ink)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(28), dp(28)))
                addView(TextView(this@MainActivity).apply { text = title; textSize = 14f; gravity = Gravity.CENTER; setTextColor(ink); setPadding(0, dp(8), 0, 0); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
                setOnClickListener { if (!importing && !AgentRuntime.busy) block() }
            }
            attachmentPanel.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
            attachmentActions.add(card)
        }
        attachmentCard("camera", "拍照") { capturePhoto() }
        attachmentCard("image", "相册") {
            if (AgentRuntime.attachments.size >= 4) toast("最多 4 张图片")
            else openAttachment(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }, 12)
        }
        attachmentCard("file", "文件") {
            openAttachment(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, 10)
        }
        root.addView(attachmentPanel)
        if (savedInstanceState?.getBoolean("attachmentsExpanded") == true) setAttachmentsExpanded(true, false)
        setContentView(root)
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            && !getPreferences(MODE_PRIVATE).getBoolean("notificationRequested", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationRequested", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 20)
        }
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
        outState.putString("pendingCapture", pendingCapture?.toString())
        outState.putBoolean("attachmentsExpanded", attachmentPanel.visibility == View.VISIBLE)
        super.onSaveInstanceState(outState)
    }
    override fun onRestoreInstanceState(state: Bundle) {
        super.onRestoreInstanceState(state); input.setText(state.getString("draft", AgentRuntime.draft))
    }

    private fun render() {
        status.text = AgentRuntime.status
        val idle = AgentRuntime.ready && !AgentRuntime.busy && !AgentRuntime.modelChanging && !importing && pendingCapture == null
        modelSelector.text = AgentRuntime.modelName + " ⌄"
        modelSelector.contentDescription = "选择模型，当前：${AgentRuntime.modelName}"
        modelSelector.isEnabled = idle; modelSelector.alpha = if (idle) 1f else 0.5f
        send.isEnabled = idle
        send.alpha = if (send.isEnabled) 1f else 0.4f
        send.visibility = if (AgentRuntime.busy) View.GONE else View.VISIBLE
        stop.visibility = if (AgentRuntime.busy) View.VISIBLE else View.GONE
        stop.isEnabled = AgentRuntime.busy
        attachmentCount.text = if (importing) "正在导入…" else if (AgentRuntime.attachments.isEmpty()) "" else "${AgentRuntime.attachments.size} 张图片待发送"
        attachmentButton.isEnabled = idle
        attachmentActions.forEach { it.isEnabled = idle; it.alpha = if (idle) 1f else 0.4f }
        actions.forEach { it.isEnabled = idle; it.alpha = if (it.isEnabled) 1f else 0.4f }
        attachmentButton.alpha = if (attachmentButton.isEnabled) 1f else 0.4f
        val keepBottom = transcript.height - (scroll.scrollY + scroll.height) < dp(100)
        if (rendered.size > AgentRuntime.messages.size || rendered.indices.any { rendered[it].first !== AgentRuntime.messages[it] }) {
            rendered.clear(); transcript.removeAllViews()
        }
        if (AgentRuntime.messages.isEmpty()) {
            if (transcript.childCount == 0) transcript.addView(TextView(this).apply {
                text = "今天想完成什么？\n\n用文字开始，或点 ＋ 添加图片和文件。\n点击顶部模型名称切换模型。"
                textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.rgb(99, 117, 120)); setPadding(dp(8), dp(72), dp(8), dp(24))
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
                val body = rendered[index].second
                if (body.tag != message.text) {
                    body.tag = message.text
                    body.text = if (message.role == "codua") NativeMarkdown.render(message.text) else message.text
                    body.movementMethod = android.text.method.LinkMovementMethod.getInstance()
                }
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

    private fun showMore(anchor: View) {
        val idle = AgentRuntime.ready && !AgentRuntime.busy && !AgentRuntime.modelChanging && !importing
        PopupMenu(this, anchor, Gravity.END).apply {
            menu.add(0, 1, 0, "工作区文件").isEnabled = idle
            menu.add(0, 2, 1, "模型设置").isEnabled = idle
            menu.add(0, 3, 2, "工具与 MCP").isEnabled = idle
            menu.add(0, 4, 3, "运行状态")
            setOnMenuItemClickListener {
                // Check again in case runtime state changed after opening the menu.
                if (it.itemId != 4 && (!AgentRuntime.ready || AgentRuntime.busy || importing)) return@setOnMenuItemClickListener true
                when (it.itemId) {
                    1 -> files()
                    2 -> manageModels()
                    3 -> toolSettings()
                    4 -> AlertDialog.Builder(this@MainActivity).setTitle("运行状态")
                        .setMessage(AgentRuntime.status + "\n\n" + AgentRuntime.mcpStatus).setPositiveButton("关闭", null).show()
                }
                true
            }
            show()
        }
    }

    private fun dismissInputKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        page.requestFocus()
    }

    private fun showAttachments() {
        val expand = attachmentPanel.visibility != View.VISIBLE
        if (expand) dismissInputKeyboard()
        setAttachmentsExpanded(expand)
    }

    private fun setAttachmentsExpanded(expanded: Boolean, animate: Boolean = true) {
        if (animate && page.isLaidOut) android.transition.TransitionManager.beginDelayedTransition(page,
            android.transition.AutoTransition().apply { duration = 160 })
        attachmentPanel.visibility = if (expanded) View.VISIBLE else View.GONE
        attachmentButton.setImageDrawable(ChatIcon(if (expanded) "close" else "plus", ink))
        attachmentButton.contentDescription = if (expanded) "收起附件选项" else "添加图片或文件"
        attachmentButton.tooltipText = attachmentButton.contentDescription
    }

    @Deprecated("Platform back bridge")
    override fun onBackPressed() {
        if (attachmentPanel.visibility == View.VISIBLE) setAttachmentsExpanded(false) else super.onBackPressed()
    }

    private fun openAttachment(intent: Intent, request: Int) {
        try { startActivityForResult(intent, request); setAttachmentsExpanded(false) }
        catch (_: android.content.ActivityNotFoundException) { toast("设备没有可用的应用来完成此操作") }
    }

    private fun capturePhoto() {
        if (AgentRuntime.attachments.size >= 4) { toast("最多 4 张图片"); return }
        try {
            val uri = CaptureProvider.create(this); pendingCapture = uri
            val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                clipData = android.content.ClipData.newRawUri("photo", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            startActivityForResult(intent, 13); setAttachmentsExpanded(false)
        } catch (_: Exception) {
            pendingCapture?.let { releaseCapture(it) }; pendingCapture = null
            toast("无法打开系统相机，请使用相册选择图片")
        }
    }

    private fun releaseCapture(uri: android.net.Uri) {
        revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching { CaptureProvider.file(this, uri).delete() }
    }

    private fun chooseModel() {
        try {
            val store = SecureSettings(this)
            val config = store.load() ?: JSONObject()
            val profiles = ModelProfiles.list(config)
            if (profiles.isEmpty()) { settings(addNew = true); return }
            val selected = profiles.indexOfFirst { it.optString("id") == config.optString("active_profile") }
            AlertDialog.Builder(this).setTitle("选择模型")
                .setSingleChoiceItems(profiles.map { profileLabel(it) }.toTypedArray(), selected) { dialog, index ->
                    if (!AgentRuntime.busy && !AgentRuntime.modelChanging && !importing) {
                        try {
                            val updated = ModelProfiles.select(config, profiles[index].getString("id"))
                            store.save(updated)
                            if (AgentRuntime.configureModel(updated)) dialog.dismiss()
                        } catch (error: Exception) { toast("切换失败：${error.message}") }
                    }
                }.setNeutralButton("管理模型") { _, _ -> manageModels() }.setNegativeButton("取消", null).show()
        } catch (error: Exception) { toast("读取配置失败：${error.message}") }
    }

    private fun profileLabel(profile: JSONObject): String {
        val alias = profile.optString("name")
        val model = profile.optString("model")
        return (if (alias.isBlank()) model else "$alias · $model") + "\n" + (android.net.Uri.parse(profile.optString("base_url")).host ?: "")
    }

    private fun manageModels() {
        try {
            val profiles = ModelProfiles.list(SecureSettings(this).load() ?: JSONObject())
            AlertDialog.Builder(this).setTitle("模型配置")
                .setItems((profiles.map { profileLabel(it) } + "＋ 添加模型配置").toTypedArray()) { _, index ->
                    if (index == profiles.size) settings(addNew = true) else settings(profiles[index].getString("id"))
                }.setNegativeButton("关闭", null).show()
        } catch (error: Exception) { toast("读取配置失败：${error.message}") }
    }

    private fun settings(profileId: String? = null, addNew: Boolean = false) {
        val store = SecureSettings(this)
        val saved = try { store.load() ?: JSONObject() } catch (_: Exception) { JSONObject() }
        val old = if (addNew) null else ModelProfiles.list(saved).find { it.optString("id") == (profileId ?: saved.optString("active_profile")) }
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
        val alias = field("配置名称（可选）", old?.optString("name").orEmpty())
        val endpoint = field("服务地址（HTTPS）", old?.optString("base_url") ?: "https://api.anthropic.com")
        val model = field("模型 ID", old?.optString("model").orEmpty())
        val token = field("API 密钥（在设备上加密保存）", old?.optString("api_key").orEmpty(), true)
        val bearer = CheckBox(this).apply { text = "使用 Bearer 认证"; isChecked = old?.optString("auth_style") == "bearer" }
        fields.addView(bearer)
        val dialog = AlertDialog.Builder(this).setTitle(if (old == null) "添加模型" else "编辑模型")
            .setView(ScrollView(this).apply { addView(fields) }).setPositiveButton("保存并使用", null).setNegativeButton("取消", null).create()
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
                        if (AgentRuntime.busy || AgentRuntime.modelChanging) { toast("请等待当前操作完成"); return@setOnClickListener }
                        val profile = JSONObject().put("name", alias.text.toString().trim()).put("base_url", base).put("model", model.text.toString().trim())
                            .put("api_key", token.text.toString().trim()).put("auth_style", if (bearer.isChecked) "bearer" else "x-api-key")
                        val config = ModelProfiles.upsert(store.load() ?: JSONObject(), old?.optString("id"), profile)
                        store.save(config)
                        if (AgentRuntime.configureModel(config)) dialog.dismiss()
                    } catch (error: Exception) { toast("保存失败：${error.message}") }
                }
            }
        }
        dialog.show()
    }

    private fun workspace() = File(filesDir, "runtime/workspace").apply { mkdirs() }

    private fun toolSettings() {
        val store = SecureSettings(this)
        val config = try { store.load() ?: JSONObject() } catch (_: Exception) { JSONObject() }
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), 0) }
        val shell = CheckBox(this).apply { text = "启用 Android Shell（每次执行需确认）"; isChecked = config.optBoolean("shell") }
        fields.addView(shell)
        fields.addView(TextView(this).apply { text = "Shell 使用系统 sh，可访问本应用的数据；设备未安装的 git、Python 等命令不可用。\n\nMCP 支持 Streamable HTTP，调用远程工具需确认。填写标准 mcpServers JSON，可带 headers。" })
        val servers = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 5; maxLines = 12; textSize = 13f
            setText((config.optJSONObject("mcp") ?: JSONObject().put("mcpServers", JSONObject())).toString(2))
        }
        fields.addView(servers)
        fields.addView(TextView(this).apply { text = AgentRuntime.mcpStatus; setTextIsSelectable(true) })
        val dialog = AlertDialog.Builder(this).setTitle("工具与 MCP")
            .setView(ScrollView(this).apply { addView(fields) }).setPositiveButton("保存并连接", null).setNegativeButton("取消", null).create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val mcp = JSONObject(servers.text.toString())
                    val entries = mcp.optJSONObject("mcpServers") ?: error("需要 mcpServers 对象")
                    check(entries.length() <= 16) { "最多 16 个服务器" }
                    entries.keys().forEach { name ->
                        check(name.matches(Regex("[A-Za-z0-9_-]+")) && !name.contains("__")) { "服务器名称格式不正确" }
                        val value = entries.getJSONObject(name)
                        check(value.optString("type", "http") == "http") { "目前支持 type=http" }
                        val uri = android.net.Uri.parse(value.getString("url"))
                        check(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) { "需要 HTTP(S) 地址" }
                        (if (value.has("headers")) value.getJSONObject("headers") else null)?.let { headers -> headers.keys().forEach { key ->
                            check(headers.get(key) is String && !key.contains('\n') && !key.contains('\r') && !headers.getString(key).contains(Regex("[\r\n]"))) { "无效请求头" }
                        } }
                    }
                    config.put("mcp", mcp).put("shell", shell.isChecked)
                    store.save(config)
                    if (AgentRuntime.configureTools(config, true)) dialog.dismiss()
                } catch (error: Exception) { servers.error = error.message ?: "配置无效" }
            }
        }
        dialog.show()
    }
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
        val capture = if (requestCode == 13) pendingCapture.also { pendingCapture = null } else null
        val uri = capture ?: data?.data
        if (resultCode != RESULT_OK || requestCode !in listOf(10, 11, 12, 13) || uri == null) {
            capture?.let { releaseCapture(it) }; render(); return
        }
        importing = true; render()
        val export = exportFile
        Thread {
            var temporary: File? = null
            val message = try {
                if (requestCode == 12 || requestCode == 13) {
                    val attachment = ImageAttachment.read(contentResolver, uri)
                    runOnUiThread { if (AgentRuntime.attachments.size < 4) AgentRuntime.attachments.add(attachment) }
                    "已添加图片，请填写问题后发送"
                } else if (requestCode == 10) {
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
            finally { temporary?.delete(); capture?.let { releaseCapture(it) } }
            runOnUiThread { importing = false; toast(message); if (!isDestroyed) render() }
        }.start()
    }

    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
}

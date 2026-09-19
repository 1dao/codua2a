package app.codua2a

import android.content.Context
import android.app.Application
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** One Lua event loop per process. Activity recreation never restarts it. */
object AgentRuntime {
    data class Message(val role: String, var text: String)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var started = false
    private lateinit var context: Application
    private var streaming: Message? = null
    private var renderQueued = false
    val messages = mutableListOf<Message>()
    var listener: (() -> Unit)? = null
    var ready = false; private set
    var busy = false; private set
    var configured = false; private set
    var modelName = "选择模型"; private set
    var modelChanging = false; private set
    var status = "正在启动 Lua 引擎…"; private set
    var pendingApproval: JSONObject? = null; private set
    var sessions: org.json.JSONArray? = null
    var draft = ""
    var mcpStatus = "尚未连接 MCP"; private set
    var serviceListener: (() -> Unit)? = null
    val attachments = mutableListOf<JSONObject>()
    private external fun nativeRun(root: String): Int
    private external fun nativeSend(command: ByteArray): Boolean

    fun start(app: Context) {
        if (started) return
        started = true
        context = app.applicationContext as Application
        executor.execute {
            try {
                System.loadLibrary("codua2a")
                val root = File(context.filesDir, "runtime").apply { mkdirs() }
                // Refresh only packaged code. Never overwrite workspace or sessions.
                copyAssets("scripts", File(root, "scripts"))
                copyAssets("android", File(root, "android"))
                val code = nativeRun(root.absolutePath)
                main.post { ready = false; busy = false; status = "引擎已退出 ($code)，请重新打开应用"; notifyUi() }
            } catch (error: Throwable) {
                main.post { status = "启动失败：${error.message}"; ready = false; notifyUi() }
            }
        }
    }

    private fun copyAssets(path: String, destination: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isNotEmpty()) {
            check(destination.isDirectory || destination.mkdirs())
            children.forEach { copyAssets("$path/$it", File(destination, it)) }
        } else {
            destination.parentFile?.mkdirs()
            context.assets.open(path).use { source -> destination.outputStream().use { source.copyTo(it) } }
        }
    }

    fun send(command: JSONObject): Boolean {
        if (!ready) return false
        return nativeSend(command.toString().toByteArray(Charsets.UTF_8)).also {
            if (!it) { status = "指令未发送，请稍后重试"; notifyUi() }
        }
    }

    fun submit(text: String): Boolean {
        if (busy || modelChanging || !configured || text.isBlank()) return false
        AgentService.begin(context)
        val sent = send(JSONObject().put("action", "submit").put("text", text).put("images", org.json.JSONArray(attachments)))
        if (sent) { attachments.clear(); busy = true; status = "正在思考…"; notifyUi() }
        return sent
    }

    fun configureModel(config: JSONObject): Boolean {
        if (!ready || busy || modelChanging) return false
        val profile = ModelProfiles.selected(ModelProfiles.normalize(config)) ?: return false
        val sent = send(JSONObject().put("action", "configure").put("config", profile))
        if (sent) { modelChanging = true; notifyUi() }
        return sent
    }

    fun configureTools(config: JSONObject, connect: Boolean): Boolean {
        if (!send(JSONObject().put("action", "configure_tools").put("shell", config.optBoolean("shell"))
                .put("mcp", config.optJSONObject("mcp") ?: JSONObject()))) return false
        if (connect) {
            AgentService.begin(context)
            val sent = send(JSONObject().put("action", "connect_mcp"))
            if (sent) { busy = true; status = "正在连接 MCP…"; notifyUi() }
            return sent
        }
        return true
    }

    fun approve(allow: Boolean) {
        val request = pendingApproval ?: return
        if (send(JSONObject().put("action", "confirm").put("id", request.getInt("id")).put("allow", allow))) {
            pendingApproval = null; status = "执行中…"; notifyUi()
        }
    }

    // JNI passes standard UTF-8 bytes, including emoji, not modified UTF-8 strings.
    @Suppress("unused")
    fun onNativeEvent(bytes: ByteArray) {
        val event = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (_: Exception) { return }
        main.post { accept(event) }
    }

    private fun add(role: String, text: String) {
        messages.add(Message(role, text))
        // Bound view memory; full completed history remains in Lua session files.
        if (messages.size > 300) messages.removeAt(0)
    }

    private fun accept(e: JSONObject) {
        when (e.optString("type")) {
            "ready" -> {
                ready = true; status = "就绪 · 请配置模型"
                try { SecureSettings(context).load()?.let {
                    configureTools(it, false)
                    if (it.has("model")) configureModel(it)
                } }
                catch (_: Exception) { status = "无法解密原配置，请在设置中重新保存" }
            }
            "configured" -> { configured = true; modelChanging = false; modelName = e.optString("model"); status = "就绪 · $modelName" }
            "mcp_status" -> {
                val servers = e.optJSONArray("servers")
                mcpStatus = if (servers == null || servers.length() == 0) "未配置 MCP 服务器" else
                    (0 until servers.length()).joinToString("\n") { n -> servers.getJSONObject(n).let {
                        "${it.optString("name")}: ${it.optString("status")} · ${it.optInt("tools")} tools ${it.optString("error", "")}" } }
                status = mcpStatus
            }
            "user" -> { streaming = null; add("你", e.optString("text")) }
            "text" -> {
                if (streaming == null) { add("codua", ""); streaming = messages.last() }
                streaming!!.text += e.optString("text")
            }
            "assistant" -> { streaming = null }
            "truncated_retry" -> { streaming?.let { messages.remove(it) }; streaming = null }
            "tool_use" -> { streaming = null; add("工具 · ${e.optString("name")}", e.optJSONObject("input")?.toString(2).orEmpty()) }
            "tool_result" -> add("结果 · ${e.optString("name")}", e.optJSONObject("result")?.optString("content").orEmpty().take(6000))
            "confirm" -> { pendingApproval = e; status = "等待工具操作确认" }
            "busy" -> {
                busy = e.optBoolean("value")
                if (!busy) { streaming = null; pendingApproval = null; status = "就绪" }
            }
            "done" -> { streaming = null; status = "完成 · ${e.optString("stop_reason")}" }
            "status" -> { status = e.optString("text") }
            "error" -> { modelChanging = false; add("错误", e.optString("error")); status = "操作失败" }
            "sessions" -> { sessions = e.optJSONArray("items") }
            "session" -> {
                messages.clear(); streaming = null
                val history = e.optJSONArray("messages")
                if (history != null) for (i in 0 until history.length()) {
                    val msg = history.optJSONObject(i) ?: continue
                    val content = msg.opt("content")
                    val text = if (content is String) content else if (content is org.json.JSONArray) {
                        (0 until content.length()).mapNotNull { n -> content.optJSONObject(n)?.let {
                            when (it.optString("type")) {
                                "text" -> it.optString("text")
                                "tool_use" -> "[工具 ${it.optString("name")}]"
                                "tool_result" -> it.optString("content").take(2000)
                                else -> null
                            }
                        } }.joinToString("\n")
                    } else ""
                    if (text.isNotEmpty()) add(if (msg.optString("role") == "assistant") "codua" else "你 / 工具", text)
                }
                status = "就绪"
            }
        }
        notifyUi()
    }

    private fun notifyUi() {
        if (renderQueued) return
        renderQueued = true
        main.postDelayed({ renderQueued = false; listener?.invoke(); serviceListener?.invoke() }, 40)
    }
}

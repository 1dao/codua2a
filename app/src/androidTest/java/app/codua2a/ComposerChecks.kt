package app.codua2a

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import org.json.JSONObject
import java.io.File

object ComposerChecks {
    fun run(test: Instrumentation) {
        fun main(block: () -> Unit) {
            var failure: Throwable? = null
            test.runOnMainSync { try { block() } catch (e: Throwable) { failure = e } }
            failure?.let { throw it }
        }
        fun waitFor(label: String, block: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 8000
            while (SystemClock.elapsedRealtime() < until) {
                var done = false; main { done = block() }; if (done) return
                SystemClock.sleep(40)
            }
            error("Timeout: $label (${AgentRuntime.status})")
        }
        fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
        val automation = test.uiAutomation
        val store = SecureSettings(test.targetContext)
        val backup = store.load()
        val legacy = JSONObject().put("base_url", "https://192.0.2.1").put("model", "fixture-alpha")
            .put("api_key", "fixture-a").put("auth_style", "bearer").put("shell", false)
            .put("mcp", JSONObject().put("mcpServers", JSONObject()))
        var activity: MainActivity? = null
        var monitor: Instrumentation.ActivityMonitor? = null
        try {
            store.save(legacy)
            val migrated = checkNotNull(store.load())
            check(ModelProfiles.list(migrated).size == 1 && migrated.getString("api_key") == "fixture-a")
            val beta = JSONObject().put("base_url", "https://192.0.2.2").put("model", "fixture-beta")
                .put("api_key", "fixture-b").put("auth_style", "x-api-key")
            val two = ModelProfiles.upsert(migrated, null, beta)
            check(ModelProfiles.list(two).size == 2 && two.has("mcp") && !two.getBoolean("shell"))
            val betaId = two.getString("active_profile")
            val edited = ModelProfiles.upsert(two, betaId, JSONObject(beta.toString()).put("name", "Second"))
            check(ModelProfiles.list(edited).size == 2)
            store.save(ModelProfiles.select(edited, "legacy"))
            check(SecureSettings(test.targetContext).load()?.getString("model") == "fixture-alpha")
            val opened = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = opened
            waitFor("default model loaded") { AgentRuntime.ready && !AgentRuntime.modelChanging && AgentRuntime.modelName == "fixture-alpha" }
            fun find(label: String): View = views(opened.window.decorView).first { it.contentDescription?.toString()?.startsWith(label) == true }
            main {
                check((find("选择模型") as TextView).text.contains("fixture-alpha"))
                find("添加图片或文件").performClick()
            }
            waitFor("inline attachments visible") { listOf("拍照", "相册", "文件").all { find(it).isShown } }
            main { find("收起附件选项").performClick() }
            waitFor("inline attachments collapsed") { !find("拍照").isShown }
            main { find("选择模型").performClick() }
            val deadline = SystemClock.elapsedRealtime() + 5000
            var selected = false
            while (!selected && SystemClock.elapsedRealtime() < deadline) {
                val nodes = automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("fixture-beta").orEmpty()
                for (match in nodes) {
                    var node: AccessibilityNodeInfo? = match
                    while (node != null && !selected) {
                        selected = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        node = node.parent
                    }
                }
                if (!selected) SystemClock.sleep(50)
            }
            check(selected) { "Cannot select second model in dialog" }
            waitFor("model switched") { AgentRuntime.modelName == "fixture-beta" && !AgentRuntime.modelChanging }
            check(store.load()?.getString("api_key") == "fixture-b")
            check(store.load()?.getString("active_profile") == betaId)
            var captured = false
            monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action != MediaStore.ACTION_IMAGE_CAPTURE) return null
                    @Suppress("DEPRECATION") val uri = intent.getParcelableExtra<android.net.Uri>(MediaStore.EXTRA_OUTPUT)!!
                    check(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
                    val bitmap = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888)
                    test.targetContext.contentResolver.openOutputStream(uri, "w")!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    bitmap.recycle(); captured = true
                    return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
                }
            }
            test.addMonitor(monitor)
            main { find("添加图片或文件").performClick() }
            waitFor("camera option") { find("拍照").isShown }
            main { find("拍照").performClick() }
            waitFor("camera result attached with null intent") { captured && AgentRuntime.attachments.size == 1 && find("添加图片或文件").isEnabled }
            check(File(test.targetContext.cacheDir, "captures").listFiles().orEmpty().isEmpty()) { "Temporary capture leaked" }
            val bad = android.net.Uri.parse("content://${test.targetContext.packageName}.captures/../model")
            check(runCatching { CaptureProvider.file(test.targetContext, bad) }.isFailure)
            test.removeMonitor(monitor); monitor = null
            main { AgentRuntime.attachments.clear() }
        } finally {
            monitor?.let { test.removeMonitor(it) }
            if (backup == null) test.targetContext.getSharedPreferences("model", 0).edit().clear().commit() else store.save(backup)
            main { activity?.finish() }
        }
    }
}

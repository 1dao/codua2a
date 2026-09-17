package app.codua2a

import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.text.Spanned
import android.text.style.StyleSpan
import org.json.JSONObject
import java.io.File

object UiRuntimeChecks {
    fun run(test: Instrumentation) {
        fun main(block: () -> Unit) {
            var failure: Throwable? = null
            test.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
            failure?.let { throw it }
        }
        fun waitFor(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10000
            while (SystemClock.elapsedRealtime() < deadline) {
                var ready = false; main { ready = condition() }; if (ready) return
                SystemClock.sleep(50)
            }
            error("Timed out: $label; ${AgentRuntime.status}")
        }
        var activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        waitFor("engine startup") { AgentRuntime.ready }
        val fixture = JSONObject().put("base_url", "https://192.0.2.1").put("api_key", "offline-fixture-only")
            .put("model", "fixture").put("auth_style", "bearer")
        val settings = SecureSettings(test.targetContext)
        val old = settings.load()
        try {
            settings.save(fixture)
            check(settings.load()?.getString("api_key") == "offline-fixture-only")
            main { AgentRuntime.send(JSONObject().put("action", "configure").put("config", fixture)) }
            waitFor("configuration") { AgentRuntime.configured }
            val image = File(test.targetContext.cacheDir, "image-test.png")
            val bitmap = Bitmap.createBitmap(1800, 900, Bitmap.Config.ARGB_8888)
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            val attachment = ImageAttachment.read(test.targetContext.contentResolver, Uri.fromFile(image))
            check(attachment.getString("media_type") == "image/jpeg" && attachment.getString("data").isNotEmpty())
            val markdown = NativeMarkdown.render("# Heading\n**bold** and `code`") as Spanned
            check(markdown.getSpans(0, markdown.length, StyleSpan::class.java).size >= 2)
            main {
                AgentRuntime.attachments.add(attachment)
                check(AgentRuntime.submit("设备测试 你好 😀"))
            }
            waitFor("foreground service") {
                @Suppress("DEPRECATION")
                val services = test.targetContext.getSystemService(ActivityManager::class.java).getRunningServices(100)
                services.any { it.service.className == AgentService::class.java.name && it.foreground }
            }
            val monitor = test.addMonitor(MainActivity::class.java.name, null, false)
            main { activity.recreate() }
            activity = checkNotNull(test.waitForMonitorWithTimeout(monitor, 5000)) as MainActivity
            test.removeMonitor(monitor)
            waitFor("recreated activity shares task") { AgentRuntime.ready && AgentRuntime.busy && AgentRuntime.messages.any { it.text.contains("设备测试") } }
            main { activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            waitFor("activity stopped") { AgentRuntime.listener == null }
            waitFor("task still running in background") { AgentRuntime.busy }
            main { AgentRuntime.send(JSONObject().put("action", "cancel")) }
            waitFor("background cancellation") { !AgentRuntime.busy }
            waitFor("foreground service stops") {
                @Suppress("DEPRECATION")
                val services = test.targetContext.getSystemService(ActivityManager::class.java).getRunningServices(100)
                services.none { it.service.className == AgentService::class.java.name }
            }
            main { AgentRuntime.send(JSONObject().put("action", "sessions")) }
            waitFor("session persisted") { AgentRuntime.sessions?.length()?.let { it > 0 } == true }
        } finally {
            // Restore pre-test encrypted configuration; tests never need a real model key.
            if (old != null) settings.save(old)
            else test.targetContext.getSharedPreferences("model", 0).edit().clear().commit()
            main { activity.finish() }
        }
    }
}

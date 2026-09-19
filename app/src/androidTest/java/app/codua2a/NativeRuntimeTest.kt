package app.codua2a

import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import java.io.File

class NativeRuntimeTest : Instrumentation() {
    private var mode = "native"
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); mode = arguments?.getString("mode") ?: "native"; start() }
    override fun onStart() {
        try {
            when (mode) {
                "ui" -> UiRuntimeChecks.run(this)
                "compose" -> ComposerChecks.run(this)
                "approval" -> ApprovalChecks.run(this)
                else -> testNativeTools()
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "$mode checks passed") })
        }
        catch (error: Throwable) { finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", error.stackTraceToString()) }) }
    }
    fun testNativeTools() {
        val root = File(targetContext.cacheDir, "native-smoke").apply { mkdirs() }
        fun copy(path: String, target: File) {
            val assets = targetContext.assets
            val children = assets.list(path).orEmpty()
            if (children.isNotEmpty()) { target.mkdirs(); children.forEach { copy("$path/$it", File(target, it)) } }
            else { target.parentFile?.mkdirs(); assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } } }
        }
        copy("scripts", File(root, "scripts")); copy("android", File(root, "android"))
        context.assets.open("native_smoke.lua").use { input ->
            File(root, "android/bootstrap.lua").outputStream().use { input.copyTo(it) }
        }
        context.assets.open("fixture-ca.pem").use { input -> File(root, "fixture-ca.pem").outputStream().use { input.copyTo(it) } }
        System.loadLibrary("codua2a")
        val run = AgentRuntime::class.java.getDeclaredMethod("nativeRun", String::class.java).apply { isAccessible = true }
        ProtocolFixture(context.assets).use { fixture ->
            File(root, "fixture-port.txt").writeText(fixture.port.toString())
            check(run.invoke(AgentRuntime, root.absolutePath) == 0) { "Native suite failed: ${AgentRuntime.status}" }
        }
        waitForIdleSync()
        check(AgentRuntime.status == "native-tests-passed 你好 😀") { AgentRuntime.status }
    }
}

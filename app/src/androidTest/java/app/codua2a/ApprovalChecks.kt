package app.codua2a

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

object ApprovalChecks {
    fun run(test: Instrumentation) {
        val automation = test.uiAutomation
        fun waitFor(label: String, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 8000
            while (SystemClock.elapsedRealtime() < end) {
                if (condition()) return
                SystemClock.sleep(50)
            }
            error("Timeout: $label; status=${AgentRuntime.status}; listener=${AgentRuntime.listener != null}")
        }
        fun launch() = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as MainActivity
        val first = launch()
        waitFor("runtime") { AgentRuntime.ready && !AgentRuntime.modelChanging }
        var second = launch()
        test.waitForIdleSync()
        SystemClock.sleep(400) // The previous Activity's onStop follows the new onStart.
        try {
            val event = JSONObject().put("type", "confirm").put("id", 987)
                .put("name", "RunLua").put("input", JSONObject().put("code", "print(42)"))
            AgentRuntime.onNativeEvent(event.toString().toByteArray(Charsets.UTF_8))
            waitFor("Lua approval dialog after reopening activity") {
                automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("允许 RunLua？")?.isNotEmpty() == true
            }
            val monitor = test.addMonitor(MainActivity::class.java.name, null, false)
            test.runOnMainSync { second.recreate() }
            second = checkNotNull(test.waitForMonitorWithTimeout(monitor, 5000)) as MainActivity
            test.removeMonitor(monitor)
            waitFor("pending approval survives recreation") {
                automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("允许 RunLua？")?.isNotEmpty() == true
            }
            waitFor("reject button") {
                automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("拒绝")?.any {
                    it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } == true
            }
            waitFor("approval resolved") { AgentRuntime.pendingApproval == null }
        } finally {
            AgentRuntime.onNativeEvent(JSONObject().put("type", "busy").put("value", false).toString().toByteArray())
            test.runOnMainSync { second.finish(); first.finish() }
        }
    }
}

package app.codua2a

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject

/** Keep an explicitly started task alive when its Activity leaves the foreground. */
class AgentService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val deadline = Runnable {
        AgentRuntime.send(JSONObject().put("action", "cancel"))
        stopSelf()
    }
    companion object {
        fun begin(context: Context) { context.startForegroundService(Intent(context, AgentService::class.java)) }
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, AgentService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "agent-tasks").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("codua 正在处理任务").setContentText(AgentRuntime.status.take(120))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("agent-tasks", "助手任务", NotificationManager.IMPORTANCE_LOW))
        AgentRuntime.serviceListener = {
            if (!AgentRuntime.busy) stopSelf()
            else if (android.os.Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                getSystemService(NotificationManager::class.java).notify(1, notification())
            }
        }
        handler.postDelayed(deadline, 30 * 60 * 1000L)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification())
        if (intent?.action == "stop") AgentRuntime.send(JSONObject().put("action", "cancel"))
        if (!AgentRuntime.busy) stopSelf()
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        AgentRuntime.serviceListener = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

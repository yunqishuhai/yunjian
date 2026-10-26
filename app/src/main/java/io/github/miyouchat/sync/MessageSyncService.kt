package io.github.miyouchat.sync

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.miyouchat.MainActivity
import io.github.miyouchat.MiyouApplication
import io.github.miyouchat.R
import io.github.miyouchat.data.MessageCodec
import kotlinx.coroutines.*

class MessageSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository get() = (application as MiyouApplication).repository
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("sync", "后台消息同步", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("messages", "私聊新消息", NotificationManager.IMPORTANCE_HIGH).apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, MessageSyncService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, "sync").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("云笺消息同步").setContentText("正在接收新消息").setOngoing(true).setContentIntent(open)
            .addAction(0, "暂停同步", stop).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        else startForeground(1, notification)
        scope.launch {
            var failures = 0
            while (isActive && repository.store.backgroundSync && repository.session() != null) {
                try {
                    repository.sync(); failures = 0
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { failures++ }
                delay(if (failures == 0) 6000L else (6000L * (1L shl failures.coerceAtMost(4))).coerceAtMost(60000))
            }
            stopSelf()
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { repository.store.backgroundSync = false; stopSelf() }
        return if (repository.store.backgroundSync && repository.session() != null) START_STICKY else START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}

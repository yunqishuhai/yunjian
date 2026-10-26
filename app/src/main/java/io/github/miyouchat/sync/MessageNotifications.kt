package io.github.miyouchat.sync

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.miyouchat.MainActivity
import io.github.miyouchat.R
import io.github.miyouchat.data.*

object IncomingPolicy {
    fun newest(account: String, previous: Chat?, current: Chat, inserted: List<Message>, initialized: Boolean): Message? {
        if (!initialized || (previous == null && current.unread == 0)) return null
        return inserted.filter { it.id.isNotBlank() && it.senderId != account && it.time >= (previous?.updatedAt ?: 0) }.maxByOrNull { it.time }
    }
    fun show(enabled: Boolean, foreground: Boolean, activeChat: String?, chatId: String, muted: Boolean) =
        enabled && !muted && !(foreground && activeChat == chatId)
    fun markRead(foreground: Boolean, activeChat: String?, chatId: String) = foreground && activeChat == chatId
}

class MessageNotifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)
    init {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(NotificationChannel("sync", "后台消息同步", NotificationManager.IMPORTANCE_LOW))
        system.createNotificationChannel(NotificationChannel("messages", "私聊新消息", NotificationManager.IMPORTANCE_HIGH).apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
    }
    fun post(account: String, chat: Chat, message: Message) {
        if (!manager.areNotificationsEnabled()) return
        val identity = MessageCodec.md5("$account:${chat.id}")
        val intent = Intent(context, MainActivity::class.java).putExtra("chat_id", chat.id).putExtra("account_id", account)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setData(Uri.parse("yunjian://conversation/$identity"))
        val click = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context,"messages").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(chat.name).setContentText(MessageCodec.text(message).take(100))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setOnlyAlertOnce(false)
            .setContentIntent(click).setCategory(NotificationCompat.CATEGORY_MESSAGE).build()
        runCatching { manager.notify("chat:$identity", 2, notification) }
    }
    fun clear(account: String, chat: String) { manager.cancel("chat:${MessageCodec.md5("$account:$chat")}",2) }
    fun clearAllMessages() {
        context.getSystemService(NotificationManager::class.java).activeNotifications.filter { it.tag?.startsWith("chat:") == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }
}

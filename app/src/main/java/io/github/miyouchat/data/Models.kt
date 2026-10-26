package io.github.miyouchat.data

import org.json.JSONObject
import java.security.MessageDigest

data class Session(val aid: String, val mid: String, val stoken: String, val deviceId: String)
data class Chat(val id: String, val targetUid: String, val name: String, val avatar: String,
                val preview: String, val updatedAt: Long, val unread: Int, val pinned: Boolean = false, val muted: Boolean = false)
enum class Delivery { SENT, SENDING, FAILED, UNCERTAIN, REVIEW, REJECTED }
data class Message(val id: String, val localId: String, val chatId: String, val senderId: String,
                   val type: Int, val content: String, val time: Long,
                   val delivery: Delivery = Delivery.SENT, val error: String = "") {
    val key: String get() = id.ifBlank { "local:$localId" }
}
data class HistoryPage(val messages: List<Message>, val cursor: String, val isLast: Boolean)
data class QrLogin(val ticket: String, val url: String, val deviceId: String)
data class Emoticon(val name: String, val url: String, val groupId: String = "default", val groupName: String = "其他",
                    val customId: String = "", val content: String = "", val auditStatus: String = "") {
    val key: String get() = if (customId.isNotBlank()) "custom:$customId" else "official:$name"
    val canSend: Boolean get() = customId.isBlank() || auditStatus == "CustomEmoticonAuditStatusPass"
}
data class FavoriteGroup(val id: String, val name: String, val items: List<Emoticon> = emptyList())

object MessageCodec {
    fun receipt(data: JSONObject, submitted: Message): Message {
        val value = data.optJSONObject("message")
        // The official client accepts retcode 0 without requiring a full
        // message body. Keep a non-retryable pending item until history can
        // reconcile local_id instead of inventing a server rejection.
        if (value == null || value.optString("message_id").isBlank()) {
            return submitted.copy(delivery = Delivery.UNCERTAIN, error = "已提交，等待历史同步确认")
        }
        if (value.optString("local_id", submitted.localId) != submitted.localId ||
            value.optString("chat_id", submitted.chatId) != submitted.chatId) {
            return submitted.copy(delivery = Delivery.UNCERTAIN, error = "回执不匹配，请刷新核对")
        }
        return parse(value, submitted.chatId).copy(localId = submitted.localId)
    }
    fun timestamp(value: Long): Long = when {
        value > 100_000_000_000_000_000 -> value / 1_000_000
        value > 100_000_000_000_000 -> value / 1_000
        value in 1..99_999_999_999 -> value * 1_000
        else -> value
    }
    fun parse(value: JSONObject, chatId: String): Message = Message(
        value.optString("message_id"), value.optString("local_id"), value.optString("chat_id", chatId),
        value.optString("sender_id"), value.optInt("message_type"), value.optString("message_content"),
        timestamp(value.optLong("send_time")))
    fun textContent(text: String): String = JSONObject().put("text", text).put("entities", org.json.JSONArray()).toString()
    fun text(message: Message): String = when (message.type) {
        9 -> runCatching { JSONObject(message.content).optString("text", "[富文本消息]") }.getOrDefault("[富文本消息]")
        3 -> "[图片]"
        7 -> "[帖子分享]"
        8 -> "[自定义表情]"
        1, 2 -> message.content
        else -> "[暂不支持的消息]"
    }
    fun md5(value: String): String = MessageDigest.getInstance("MD5").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

package io.github.miyouchat

import io.github.miyouchat.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MessageCodecTest {
    @Test fun acceptedRequestWithoutEchoDoesNotOfferBlindRetry() {
        val pending = Message("", "local", "chat", "sender", 9, "{}", 1000, Delivery.SENDING)
        for (data in listOf(JSONObject(), JSONObject().put("message", JSONObject.NULL), JSONObject().put("message", "accepted"))) {
            val result = MessageCodec.receipt(data, pending)
            assertEquals(Delivery.UNCERTAIN, result.delivery)
            assertEquals("local", result.localId)
            assertTrue(result.id.isBlank())
        }
    }
    @Test fun mismatchedAcknowledgementCannotReplaceAnotherMessage() {
        val pending = Message("", "local", "chat", "sender", 9, "{}", 1000, Delivery.SENDING)
        val echo = JSONObject().put("message_id", "server").put("local_id", "other-local").put("chat_id", "chat")
        assertEquals(Delivery.UNCERTAIN, MessageCodec.receipt(JSONObject().put("message", echo), pending).delivery)
    }
    @Test fun timestampsUseOneUnitAcrossServerVersions() {
        val millis = 1760315400000L
        assertEquals(millis, MessageCodec.timestamp(millis / 1000))
        assertEquals(millis, MessageCodec.timestamp(millis))
        assertEquals(millis, MessageCodec.timestamp(millis * 1000))
        assertEquals(millis, MessageCodec.timestamp(millis * 1000000))
    }
    @Test fun richTextPreservesUnicodeQuotesAndNewlines() {
        val text = "你好，世界\n\"测试\" 👋"
        val content = MessageCodec.textContent(text)
        val json = JSONObject(content)
        assertEquals(text, json.getString("text"))
        assertEquals(0, json.getJSONArray("entities").length())
        assertEquals(text, MessageCodec.text(Message("id", "local", "chat", "sender", 9, content, 0)))
    }
    @Test fun unknownMessageIsNotRenderedAsRawJson() {
        assertEquals("[暂不支持的消息]", MessageCodec.text(Message("id", "", "chat", "sender", 1000, "private raw payload", 0)))
        assertEquals("[图片]", MessageCodec.text(Message("id", "", "chat", "sender", 3, "{}", 0)))
    }
    @Test fun reconciliationKeepsClientAndServerIdentitySeparate() {
        val parsed = MessageCodec.parse(JSONObject("""{"message_id":"server-id","local_id":"client-id","sender_id":"u","message_type":9,"message_content":"{}","send_time":1760315400000000} """), "chat")
        assertEquals("server-id", parsed.key)
        assertEquals("client-id", parsed.localId)
        assertEquals(1760315400000, parsed.time)
    }
}

package io.github.miyouchat

import io.github.miyouchat.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipInputStream

class ChatArchiveTest {
    private fun row(id: String, type: Int, content: String) = JSONObject().put("id",id).put("local_id","local-$id")
        .put("chat_id","../../chat").put("chat_name","<script>nickname</script>").put("sender_id","self")
        .put("type",type).put("content",content).put("time",1000).put("delivery","SENT").put("error","")
    private fun entries(bytes: ByteArray): Map<String,ByteArray> = linkedMapOf<String,ByteArray>().apply {
        ZipInputStream(bytes.inputStream()).use { zip -> while (true) { val entry = zip.nextEntry ?: break; put(entry.name,zip.readBytes()) } }
    }
    private fun archive(rows: List<JSONObject>, images: Boolean = false, fetch: (String)->ByteArray? = { null }): Pair<ArchiveResult,Map<String,ByteArray>> {
        val file = File.createTempFile("synthetic-chat-", ".jsonl")
        try {
            file.writeText(rows.joinToString("\n") { it.toString() })
            val output = ByteArrayOutputStream()
            val result = ChatArchive.write(file,output,"self","test account",emptyList(),images,fetch = fetch)
            return result to entries(output.toByteArray())
        } finally { file.delete() }
    }
    @Test fun archiveEscapesTextAndNamesAndPreservesRawJson() {
        val text = "<img src=x onerror=alert(1)>\n你好\"test\""
        val row = row("1",9,MessageCodec.textContent(text))
        val (result,entries) = archive(listOf(row))
        assertEquals(1,result.messages)
        val html = String(entries.entries.first { it.key.startsWith("chats/") }.value)
        assertFalse(html.contains("<script>nickname")); assertFalse(html.contains("<img src=x"))
        assertTrue(html.contains("&lt;img")); assertTrue(html.contains("default-src 'none'"))
        assertEquals(text,JSONObject(JSONObject(String(entries.getValue("messages.json"))).getJSONArray("messages").getJSONObject(0).getString("content")).getString("text"))
        assertTrue(entries.keys.none { it.contains("..") || it.startsWith("/") })
    }
    @Test fun attachmentsAreDeduplicatedAndOfflineReferenced() {
        val url = "https://bbs-static.miyoushe.com/image.png"
        val content = JSONObject().put("url",url).toString()
        var requests = 0
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
        val (result,entries) = archive(listOf(row("1",3,content),row("2",8,content)),true) { requests++; png }
        assertEquals(1,requests); assertEquals(1,result.images); assertEquals(0,result.missing)
        assertEquals(1,entries.keys.count { it.startsWith("media/") })
        val html = String(entries.entries.first { it.key.startsWith("chats/") }.value)
        assertTrue(html.contains("src='../media/")); assertFalse(html.contains("src='https://"))
    }
    @Test fun unavailableImagesAreReportedAndNeverAutoLoadedRemotely() {
        val (result,entries) = archive(listOf(row("1",3,"""{"url":"https://bbs-static.miyoushe.com/image.jpg"}""")),true)
        assertEquals(1,result.missing); assertEquals(0,result.images)
        assertEquals(1,JSONObject(String(entries.getValue("manifest.json"))).getJSONArray("missing_images").length())
        val html = String(entries.entries.first { it.key.startsWith("chats/") }.value)
        assertFalse(html.contains("<img")); assertTrue(html.contains("打开原图链接"))
    }
    @Test fun onlyTrustedHttpsImageHostsCanBeFetched() {
        for (url in listOf("http://bbs-static.miyoushe.com/a.jpg","https://miyoushe.com.evil.test/a","https://miyoushe.com@evil.test/a","file:///data/private","https://127.0.0.1/a","https://miyoushe.com:8080/a")) assertFalse(ChatArchive.trustedImage(url))
        assertTrue(ChatArchive.trustedImage("https://upload-bbs.miyoushe.com/a.jpg"))
        assertTrue(ChatArchive.trustedImage("https://img-static.mihoyo.com/a.gif"))
    }
    @Test fun emptyArchiveStillHasUsableIndexAndValidJson() {
        val (result,entries) = archive(emptyList())
        assertEquals(0,result.messages)
        assertTrue(entries.containsKey("index.html"))
        assertEquals(0,JSONObject(String(entries.getValue("messages.json"))).getJSONArray("messages").length())
    }
}

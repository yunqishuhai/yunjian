package io.github.miyouchat.data

import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ArchiveResult(val messages: Int, val images: Int, val missing: Int)

/** Offline HTML and machine-readable JSON. No scripts, credentials or remote auto-loads. */
object ChatArchive {
    private data class Room(val name: String, val path: String, var count: Int = 0)
    fun trustedImage(url: String): Boolean = runCatching {
        val uri = URI(url); val host = uri.host?.lowercase().orEmpty()
        uri.scheme == "https" && uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) &&
            listOf("miyoushe.com", "mihoyo.com").any { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    fun escape(value: String): String = value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;")
    private fun media(row: JSONObject, official: Map<String,String>): String? = when (row.getInt("type")) {
        3,8 -> runCatching { JSONObject(row.getString("content")).getString("url") }.getOrNull()
        2 -> row.getString("content").let { if (it.startsWith("_(") && it.endsWith(")")) official[it.substring(2,it.length-1)] else null }
        else -> null
    }
    private fun isImage(bytes: ByteArray): Boolean =
        (bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) ||
        (bytes.size >= 8 && bytes.take(8).toByteArray().contentEquals(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,13,10,26,10))) ||
        (bytes.size >= 6 && String(bytes,0,6,Charsets.US_ASCII) in listOf("GIF87a","GIF89a")) ||
        (bytes.size >= 12 && String(bytes,0,4,Charsets.US_ASCII) == "RIFF" && String(bytes,8,4,Charsets.US_ASCII) == "WEBP")
    private val header = """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; base-uri 'none'"><style>body{font:16px/1.7 system-ui,sans-serif;background:#f5f3ee;color:#26343e;max-width:880px;margin:auto;padding:24px}a{color:#466983}article{background:#fffcf7;margin:16px 0;padding:16px;border-radius:14px;overflow-wrap:anywhere}article.own{background:#dce8f0}small{color:#59666f}img{max-width:min(100%,420px);max-height:600px;object-fit:contain}h1{font-size:26px}p{white-space:pre-wrap;margin:8px 0}</style><body>"""

    fun write(snapshot: File, output: OutputStream, account: String, accountLabel: String,
        coverage: List<HistoryCoverage>, includeImages: Boolean, official: Map<String,String> = emptyMap(),
        fetch: (String) -> ByteArray?, progress: (String,Int,Int) -> Unit = {_,_,_->}): ArchiveResult {
        val rooms = linkedMapOf<String,Room>(); val wanted = linkedMapOf<String,String>(); val downloaded = hashSetOf<String>()
        val missing = JSONArray(); var count = 0
        ZipOutputStream(output.buffered()).use { zip ->
            fun text(value: String) { zip.write(value.toByteArray(Charsets.UTF_8)) }
            fun entry(name: String) { zip.putNextEntry(ZipEntry(name)) }
            entry("messages.json"); text("{\"schema_version\":1,\"messages\":[\n")
            snapshot.useLines { rows -> rows.forEach { line ->
                val row = JSONObject(line); if (count++ > 0) text(",\n"); text(row.toString())
                val id = row.getString("chat_id")
                rooms.getOrPut(id) { Room(row.getString("chat_name"), "chats/${hash(id)}.html") }.count++
                media(row,official)?.takeIf(::trustedImage)?.let { url -> wanted[url] = "media/${hash(url)}.img" }
                progress("整理消息", count,0)
            } }
            text("\n]}"); zip.closeEntry()
            if (includeImages) wanted.entries.forEachIndexed { index,(url,path) ->
                progress("下载图片附件",index,wanted.size)
                val bytes = fetch(url)
                if (bytes != null && bytes.size <= 20*1024*1024 && isImage(bytes)) {
                    entry(path); zip.write(bytes); zip.closeEntry(); downloaded += url
                } else missing.put(JSONObject().put("url",url).put("reason","图片不可获取、超过大小限制或格式不支持"))
            }
            progress("生成离线阅读页",0,rooms.size)
            var current: String? = null
            val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
            snapshot.useLines { rows -> rows.forEach { line ->
                val row = JSONObject(line); val id = row.getString("chat_id")
                if (current != id) {
                    if (current != null) { text("</body></html>"); zip.closeEntry() }
                    entry(rooms.getValue(id).path); current = id
                    text(header + "<a href='../index.html'>返回目录</a><h1>${escape(rooms.getValue(id).name)}</h1>")
                }
                val own = row.getString("sender_id") == account
                text("<article class='${if (own) "own" else "other"}'><small>${if (own) "我" else escape(row.getString("chat_name"))} · ${escape(format.format(Instant.ofEpochMilli(row.getLong("time"))))}</small>")
                val image = media(row,official)
                if (image != null && image in downloaded) text("<p><img src='../${wanted.getValue(image)}' alt='聊天图片或表情'></p>")
                else if (image != null && trustedImage(image)) text("<p>[图片或表情未随文件保存] <a rel='noreferrer' href='${escape(image)}'>打开原图链接</a></p>")
                else {
                    val message = Message("","",id,row.getString("sender_id"),row.getInt("type"),row.getString("content"),row.getLong("time"))
                    text("<p>${escape(MessageCodec.text(message))}</p>")
                }
                text("</article>"); progress("生成离线阅读页",count, count)
            } }
            if (current != null) { text("</body></html>"); zip.closeEntry() }
            entry("index.html"); text(header + "<h1>云笺聊天记录</h1><p>${escape(accountLabel)} · $count 条已确认消息</p><p>本文件包含导出时已缓存的所选记录。未同步的更早历史不在其中。</p><ul>")
            rooms.forEach { (id,room) ->
                val status = if (coverage.firstOrNull { it.chatId == id }?.complete == true) "已同步到最早" else "可能仍有更早历史"
                text("<li><a href='${room.path}'>${escape(room.name)}</a> · ${room.count} 条 · $status</li>")
            }
            text("</ul><p>备份数据：messages.json。附件不可获取情况见 manifest.json。</p></body></html>"); zip.closeEntry()
            entry("manifest.json"); text(JSONObject().put("schema_version",1).put("created_at",Instant.now().toString())
                .put("account_label",accountLabel).put("message_count",count).put("image_count",downloaded.size)
                .put("images_requested",includeImages).put("missing_images",missing).put("history_scope","cached_confirmed_messages")
                .put("coverage",JSONArray().apply { coverage.forEach { put(JSONObject().put("chat_id",it.chatId).put("complete",it.complete).put("cached",it.cached)) } }).toString()); zip.closeEntry()
        }
        return ArchiveResult(count,downloaded.size,missing.length())
    }
}

package io.github.miyouchat.data

import io.github.miyouchat.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MultipartBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import android.util.Base64
import java.io.IOException

class ApiFailure(val code: Int, val challenge: String? = null, serverMessage: String = "", operation: String = "") : Exception(when (code) {
    -100, 10001 -> "登录状态已失效，请重新登录"
    1028, 1034 -> "需要完成官方安全验证"
    -3501 -> "二维码已过期，请刷新"
    else -> (if (operation.isNotBlank()) "$operation：" else "") +
        serverMessage.filter { !it.isISOControl() }.trim().take(160).ifBlank { "请求未成功" } + "（$code）"
})
class MiyouApi : TemporaryImageApi {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val random = SecureRandom()
    private fun ds(body: String? = null): String {
        val time = System.currentTimeMillis() / 1000
        val nonce = if (body != null) (100000 + random.nextInt(100001)).toString() else UUID.randomUUID().toString().replace("-", "").take(6)
        val salt = if (body != null) "xV8v4Qu54lUKrEYFZkJhB8cuOh9Asafs" else BuildConfig.BBS_DS_SALT
        check(salt.isNotBlank()) { "Missing protocol build configuration" }
        val input = "salt=$salt&t=$time&r=$nonce" + (body?.let { "&b=$it&q=" } ?: "")
        return "$time,$nonce,${MessageCodec.md5(input)}"
    }
    private suspend fun call(url: String, body: JSONObject? = null, session: Session? = null,
                             deviceId: String = session?.deviceId.orEmpty(), passport: Boolean = false,
                             aigis: String = ""): JSONObject = withContext(Dispatchers.IO) {
        val serialized = body?.toString()
        val smsFlow = url.endsWith("createLoginCaptcha") || url.endsWith("loginByMobileCaptcha")
        val request = Request.Builder().url(url)
            .header("User-Agent", "MiyouChat/${BuildConfig.VERSION_NAME} (Android)")
            .header("x-rpc-device_id", deviceId).header("x-rpc-app_id", "bll8iq97cem8")
            .header("x-rpc-client_type", if (passport && !smsFlow) "3" else "2")
            .header("x-rpc-app_version", if (passport && !smsFlow) "2.90.1" else BuildConfig.BBS_APP_VERSION)
            .header("x-rpc-device_name", "Miyou Chat").header("x-rpc-device_model", android.os.Build.MODEL)
            .header("x-rpc-sys_version", android.os.Build.VERSION.RELEASE)
            .header("Referer", "https://app.mihoyo.com")
        if (passport) {
            request.header("x-rpc-sdk_version", "2.90.1").header("x-rpc-account_version", "2.90.1")
                .header("x-rpc-game_biz", "bbs_cn")
            if (serialized != null && !smsFlow) request.header("DS", ds(serialized))
        } else request.header("DS", ds())
        if (aigis.isNotBlank()) request.header("x-rpc-aigis", aigis)
        session?.let { request.header("Cookie", "stuid=${it.aid};stoken=${it.stoken};mid=${it.mid};") }
        if (serialized != null) request.post(serialized.toRequestBody("application/json; charset=utf-8".toMediaType()))
        client.newCall(request.build()).execute().use { response ->
            val result = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrElse { throw IOException("Unusable application response") }
            val code = result.optInt("retcode", Int.MIN_VALUE)
            if (BuildConfig.DEBUG && url.endsWith("/sendMessage")) {
                // Protocol metadata only: no Cookie, IDs, content or response values.
                val data = result.optJSONObject("data")
                val shape = when (data?.opt("message")) { is JSONObject -> "object"; is String -> "string"; null, JSONObject.NULL -> "null"; else -> "other" }
                val publicError = result.optString("message")
                val hints = listOf("DS", "签名", "登录", "图片", "参数", "频繁", "权限", "验证", "风险", "上传", "审核", "关注", "屏蔽", "网络", "系统", "繁忙")
                    .filter { publicError.contains(it, ignoreCase = true) }.joinToString(",")
                android.util.Log.i("MiyouProtocol", "send http=${response.code} retcode=$code acknowledgement=$shape hints=$hints")
            }
            if (!response.isSuccessful || code == Int.MIN_VALUE) throw IOException("Unconfirmed application response")
            if (code != 0) throw ApiFailure(code, response.header("x-rpc-aigis"), result.optString("message"), when {
                url.contains("/getUploadParams") -> "获取图片上传参数失败"
                url.endsWith("/addCustomEmoticon") -> "添加表情失败"
                url.endsWith("/removeCustomEmoticon") -> "删除表情失败"
                url.endsWith("/sendMessage") && body?.optInt("message_type") == 3 -> "图片发送失败"
                url.endsWith("/sendMessage") && body?.optInt("message_type") == 8 -> "表情发送失败"
                else -> ""
            })
            result.optJSONObject("data") ?: JSONObject()
        }
    }
    suspend fun chats(session: Session): List<Chat> {
        val all = mutableListOf<Chat>(); var cursor = ""
        repeat(20) {
            val url = "https://bbs-api.miyoushe.com/chat/api/getChatList".toHttpUrl().newBuilder()
                .addQueryParameter("size", "30").addQueryParameter("last_id", cursor).build()
            val data = call(url.toString(), session = session)
            val rows = data.optJSONArray("list") ?: JSONArray()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i); val user = row.optJSONObject("target") ?: JSONObject()
                val latest = row.optJSONObject("latest_message") ?: JSONObject()
                all += Chat(row.getString("chat_id"), row.optString("target_uid"), user.optString("nickname", "米游社用户"),
                    user.optString("avatar_url"), MessageCodec.text(MessageCodec.parse(latest, row.getString("chat_id"))),
                    MessageCodec.timestamp(latest.optLong("send_time")), row.optInt("unread_count"))
            }
            if (data.optBoolean("is_last", true)) return all.distinctBy { it.id }
            val next = data.optString("last_id")
            check(next.isNotBlank() && next != cursor) { "Conversation cursor did not advance" }; cursor = next
        }
        return all.distinctBy { it.id }
    }
    override suspend fun history(session: Session, chatId: String, cursor: String, size: Int): HistoryPage {
        val url = "https://bbs-api.miyoushe.com/chat/api/getMessageList".toHttpUrl().newBuilder()
            .addQueryParameter("chat_id", chatId).addQueryParameter("fetch_type", "2")
            .addQueryParameter("last_id", cursor).addQueryParameter("size", size.toString()).build()
        val data = call(url.toString(), session = session)
        val array = data.optJSONArray("list") ?: JSONArray()
        return HistoryPage((0 until array.length()).map { MessageCodec.parse(array.getJSONObject(it), chatId) }, data.optString("last_id"), data.optBoolean("is_last"))
    }
    override suspend fun send(session: Session, message: Message): Message {
        val body = JSONObject().put("local_id", message.localId).put("message_content", message.content)
            .put("message_type", message.type).put("chat_id", message.chatId)
        val data = call("https://bbs-api.miyoushe.com/chat/api/sendMessage", body, session)
        return MessageCodec.receipt(data, message)
    }
    suspend fun clearUnread(session: Session, chatId: String) {
        call("https://bbs-api.miyoushe.com/chat/api/clearChatUnread", JSONObject().put("chat_id", chatId), session)
    }
    suspend fun profile(session: Session): AccountProfile {
        val url = "https://bbs-api.miyoushe.com/user/api/getUserFullInfo".toHttpUrl().newBuilder()
            .addQueryParameter("uid",session.aid).build()
        return AccountProfiles.parse(call(url.toString(),session = session),session.aid)
    }
    suspend fun createQr(deviceId: String): QrLogin {
        val data = call("https://passport-api.mihoyo.com/account/ma-cn-passport/app/createQRLogin", JSONObject(), deviceId = deviceId, passport = true)
        val url = data.getString("url").toHttpUrl()
        check(url.isHttps && (url.host == "user.mihoyo.com" || url.host == "user.miyoushe.com"))
        return QrLogin(data.getString("ticket"), url.toString(), deviceId)
    }
    suspend fun queryQr(qr: QrLogin): Pair<String, Session?> {
        val data = call("https://passport-api.mihoyo.com/account/ma-cn-passport/app/queryQRLoginStatus", JSONObject().put("ticket", qr.ticket), deviceId = qr.deviceId, passport = true)
        if (data.optString("status") != "Confirmed") return data.optString("status") to null
        val user = data.getJSONObject("user_info"); val tokens = data.getJSONArray("tokens")
        val matches = (0 until tokens.length()).map { tokens.getJSONObject(it) }.filter { it.optInt("token_type") == 1 }
        check(matches.size == 1)
        return "Confirmed" to Session(user.getString("aid"), user.getString("mid"), matches.single().getString("token"), qr.deviceId)
    }
    private fun rsa(value: String): String {
        val modulus = BigInteger("c3bde91d3cc1cddc06219bfbe4b494fe609afb708e4372c34aa9db31e43657d200ee585b888f377006eb6b2183cd9912751bcc9b0c817ba035b6784a66e6c31b2fdcecf44c5709dbeaae7e75a842dbaa3d17c6d3132296821c5488e743df3e94c557d5edfe19b2570a24a0e5c59401200a7f900a01ace766c5a1832dca2fb111", 16)
        val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, BigInteger.valueOf(65537)))
        return Base64.encodeToString(Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, key) }.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }
    suspend fun sms(phone: String, deviceId: String, aigis: String = ""): String {
        val body = JSONObject().put("area_code", rsa("+86")).put("mobile", rsa(phone))
        return call("https://passport-api.mihoyo.com/account/ma-cn-verifier/verifier/createLoginCaptcha", body, deviceId = deviceId, passport = true, aigis = aigis).getString("action_type")
    }
    suspend fun loginSms(phone: String, captcha: String, action: String, deviceId: String, aigis: String = ""): Session {
        val body = JSONObject().put("area_code", rsa("+86")).put("mobile", rsa(phone)).put("captcha", captcha).put("action_type", action)
        val data = call("https://passport-api.mihoyo.com/account/ma-cn-passport/app/loginByMobileCaptcha", body, deviceId = deviceId, passport = true, aigis = aigis)
        val user = data.getJSONObject("user_info")
        val token = data.getJSONObject("token"); check(token.optInt("token_type", 1) == 1)
        return Session(user.getString("aid"), user.getString("mid"), token.getString("token"), deviceId)
    }
    suspend fun emoticons(): List<Emoticon> {
        val data = call("https://bbs-api-static.miyoushe.com/misc/api/getStaticEmoticonList")
        val groups = data.optJSONArray("list") ?: JSONArray()
        return buildList {
            for (i in 0 until groups.length()) {
                val group = groups.getJSONObject(i)
                if (!group.optBoolean("is_available", true) || group.optString("status", "published") != "published") continue
                val list = group.optJSONArray("list") ?: continue
                for (j in 0 until list.length()) {
                    val item = list.getJSONObject(j)
                    if (!item.optBoolean("is_available", true) || item.optString("status", "published") != "published") continue
                    val name = item.optString("name")
                    val icon = item.optString("static_icon").ifBlank { item.optString("icon") }
                    if (name.isNotBlank() && icon.startsWith("https://")) add(Emoticon(name, icon,
                        group.optString("id").ifBlank { "group:$i" }, group.optString("name").ifBlank { "表情包 ${i + 1}" }))
                }
            }
        }.distinctBy { it.name }
    }
    override suspend fun customEmoticons(session: Session): List<Emoticon> {
        val data = call("https://bbs-api.miyoushe.com/misc/api/customEmoticonManagePanel", session = session)
        val list = data.getJSONArray("list")
        val audit = data.optJSONArray("audit_list") ?: JSONArray()
        return (0 until list.length()).map { customEmoticon(list.getJSONObject(it)) }
            .plus((0 until audit.length()).map { customEmoticon(audit.getJSONObject(it)) }).distinctBy { it.key }
    }
    override suspend fun addCustomEmoticon(session: Session, uploaded: String): Emoticon? {
        val image = JSONObject(uploaded)
        val ext = image.getJSONObject("size").put("file_size", image.getLong("file_size"))
        val data = call("https://bbs-api.miyoushe.com/misc/api/addCustomEmoticon",
            JSONObject().put("img_url", image.getString("url")).put("img_ext", ext), session)
        return data.optJSONObject("custom_emoticon_info")?.let(::customEmoticon)
    }
    override suspend fun removeCustomEmoticon(session: Session, id: String) {
        require(id.isNotBlank())
        call("https://bbs-api.miyoushe.com/misc/api/removeCustomEmoticon", JSONObject().put("ids", JSONArray().put(id)), session)
    }
    private fun customEmoticon(item: JSONObject): Emoticon {
        val id = item.getString("id")
        val url = item.getString("icon").also { check(it.toHttpUrl().isHttps) }
        val ext = item.getJSONObject("img_ext")
        val content = JSONObject().put("id", id).put("url", url)
            .put("size", JSONObject().put("width", ext.getInt("width")).put("height", ext.getInt("height")))
            .put("file_size", ext.getLong("file_size")).put("thumbnail", "").toString()
        return Emoticon("自定义表情", url, "custom", "自定义", id, content, item.optString("audit_status"))
    }
    suspend fun uploadImage(session: Session, bytes: ByteArray, width: Int, height: Int): String {
        require(bytes.size <= 20 * 1024 * 1024 && width > 0 && height > 0)
        val md5 = java.security.MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
        val url = "https://bbs-api.miyoushe.com/apihub/sapi/getUploadParams".toHttpUrl().newBuilder()
            .addQueryParameter("md5", md5).addQueryParameter("ext", "jpg")
            .addQueryParameter("support_content_type", "1").addQueryParameter("upload_source", "2").build()
        val data = call(url.toString(), session = session)
        val parameters = data.getJSONObject("params")
        val host = parameters.getString("host").toHttpUrl()
        check(host.isHttps)
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("name", data.getString("file_name"))
            .addFormDataPart("key", parameters.getString("dir") + parameters.getString("name"))
            .addFormDataPart("callback", parameters.getString("callback"))
            .addFormDataPart("success_action_status", "200")
            .addFormDataPart("x:extra", parameters.getJSONObject("callback_var").getString("x:extra"))
            .addFormDataPart("OSSAccessKeyId", parameters.getString("accessid"))
            .addFormDataPart("policy", parameters.getString("policy"))
            .addFormDataPart("signature", parameters.getString("signature"))
        parameters.optString("x_oss_content_type").takeIf { it.isNotBlank() }?.let { multipart.addFormDataPart("x-oss-content-type", it) }
        parameters.optString("content_disposition").takeIf { it.isNotBlank() }?.let { multipart.addFormDataPart("Content-Disposition", it) }
        parameters.optJSONArray("extra_form_data")?.let { extras ->
            for (i in 0 until extras.length()) { val field = extras.getJSONObject(i); multipart.addFormDataPart(field.getString("key"), field.getString("value")) }
        }
        // Upload credentials are supplied by the official API for this object;
        // never forward the account Cookie or passport headers to object storage.
        multipart.addFormDataPart("file", data.getString("file_name"), bytes.toRequestBody("image/jpeg".toMediaType()))
        val uploaded = withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(host).post(multipart.build()).build()).execute().use { response ->
                if (!response.isSuccessful) throw ApiFailure(response.code)
                val result = JSONObject(response.body?.string().orEmpty())
                result.getJSONObject("data").getString("url").also { check(it.toHttpUrl().isHttps) }
            }
        }
        return JSONObject().put("url", uploaded).put("size", JSONObject().put("width", width).put("height", height))
            .put("file_size", bytes.size).put("thumbnail", "").put("localPath", "").toString()
    }
}

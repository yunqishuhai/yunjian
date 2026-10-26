package io.github.miyouchat.update

import org.json.JSONObject
import java.net.URI

data class ReleaseRepository(val owner: String,val name: String) {
    val slug get() = "$owner/$name"
    val releasesUrl get() = "https://github.com/$slug/releases"
    val apiUrl get() = "https://api.github.com/repos/$slug/releases/latest"
    fun ownsAsset(url: String, tag: String): Boolean = runCatching {
        val value = URI(url)
        value.scheme == "https" && value.host == "github.com" && value.port == -1 && value.userInfo == null && value.fragment == null &&
            value.rawPath.startsWith("/$slug/releases/download/$tag/") && value.rawQuery == null && !value.rawPath.contains("%") &&
            !value.rawPath.contains("/../") && value.rawPath.substringAfterLast('/').matches(Regex("[A-Za-z0-9_.-]+"))
    }.getOrDefault(false)
    companion object {
        fun parse(value: String): ReleaseRepository? {
            val parts = value.trim().split('/')
            return if (parts.size == 2 && parts[0].matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")) &&
                parts[1].matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}")) && parts[1] !in setOf(".","..")) ReleaseRepository(parts[0],parts[1]) else null
        }
    }
}

data class PublishedRelease(val tag: String,val notes: String,val manifestUrl: String,val assets: Map<String,String>)
data class AppRelease(val versionCode: Long,val versionName: String,val minSdk: Int,val apkName: String,
    val apkUrl: String,val bytes: Long,val sha256: String,val notes: String,val pageUrl: String)

object ReleaseManifest {
    const val MAX_APK_BYTES = 200L*1024*1024
    fun published(repository: ReleaseRepository,json: String): PublishedRelease {
        val value = JSONObject(json)
        require(!value.optBoolean("draft") && !value.optBoolean("prerelease")) { "仅检查已发布的正式版本" }
        val tag = value.getString("tag_name")
        require(tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+"))) { "发布标签格式不支持" }
        val entries = value.getJSONArray("assets")
        val assets = (0 until entries.length()).map { index ->
            val asset = entries.getJSONObject(index)
            val name = asset.getString("name"); val url = asset.getString("browser_download_url")
            require(repository.ownsAsset(url,tag)) { "发布附件地址不匹配" }
            name to url
        }
        require(assets.map { it.first }.distinct().size == assets.size) { "发布附件名称重复" }
        val map = assets.toMap()
        return PublishedRelease(tag,value.optString("body").take(12000),map["update.json"] ?: error("该版本缺少更新清单"),map)
    }
    fun parse(repository: ReleaseRepository,published: PublishedRelease,json: String): AppRelease {
        val value = JSONObject(json)
        require(value.getInt("schemaVersion") == 1) { "更新清单版本不支持" }
        val name = value.getString("versionName")
        require("v$name" == published.tag) { "版本信息不一致" }
        val code = value.getLong("versionCode"); require(code > 0)
        val minSdk = value.getInt("minSdk"); require(minSdk >= 26)
        val apkName = value.getString("apkName")
        require(apkName == "yunjian-$name.apk") { "安装包名称不匹配" }
        val bytes = value.getLong("bytes"); require(bytes in 1..MAX_APK_BYTES) { "安装包大小不支持" }
        val sha = value.getString("sha256").lowercase(); require(sha.matches(Regex("[a-f0-9]{64}"))) { "更新清单缺少 SHA-256" }
        return AppRelease(code,name,minSdk,apkName,published.assets[apkName] ?: error("该版本缺少安装包"),bytes,sha,published.notes,
            "${repository.releasesUrl}/tag/${published.tag}")
    }
}

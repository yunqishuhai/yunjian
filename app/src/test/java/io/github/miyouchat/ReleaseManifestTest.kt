package io.github.miyouchat

import io.github.miyouchat.update.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseManifestTest {
    private val repo = ReleaseRepository.parse("example/yunjian")!!
    private fun response(change: (JSONObject) -> Unit = {}): String = JSONObject().apply {
        put("tag_name","v1.2.3"); put("draft",false); put("prerelease",false); put("body","Update notes")
        put("assets",JSONArray().apply {
            for (name in listOf("update.json","yunjian-1.2.3.apk","SHA256SUMS")) put(JSONObject().put("name",name)
                .put("browser_download_url","https://github.com/example/yunjian/releases/download/v1.2.3/$name"))
        }); change(this)
    }.toString()
    private fun manifest(change: (JSONObject) -> Unit = {}): String = JSONObject().apply {
        put("schemaVersion",1); put("versionCode",123); put("versionName","1.2.3"); put("minSdk",26)
        put("apkName","yunjian-1.2.3.apk"); put("bytes",1024); put("sha256","a".repeat(64)); change(this)
    }.toString()
    @Test fun stableManifestUsesNumericVersionAndExactPublishedAsset() {
        val release = ReleaseManifest.parse(repo,ReleaseManifest.published(repo,response()),manifest())
        assertEquals(123L,release.versionCode); assertEquals("1.2.3",release.versionName)
        assertEquals("https://github.com/example/yunjian/releases/download/v1.2.3/yunjian-1.2.3.apk",release.apkUrl)
        assertEquals("Update notes",release.notes)
    }
    @Test fun repositoryAndAssetsRejectExternalHostsCredentialsAndTraversal() {
        for (value in listOf("", "https://github.com/example/yunjian","a/b/c","a/..","../yunjian","a/b?x")) assertNull(ReleaseRepository.parse(value))
        for (url in listOf("http://github.com/example/yunjian/releases/download/v1.2.3/x.apk",
            "https://github.com.evil.test/example/yunjian/releases/download/v1.2.3/x.apk",
            "https://user@github.com/example/yunjian/releases/download/v1.2.3/x.apk",
            "https://github.com/other/yunjian/releases/download/v1.2.3/x.apk",
            "https://github.com/example/yunjian/releases/download/v1.2.3/%2e%2e/x.apk",
            "https://github.com/example/yunjian/releases/download/v1.2.3/../x.apk")) assertFalse(repo.ownsAsset(url,"v1.2.3"))
    }
    @Test fun draftsPrereleasesAndUnknownTagsAreRejected() {
        for (json in listOf(response { it.put("draft",true) },response { it.put("prerelease",true) },response { it.put("tag_name","main") })) {
            assertThrows(Exception::class.java) { ReleaseManifest.published(repo,json) }
        }
    }
    @Test fun incompleteDuplicateOrForeignReleaseAssetsAreRejected() {
        for (json in listOf(response { it.put("assets",JSONArray()) },response {
            val array = it.getJSONArray("assets"); array.put(array.getJSONObject(0))
        },response { it.getJSONArray("assets").getJSONObject(0).put("browser_download_url","https://example.org/update.json") })) {
            assertThrows(Exception::class.java) { ReleaseManifest.published(repo,json) }
        }
    }
    @Test fun malformedOrMismatchedManifestsCannotOfferAnUpdate() {
        val published = ReleaseManifest.published(repo,response())
        for (json in listOf(manifest { it.put("schemaVersion",2) },manifest { it.put("versionName","1.2.4") },
            manifest { it.put("apkName","other.apk") },manifest { it.put("sha256","invalid") },manifest { it.put("bytes",0) },
            manifest { it.put("bytes",ReleaseManifest.MAX_APK_BYTES+1) },manifest { it.put("versionCode",0) },manifest { it.put("minSdk",1) })) {
            assertThrows(Exception::class.java) { ReleaseManifest.parse(repo,published,json) }
        }
    }
}

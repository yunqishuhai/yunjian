package io.github.miyouchat.data

import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object AccountProfiles {
    const val CACHE_AGE = 6 * 60 * 60 * 1000L
    fun parse(data: JSONObject, expectedUid: String, now: Long = System.currentTimeMillis()): AccountProfile {
        val user = data.getJSONObject("user_info")
        val uid = user.optString("uid")
        check(uid == expectedUid) { "User profile does not match the saved account" }
        val avatar = user.optString("avatar_url").toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }?.toString().orEmpty()
        return AccountProfile(uid,user.optString("nickname").trim(),avatar,now)
    }
    fun fresh(profile: AccountProfile?, now: Long = System.currentTimeMillis()) = profile != null && now - profile.updatedAt in 0 until CACHE_AGE
}

package io.github.miyouchat

import io.github.miyouchat.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountProfilesTest {
    private fun data(uid: Any = "101", avatar: String = "https://upload-bbs.miyoushe.com/profile.png") = JSONObject().put("user_info",JSONObject().put("uid",uid).put("nickname","云间").put("avatar_url",avatar))
    @Test fun parsesStringAndNumericCommunityUid() {
        for (uid in listOf<Any>("101",101L)) {
            val profile = AccountProfiles.parse(data(uid),"101",100)
            assertEquals("101",profile.uid); assertEquals("云间",profile.nickname)
            assertEquals("https://upload-bbs.miyoushe.com/profile.png",profile.avatarUrl)
        }
    }
    @Test fun mismatchedIdentityCannotOverwriteSavedAccount() {
        try { AccountProfiles.parse(data("202"),"101"); fail("Must reject a different user's profile") } catch (_: IllegalStateException) { }
    }
    @Test fun unsafeOrMissingAvatarFallsBackWithoutLosingName() {
        for (url in listOf("","http://example.com/image.png","https://name:secret@example.com/image.png","javascript:alert(1)")) {
            val profile = AccountProfiles.parse(data(avatar = url),"101")
            assertEquals("",profile.avatarUrl); assertEquals("云间",profile.nickname)
        }
    }
    @Test fun cachedNameAndRemarkStaySeparateAndExpirePredictably() {
        val profile = AccountProfile("101","云间","https://upload-bbs.miyoushe.com/a.png",1000)
        val account = AccountSummary("101","工作账号",true,profile)
        assertEquals("云间",account.displayName); assertEquals("工作账号",account.remark); assertEquals("101",account.uid)
        assertEquals("",account.copy(label = "账号 101").remark)
        assertTrue(AccountProfiles.fresh(profile,1001)); assertFalse(AccountProfiles.fresh(profile,1000+AccountProfiles.CACHE_AGE))
        assertFalse(AccountProfiles.fresh(profile,999)); assertFalse(AccountProfiles.fresh(null,1000))
    }
}

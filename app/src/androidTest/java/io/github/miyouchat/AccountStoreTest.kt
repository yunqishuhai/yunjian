package io.github.miyouchat

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import io.github.miyouchat.data.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.security.KeyStore
import javax.crypto.Cipher

class AccountStoreTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val isolated = object : ContextWrapper(target) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences("test-account-vault-$name",mode)
    }
    private val prefs get() = isolated.getSharedPreferences("account",Context.MODE_PRIVATE)
    @Before fun setup() { prefs.edit().clear().commit() }
    @After fun done() { prefs.edit().clear().commit() }
    @Test fun accountsSwitchWithoutMixingSessionsAndLogoutKeepsOtherAccount() {
        val a = Session("synthetic-a","fake-mid-a","fake-token-a","device-a")
        val b = Session("synthetic-b","fake-mid-b","fake-token-b","device-b")
        val store = SessionStore(isolated); store.save(a); store.save(b)
        assertEquals(b,store.load()); store.select(a.aid); store.rename(a.aid,"第一个账号")
        val restored = SessionStore(isolated)
        assertEquals(a,restored.load()); assertEquals(2,restored.accounts().size)
        assertEquals("第一个账号",restored.accounts().first { it.id == a.aid }.label)
        restored.clear(); assertNull(restored.load()); assertEquals(1,restored.accounts().size)
        restored.select(b.aid); assertEquals(b,restored.load())
        val encoded = prefs.getString("accounts_v1","").orEmpty()
        assertFalse(encoded.contains("fake-token")); assertFalse(encoded.contains("fake-mid"))
    }
    @Test fun addingAccountPreservesExistingLoginWithoutKeepingItActive() {
        val a = Session("synthetic-a","fake-mid","fake-token","device")
        val store = SessionStore(isolated); store.save(a); store.backgroundSync = true; store.addAnother()
        assertNull(store.load()); assertEquals(1,store.accounts().size); assertTrue(store.backgroundSync)
        store.select(a.aid); assertEquals(a,store.load())
    }
    @Test fun oldSingleAccountEntryMigratesOnlyInsideEncryptedVault() {
        val value = Session("legacy-a","fake-mid","fake-token","device")
        SessionStore(isolated).save(value) // Creates the app-owned key if needed; fixture uses isolated prefs only.
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("miyou-chat-session-v1",null)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key) }
        val json = JSONObject().put("aid",value.aid).put("mid",value.mid).put("stoken",value.stoken).put("deviceId",value.deviceId).toString()
        val legacy = Base64.encodeToString(cipher.iv + cipher.doFinal(json.toByteArray()),Base64.NO_WRAP)
        prefs.edit().remove("accounts_v1").putString("session",legacy).commit()
        val migrated = SessionStore(isolated)
        assertEquals(value,migrated.load()); assertEquals(1,migrated.accounts().size)
        assertFalse(prefs.contains("session")); assertTrue(prefs.contains("accounts_v1"))
    }
    @Test fun profileMigrationRefreshAndReloginPreserveTheRightAccountAndRemark() {
        val a = Session("101","fake-mid-a","fake-token-a","device")
        val b = Session("202","fake-mid-b","fake-token-b","device")
        val store = SessionStore(isolated); store.save(a); store.save(b)
        assertTrue(store.accounts().all { it.profile == null })
        val profile = AccountProfile("101","云间","https://upload-bbs.miyoushe.com/a.png",1000)
        store.rename(a.aid,"备用账号"); store.updateProfile(a.aid,profile)
        assertEquals(b,store.load()) // Fetching another profile must not switch accounts.
        store.save(a.copy(stoken = "fake-refreshed-token"))
        val restored = SessionStore(isolated)
        val summary = restored.accounts().first { it.id == a.aid }
        assertEquals(profile,summary.profile); assertEquals("备用账号",summary.remark)
        assertNull(restored.accounts().first { it.id == b.aid }.profile)
        assertFalse(prefs.getString("accounts_v1","").orEmpty().contains("云间"))
        try { restored.updateProfile(b.aid,profile); fail("Wrong UID must be rejected") } catch (_: IllegalStateException) { }
        restored.clear(); restored.updateProfile(a.aid,profile)
        assertEquals(1,restored.accounts().size) // Late responses must not restore a logged-out account.
    }
}

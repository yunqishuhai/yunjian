package io.github.miyouchat.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import org.json.JSONArray
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val alias = "miyou-chat-session-v1"
    private var restored = false
    private data class Saved(val session: Session, val label: String, val profile: AccountProfile? = null)
    private val accounts = linkedMapOf<String, Saved>()
    private var active: String? = null
    val deviceId: String = preferences.getString("device_id", null) ?: UUID.randomUUID().toString().uppercase().also {
        preferences.edit().putString("device_id", it).commit()
    }
    var backgroundSync: Boolean
        get() = preferences.getBoolean("background_sync", false)
        set(value) { preferences.edit().putBoolean("background_sync", value).apply() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun save(session: Session) {
        restore()
        val old = accounts[session.aid]
        accounts[session.aid] = Saved(session, old?.label ?: "账号 ${session.aid.takeLast(4)}",old?.profile)
        active = session.aid; persist()
    }
    private fun encrypt(json: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val value = cipher.iv + cipher.doFinal(json.toByteArray())
        return Base64.encodeToString(value, Base64.NO_WRAP)
    }
    private fun decrypt(encoded: String): JSONObject {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size))))
    }
    private fun session(json: JSONObject) = Session(json.getString("aid"),json.getString("mid"),json.getString("stoken"),json.getString("deviceId"))
    private fun restore() {
        if (restored) return
        val vault = preferences.getString("accounts_v1", null)
        if (vault != null) runCatching {
            val json = decrypt(vault)
            val list = json.getJSONArray("list")
            for (index in 0 until list.length()) {
                val value = list.getJSONObject(index); val session = session(value)
                val profile = value.optJSONObject("profile")?.takeIf { it.optString("uid") == session.aid }?.let {
                    AccountProfile(it.getString("uid"),it.optString("nickname"),it.optString("avatar_url"),it.optLong("updated_at"))
                }
                accounts[session.aid] = Saved(session,value.optString("label", "账号 ${session.aid.takeLast(4)}"),profile)
            }
            active = json.optString("active").takeIf { accounts.containsKey(it) }
        }.onFailure { accounts.clear(); active = null }
        else preferences.getString("session", null)?.let { encoded -> runCatching {
            val old = session(decrypt(encoded)); accounts[old.aid] = Saved(old,"账号 ${old.aid.takeLast(4)}"); active = old.aid
            persist() // Migrate only after successfully decrypting the old entry.
        } }
        restored = true
    }
    private fun persist() {
        val list = JSONArray()
        accounts.values.forEach { saved -> saved.session.let {
            val row = JSONObject().put("aid",it.aid).put("mid",it.mid).put("stoken",it.stoken).put("deviceId",it.deviceId).put("label",saved.label)
            saved.profile?.let { profile -> row.put("profile",JSONObject().put("uid",profile.uid).put("nickname",profile.nickname).put("avatar_url",profile.avatarUrl).put("updated_at",profile.updatedAt)) }
            list.put(row)
        } }
        check(preferences.edit().putString("accounts_v1",encrypt(JSONObject().put("active",active ?: JSONObject.NULL).put("list",list).toString())).remove("session").commit())
    }
    @Synchronized fun load(): Session? { restore(); return accounts[active]?.session }
    @Synchronized fun accounts(): List<AccountSummary> { restore(); return accounts.map { (id,value) -> AccountSummary(id,value.label,id == active,value.profile) } }
    @Synchronized internal fun savedSessions(): List<Session> { restore(); return accounts.values.map { it.session } }
    @Synchronized fun updateProfile(id: String, profile: AccountProfile) {
        restore(); val saved = accounts[id] ?: return
        check(profile.uid == id)
        accounts[id] = saved.copy(profile = profile); persist()
    }
    @Synchronized fun select(id: String) { restore(); check(accounts.containsKey(id)); active = id; persist() }
    @Synchronized fun rename(id: String, label: String) { restore(); val saved = accounts[id] ?: return; accounts[id] = saved.copy(label = label.trim().take(24).ifBlank { "账号 ${id.takeLast(4)}" }); persist() }
    @Synchronized fun addAnother() { restore(); active = null; persist() }
    @Synchronized fun clear() {
        restore(); accounts.remove(active); active = null
        backgroundSync = false; persist()
    }
}

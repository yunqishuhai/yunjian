package io.github.miyouchat.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Official catalogue is public; personal groups are isolated by signed-in account. */
class EmoticonLibrary(context: Context) {
    private val preferences = context.getSharedPreferences("emoticon_groups", Context.MODE_PRIVATE)
    fun load(account: String): List<FavoriteGroup> = runCatching {
        val array = JSONArray(preferences.getString(MessageCodec.md5(account), "[]"))
        (0 until array.length()).map { index ->
            val group = array.getJSONObject(index)
            FavoriteGroup(group.getString("id"), group.getString("name"), decode(group.getJSONArray("items")))
        }
    }.getOrDefault(emptyList()).ifEmpty { listOf(FavoriteGroup("favorites", "默认收藏")) }

    fun save(account: String, groups: List<FavoriteGroup>) {
        check(account.isNotBlank())
        val array = JSONArray()
        groups.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("items", encode(it.items))) }
        check(preferences.edit().putString(MessageCodec.md5(account), array.toString()).commit())
    }
    companion object {
        fun newGroup(name: String) = FavoriteGroup(UUID.randomUUID().toString(), name.trim().take(24))
        fun encode(items: List<Emoticon>): JSONArray = JSONArray().apply {
            items.forEach { put(JSONObject().put("name", it.name).put("url", it.url)
                .put("group_id", it.groupId).put("group_name", it.groupName)
                .put("custom_id", it.customId).put("content", it.content).put("audit_status", it.auditStatus)) }
        }
        fun decode(array: JSONArray): List<Emoticon> = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Emoticon(item.getString("name"), item.getString("url"), item.optString("group_id", "default"),
                item.optString("group_name", "其他"), item.optString("custom_id"), item.optString("content"), item.optString("audit_status"))
        }
    }
}

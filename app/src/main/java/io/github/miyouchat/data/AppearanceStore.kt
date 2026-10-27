package io.github.miyouchat.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemePalette(val id: String, val title: String, val description: String) {
    MIST_BLUE("mist_blue", "雾蓝", "柔和雾蓝，清爽冷白"),
    ICE_BLUE("ice_blue", "冰蓝", "通透冰蓝，轻盈雪白"),
    BAMBOO("bamboo", "竹青", "竹叶青绿，安静自然"),
    LILAC("lilac", "浅紫", "淡雅紫调，柔和舒适"),
    BLOSSOM("blossom", "樱粉", "浅樱粉色，温暖明净");
    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: MIST_BLUE }
}
enum class ThemeMode(val id: String, val title: String) {
    SYSTEM("system", "跟随系统"), LIGHT("light", "浅色"), DARK("dark", "深色");
    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: SYSTEM }
}
data class Appearance(val palette: ThemePalette = ThemePalette.MIST_BLUE, val mode: ThemeMode = ThemeMode.SYSTEM) {
    fun dark(systemDark: Boolean) = when (mode) { ThemeMode.SYSTEM -> systemDark; ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
}

/** Device-wide appearance stays independent of the selected chat account. */
class AppearanceStore(context: Context) {
    private val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(Appearance(ThemePalette.fromId(preferences.getString("palette", null)), ThemeMode.fromId(preferences.getString("mode", null))))
    val state = mutable.asStateFlow()
    fun set(value: Appearance) {
        preferences.edit().putString("palette", value.palette.id).putString("mode", value.mode.id).apply()
        mutable.value = value
    }
}

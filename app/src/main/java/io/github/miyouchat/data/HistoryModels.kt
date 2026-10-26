package io.github.miyouchat.data

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.Instant

enum class SenderFilter { ALL, SELF, OTHER }
enum class MessageFilter { ALL, TEXT, IMAGES }
data class HistoryFilter(val keyword: String = "", val chatIds: Set<String> = emptySet(),
    val from: Long? = null, val until: Long? = null, val sender: SenderFilter = SenderFilter.ALL,
    val type: MessageFilter = MessageFilter.ALL)
data class SearchCursor(val time: Long, val key: String)
data class SearchHit(val message: Message, val chatName: String)
data class SearchPage(val hits: List<SearchHit>, val next: SearchCursor?)
data class HistoryCoverage(val chatId: String, val name: String, val cached: Int, val complete: Boolean)
data class AccountProfile(val uid: String, val nickname: String, val avatarUrl: String, val updatedAt: Long)
data class AccountSummary(val id: String, val label: String, val active: Boolean, val profile: AccountProfile? = null) {
    val displayName: String get() = profile?.nickname?.takeIf { it.isNotBlank() } ?: label
    val uid: String get() = profile?.uid ?: id
    val avatarUrl: String get() = profile?.avatarUrl.orEmpty()
    val remark: String get() = label.takeUnless { it == "账号 ${id.takeLast(4)}" || it == displayName }.orEmpty()
}

object HistoryDates {
    // Material date pickers represent calendar dates at UTC midnight, not local midnight.
    fun pickerMillis(date: String): Long? = date.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
    fun pickerDate(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
    fun range(from: String, until: String, zone: ZoneId = ZoneId.systemDefault()): Pair<Long?, Long?> {
        val start = from.trim().takeIf { it.isNotBlank() }?.let(LocalDate::parse)
        val end = until.trim().takeIf { it.isNotBlank() }?.let(LocalDate::parse)
        require(start == null || end == null || !start.isAfter(end)) { "开始日期不能晚于结束日期" }
        return start?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() to
            end?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
    }
    fun likeLiteral(value: String) = "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
}

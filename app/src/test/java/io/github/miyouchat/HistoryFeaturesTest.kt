package io.github.miyouchat

import io.github.miyouchat.data.*
import io.github.miyouchat.sync.IncomingPolicy
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class HistoryFeaturesTest {
    @Test fun endDateIncludesEntireLocalDay() {
        val range = HistoryDates.range("2026-10-25","2026-10-25",ZoneId.of("Asia/Shanghai"))
        assertEquals(Instant.parse("2026-10-24T16:00:00Z").toEpochMilli(),range.first)
        assertEquals(Instant.parse("2026-10-25T16:00:00Z").toEpochMilli(),range.second)
    }
    @Test fun calendarRangeHandlesDaylightSavingWithoutAssuming24Hours() {
        val range = HistoryDates.range("2026-03-08","2026-03-08",ZoneId.of("America/New_York"))
        assertEquals(23*60*60*1000L,range.second!!-range.first!!)
    }
    @Test fun invalidRangeIsRejected() {
        for (range in listOf("2026-10-26" to "2026-10-25","2026-02-30" to "")) {
            try { HistoryDates.range(range.first,range.second); fail("Invalid date must not silently widen export") } catch (_: Exception) { }
        }
    }
    @Test fun notificationPolicySuppressesCurrentChatMuteAndDisabled() {
        assertFalse(IncomingPolicy.show(true,true,"chat","chat",false))
        assertFalse(IncomingPolicy.show(true,false,null,"chat",true))
        assertFalse(IncomingPolicy.show(false,false,null,"chat",false))
        assertTrue(IncomingPolicy.show(true,true,"other","chat",false))
        assertTrue(IncomingPolicy.show(true,false,null,"chat",false))
    }
    @Test fun initialSyncOldHistoryAndOwnMessagesDoNotNotify() {
        val chat = Chat("chat","other","name","","",2000,1)
        val incoming = Message("new","local","chat","other",9,"{}",2000)
        assertNull(IncomingPolicy.newest("self",null,chat,listOf(incoming),false))
        assertEquals(incoming,IncomingPolicy.newest("self",null,chat,listOf(incoming),true))
        assertNull(IncomingPolicy.newest("self",chat,chat,listOf(incoming.copy(time = 1000)),true))
        assertNull(IncomingPolicy.newest("self",chat,chat,listOf(incoming.copy(senderId = "self")),true))
    }
    @Test fun backgroundConversationMustNotMarkIncomingMessagesRead() {
        assertTrue(IncomingPolicy.markRead(true,"chat","chat"))
        assertFalse(IncomingPolicy.markRead(false,"chat","chat"))
        assertFalse(IncomingPolicy.markRead(true,"other","chat"))
        assertFalse(IncomingPolicy.markRead(true,null,"chat"))
    }
    @Test fun pickerCalendarDateDoesNotShiftWithPhoneTimeZone() {
        val selected = HistoryDates.pickerMillis("2026-10-25")!!
        assertEquals(Instant.parse("2026-10-25T00:00:00Z").toEpochMilli(),selected)
        val date = HistoryDates.pickerDate(selected)
        assertEquals("2026-10-25",date)
        for (zone in listOf("Asia/Shanghai","America/Los_Angeles","Pacific/Auckland")) {
            val local = ZoneId.of(zone)
            val range = HistoryDates.range(date,date,local)
            assertEquals(date,Instant.ofEpochMilli(range.first!!).atZone(local).toLocalDate().toString())
            assertEquals("2026-10-26",Instant.ofEpochMilli(range.second!!).atZone(local).toLocalDate().toString())
        }
    }
}

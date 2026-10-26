package io.github.miyouchat

import androidx.test.platform.app.InstrumentationRegistry
import io.github.miyouchat.data.*
import org.junit.*
import org.junit.Assert.*

class HistoryDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: ChatDatabase
    @Before fun setup() { context.deleteDatabase("history-features-test.db"); db = ChatDatabase(context,"history-features-test.db") }
    @After fun done() { db.close(); context.deleteDatabase("history-features-test.db") }
    private fun message(id: String,text: String,time: Long = 1000,sender: String = "a",chat: String = "chat") = Message(id,"local-$id",chat,sender,9,MessageCodec.textContent(text),time)
    @Test fun searchTreatsWildcardsAndUnicodeLiterallyAndIsolatesAccounts() {
        db.saveMessages("a",listOf(message("1","你好%_\\测试"),message("2","你好OTHER测试")))
        db.saveMessages("b",listOf(message("1","你好%_\\测试")))
        assertEquals(listOf("1"),db.search("a",HistoryFilter(keyword = "%_\\")).hits.map { it.message.id })
        assertEquals(2,db.search("a",HistoryFilter(keyword = "你好")).hits.size)
        assertTrue(db.search("c",HistoryFilter()).hits.isEmpty())
    }
    @Test fun searchCombinesConversationDateSenderAndType() {
        db.saveMessages("a",listOf(message("1","target",1000),message("2","target",2000,"other"),message("3","target",3000),message("4","target",2000,"other","other-chat")))
        val filter = HistoryFilter("target",setOf("chat"),1500,3000,SenderFilter.OTHER,MessageFilter.TEXT)
        assertEquals("2",db.search("a",filter).hits.single().message.id)
        assertTrue(db.search("a",filter.copy(type = MessageFilter.IMAGES)).hits.isEmpty())
    }
    @Test fun stablePaginationDoesNotLoseEqualTimestampMessages() {
        db.saveMessages("a",(1..105).map { message(it.toString(),"same time") })
        val all = mutableListOf<String>(); var next: SearchCursor? = null
        do { val page = db.search("a",HistoryFilter(),next); all += page.hits.map { it.message.id }; next = page.next } while (next != null)
        assertEquals(105,all.size); assertEquals(105,all.toSet().size)
    }
    @Test fun pinAndMuteSurviveServerRefreshAndStayAccountScoped() {
        val old = Chat("chat","target","Name","","",1000,1)
        db.saveChats("a",listOf(old,old.copy(id = "newer",updatedAt = 2000)))
        db.setOptions("a","chat",true,true); db.saveChats("a",listOf(old.copy(updatedAt = 500)))
        assertEquals("chat",db.chats("a").first().id); assertTrue(db.chats("a").first().muted)
        assertFalse(db.muted("b","chat"))
    }
    @Test fun exportSnapshotContainsOnlyChosenAccountAndConfirmedMessages() {
        db.saveMessages("a",listOf(message("1","chosen"),message("","pending").copy(delivery = Delivery.REVIEW)))
        db.saveMessages("b",listOf(message("2","other account")))
        val file = java.io.File.createTempFile("synthetic-export-", ".jsonl",context.cacheDir)
        try { assertEquals(1,db.exportSnapshot("a",HistoryFilter(),file)); assertTrue(file.readText().contains("chosen")); assertFalse(file.readText().contains("other account")); assertFalse(file.readText().contains("pending")) }
        finally { file.delete() }
    }
}

package io.github.miyouchat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.miyouchat.data.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CacheIntegrityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: ChatDatabase
    @Before fun setup() { context.deleteDatabase("cache-integrity-test.db"); database = ChatDatabase(context, "cache-integrity-test.db") }
    @After fun teardown() { database.close(); context.deleteDatabase("cache-integrity-test.db") }
    @Test fun serverAcknowledgementReplacesPendingWithoutDuplicate() {
        val pending = Message("", "local-test", "chat-test", "a", 9, "{}", 1000, Delivery.SENDING)
        database.saveMessages("a", listOf(pending))
        database.saveMessages("a", listOf(pending.copy(id = "server-test", delivery = Delivery.SENT)))
        database.saveMessages("a", listOf(pending.copy(id = "server-test", delivery = Delivery.SENT)))
        val rows = database.messages("a", "chat-test")
        assertEquals(1, rows.size); assertEquals("server-test", rows.single().id); assertEquals(Delivery.SENT, rows.single().delivery)
    }
    @Test fun historiesAreOrderedAndIsolatedPerAccount() {
        val message = Message("same-server-id", "same-local-id", "same-chat-id", "a", 9, "first", 2000)
        database.saveMessages("a", listOf(message, message.copy(id = "older", localId = "older-local", time = 1000)))
        database.saveMessages("b", listOf(message.copy(content = "other account")))
        assertEquals(listOf(1000L, 2000L), database.messages("a", "same-chat-id").map { it.time })
        assertEquals("other account", database.messages("b", "same-chat-id").single().content)
        database.clearAccount("a")
        assertTrue(database.messages("a", "same-chat-id").isEmpty())
        assertEquals(1, database.messages("b", "same-chat-id").size)
    }
    @Test fun interruptedSendIsNotAutomaticallyRetried() {
        database.saveMessages("a", listOf(Message("", "local-test", "chat-test", "a", 9, "{}", 1000, Delivery.SENDING)))
        database.markInterrupted("a")
        assertEquals(Delivery.UNCERTAIN, database.messages("a", "chat-test").single().delivery)
    }
    @Test fun imageQueueAndOutboxSurviveReopenAndStayAccountIsolated() {
        val job = ImageJob("a", "image-local", "chat-test", 1000, ImagePhase.REVIEW, "{}", error = "图片审核中")
        database.saveImageJob(job)
        database.close(); database = ChatDatabase(context, "cache-integrity-test.db")
        assertEquals(job, database.imageJobs("a").single())
        assertTrue(database.imageJobs("b").isEmpty())
        assertEquals(Delivery.REVIEW, database.messages("a", "chat-test").single().delivery)
        database.finishImageJob(job.copy(cancelled = true, phase = ImagePhase.DONE))
        assertTrue(database.imageJobs("a").isEmpty())
        assertTrue(database.messages("a", "chat-test").isEmpty())
    }
    @Test fun versionOneUpgradeKeepsHistoryAndCreatesQueue() {
        val message = Message("server", "local", "chat-test", "a", 1, "history", 1000)
        database.close(); context.deleteDatabase("cache-integrity-test.db")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("cache-integrity-test.db"),null).use { old ->
            old.execSQL("CREATE TABLE messages(account TEXT,key TEXT,id TEXT,local_id TEXT,chat TEXT,sender TEXT,type INTEGER,content TEXT,time INTEGER,delivery TEXT,error TEXT,PRIMARY KEY(account,key))")
            old.execSQL("CREATE TABLE chats(account TEXT,id TEXT,target TEXT,name TEXT,avatar TEXT,preview TEXT,time INTEGER,unread INTEGER,PRIMARY KEY(account,id))")
            old.execSQL("CREATE TABLE cursors(account TEXT,chat TEXT,cursor TEXT,is_last INTEGER,PRIMARY KEY(account,chat))")
            old.execSQL("INSERT INTO messages VALUES('a','server','server','local','chat-test','a',1,'history',1000,'SENT','')")
            old.version = 1
        }
        database = ChatDatabase(context, "cache-integrity-test.db")
        assertEquals(message, database.messages("a", "chat-test").single())
        assertTrue(database.imageJobs("a").isEmpty())
        database.saveImageJob(ImageJob("a", "image-local", "chat-test", 2000))
        assertEquals(1, database.imageJobs("a").size)
        assertEquals("history", database.search("a",HistoryFilter(keyword = "history")).hits.single().message.content)
    }
}

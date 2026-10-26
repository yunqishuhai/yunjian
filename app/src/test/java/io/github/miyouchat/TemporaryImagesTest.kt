package io.github.miyouchat

import io.github.miyouchat.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TemporaryImagesTest {
    private val session = Session("a", "test-mid", "test-token", "test-device")
    private val uploaded = """{"url":"https://example.test/image.jpg","size":{"width":600,"height":600},"file_size":123}"""
    private fun item(status: String = "CustomEmoticonAuditStatusPass") = Emoticon("图片", "https://example.test/image.jpg", "custom", "自定义", "item-id",
        JSONObject(uploaded).put("id", "item-id").put("thumbnail", "").toString(), status)
    private class MemoryStore : ImageJobStore {
        val values = linkedMapOf<String, ImageJob>()
        var receipt: Message? = null
        override fun jobs(account: String) = values.values.filter { it.account == account }
        override fun save(job: ImageJob) { values[job.localId] = job }
        override fun finish(job: ImageJob) { values.remove(job.localId) }
        override fun observed(job: ImageJob) = receipt
    }
    private inner class FakeApi(val store: MemoryStore) : TemporaryImageApi {
        val items = mutableListOf<Emoticon>()
        val history = mutableListOf<Message>()
        var initialStatus = "CustomEmoticonAuditStatusPass"
        var added = 0; var sent = 0; var removed = 0
        var echoHistory = true; var deleteFails = false; var addTimeout = false; var sendCrash = false; var sendReject = false
        override suspend fun customEmoticons(session: Session) = items.toList()
        override suspend fun addCustomEmoticon(session: Session, uploaded: String): Emoticon {
            added++; val value = item(initialStatus); items += value
            if (addTimeout) throw IOException("ambiguous add")
            return value
        }
        override suspend fun removeCustomEmoticon(session: Session, id: String) {
            removed++; if (deleteFails) throw IOException("cleanup failed")
            items.removeAll { it.customId == id }
        }
        override suspend fun send(session: Session, message: Message): Message {
            assertEquals(ImagePhase.VERIFY, store.values[message.localId]!!.phase)
            sent++
            if (sendReject) throw ApiFailure(-1)
            val delivered = message.copy(id = "server-id", delivery = Delivery.SENT)
            if (echoHistory) history += delivered
            if (sendCrash) throw CancellationException("process interrupted after POST")
            return delivered
        }
        override suspend fun history(session: Session, chatId: String, cursor: String, size: Int) = HistoryPage(history.toList(), "", true)
    }
    private inner class Fixture {
        var now = 1000L
        val store = MemoryStore()
        val api = FakeApi(store)
        val queue = TemporaryImages(api, store, { uploaded }, { now })
        init { store.save(ImageJob("a", "local", "chat", now)) }
        suspend fun tick() { now += 61000; queue.tick(session) }
    }
    @Test fun pendingAuditWaitsThenSendsAndCleansOnlyAfterHistory() = runBlocking {
        val f = Fixture(); f.api.initialStatus = "CustomEmoticonAuditStatusPend"
        f.tick()
        assertEquals(Delivery.REVIEW, f.store.values["local"]!!.message().delivery)
        assertEquals("图片审核中", f.store.values["local"]!!.message().error)
        assertEquals(0, f.api.sent); assertEquals(0, f.api.removed)
        f.api.items[0] = item(); f.tick()
        assertEquals(1, f.api.sent); assertEquals(1, f.api.removed); assertTrue(f.store.values.isEmpty())
    }
    @Test fun existingLibraryItemIsNeverDeleted() = runBlocking {
        val f = Fixture(); f.api.items += item(); f.tick()
        assertEquals(0, f.api.added); assertEquals(1, f.api.sent); assertEquals(0, f.api.removed)
        assertEquals(1, f.api.items.size)
    }
    @Test fun acknowledgementWithoutHistoryDoesNotDeleteOrResend() = runBlocking {
        val f = Fixture(); f.api.echoHistory = false
        f.tick(); f.tick(); f.tick()
        assertEquals(ImagePhase.VERIFY, f.store.values["local"]!!.phase)
        assertEquals(1, f.api.sent); assertEquals(0, f.api.removed)
    }
    @Test fun cleanupFailureCannotResendMessage() = runBlocking {
        val f = Fixture(); f.api.deleteFails = true; f.tick()
        assertEquals(ImagePhase.CLEANUP, f.store.values["local"]!!.phase)
        f.api.deleteFails = false; f.tick()
        assertEquals(1, f.api.sent); assertEquals(2, f.api.removed); assertTrue(f.store.values.isEmpty())
    }
    @Test fun crashAfterPostReconcilesHistoryWithoutAnotherPost() = runBlocking {
        val f = Fixture(); f.api.sendCrash = true
        try { f.tick(); fail("Expected interrupted POST") } catch (_: CancellationException) { }
        assertEquals(ImagePhase.VERIFY, f.store.values["local"]!!.phase)
        f.api.sendCrash = false; f.tick()
        assertEquals(1, f.api.sent); assertEquals(1, f.api.removed)
    }
    @Test fun ambiguousAddIsNotRepeatedAndRecoveryDoesNotClaimOwnership() = runBlocking {
        val f = Fixture(); f.api.addTimeout = true; f.tick()
        assertEquals(ImagePhase.ADD_UNCERTAIN, f.store.values["local"]!!.phase)
        f.tick()
        assertEquals(1, f.api.added); assertEquals(1, f.api.sent); assertEquals(0, f.api.removed)
    }
    @Test fun rejectedAuditNeverSendsAndCanBeCancelled() = runBlocking {
        val f = Fixture(); f.api.initialStatus = "CustomEmoticonAuditStatusNoPass"; f.tick()
        assertEquals(Delivery.REJECTED, f.store.values["local"]!!.message().delivery)
        assertEquals(0, f.api.sent)
        f.queue.cancel(session, "local"); f.tick()
        assertEquals(1, f.api.removed); assertEquals(0, f.api.sent); assertTrue(f.store.values.isEmpty())
    }
    @Test fun explicitSendRejectionRequiresManualRetry() = runBlocking {
        val f = Fixture(); f.api.sendReject = true; f.tick(); f.tick()
        assertEquals(1, f.api.sent); assertEquals(ImagePhase.SEND_FAILED, f.store.values["local"]!!.phase)
        f.api.sendReject = false; f.queue.retry(session, "local"); f.tick()
        assertEquals(2, f.api.sent); assertEquals(1, f.api.removed)
    }
    @Test fun unrelatedHistoryCannotAuthorizeCleanup() = runBlocking {
        val job = ImageJob("a", "local", "chat", 1000, ImagePhase.VERIFY, uploaded, item(), true)
        val message = job.message().copy(id = "server", delivery = Delivery.SENT)
        assertTrue(TemporaryImages.confirms(job, message))
        for (other in listOf(message.copy(senderId = "other"), message.copy(chatId = "other"), message.copy(localId = "other"),
            message.copy(type = 3), message.copy(content = JSONObject(message.content).put("id", "other").toString())))
            assertFalse(TemporaryImages.confirms(job, other))
    }
    @Test fun durableJobRoundTripPreservesOwnershipAndUncertainty() {
        val job = ImageJob("a", "local", "chat", 1000, ImagePhase.VERIFY, uploaded, item(), true, "待确认", 3000)
        assertEquals(job, TemporaryImages.decode(TemporaryImages.encode(job)))
    }
    @Test fun newlySyncedHistoryConfirmsBeforeNextPollingDeadline() = runBlocking {
        val f = Fixture(); f.api.echoHistory = false; f.tick()
        val pending = f.store.values["local"]!!
        assertTrue(pending.nextCheck > f.now)
        f.store.receipt = pending.message().copy(id = "server-id", delivery = Delivery.SENT)
        f.queue.tick(session)
        assertEquals(1, f.api.sent); assertEquals(1, f.api.removed); assertTrue(f.store.values.isEmpty())
    }
}

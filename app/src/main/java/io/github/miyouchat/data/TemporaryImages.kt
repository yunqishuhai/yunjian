package io.github.miyouchat.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

enum class ImagePhase { UPLOAD, ADD, ADD_UNCERTAIN, REVIEW, READY, VERIFY, SEND_FAILED, REJECTED, CLEANUP, DONE }
data class ImageJob(val account: String, val localId: String, val chatId: String, val time: Long,
    val phase: ImagePhase = ImagePhase.UPLOAD, val uploaded: String = "", val item: Emoticon? = null,
    val owned: Boolean = false, val error: String = "", val nextCheck: Long = 0,
    val cancelled: Boolean = false, val receiptId: String = "") {
    fun message(): Message = Message(receiptId, localId, chatId, account, if (item == null) 3 else 8,
        item?.content ?: uploaded.ifBlank { "{}" }, time, when (phase) {
            ImagePhase.REVIEW, ImagePhase.ADD_UNCERTAIN -> Delivery.REVIEW
            ImagePhase.VERIFY -> Delivery.UNCERTAIN
            ImagePhase.SEND_FAILED -> Delivery.FAILED
            ImagePhase.REJECTED -> Delivery.REJECTED
            ImagePhase.CLEANUP, ImagePhase.DONE -> if (cancelled) Delivery.REJECTED else Delivery.SENT
            else -> Delivery.SENDING
        }, when (phase) {
            ImagePhase.UPLOAD -> error.ifBlank { "图片上传中" }
            ImagePhase.ADD -> error.ifBlank { "正在提交图片审核" }
            ImagePhase.ADD_UNCERTAIN -> "已提交，等待表情库确认"
            ImagePhase.REVIEW -> error.ifBlank { "图片审核中" }
            ImagePhase.READY -> "图片审核通过，准备发送"
            ImagePhase.VERIFY -> "等待历史确认，请勿重复发送"
            ImagePhase.CLEANUP -> if (cancelled) "已取消，临时表情清理中" else error.ifBlank { "已发送，临时表情清理中" }
            else -> error
        })
}

interface TemporaryImageApi {
    suspend fun customEmoticons(session: Session): List<Emoticon>
    suspend fun addCustomEmoticon(session: Session, uploaded: String): Emoticon?
    suspend fun removeCustomEmoticon(session: Session, id: String)
    suspend fun send(session: Session, message: Message): Message
    suspend fun history(session: Session, chatId: String, cursor: String = "", size: Int = 30): HistoryPage
}
interface ImageJobStore {
    fun jobs(account: String): List<ImageJob>
    /** Persist the transition and its visible outbox message in one transaction. */
    fun save(job: ImageJob)
    fun finish(job: ImageJob)
    fun observed(job: ImageJob): Message? = null
}

/** Durable transitions precede POST. An ambiguous POST is never automatically repeated. */
class TemporaryImages(private val api: TemporaryImageApi, private val store: ImageJobStore,
    private val upload: suspend (ImageJob) -> String, private val clock: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    suspend fun tick(session: Session) = mutex.withLock {
        for (job in store.jobs(session.aid).filter { it.phase !in setOf(ImagePhase.SEND_FAILED, ImagePhase.REJECTED, ImagePhase.DONE) }.take(20)) {
            if (job.nextCheck > clock() && !(job.phase == ImagePhase.VERIFY && store.observed(job)?.let { confirms(job, it) } == true)) continue
            try { advance(session, job) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                // Only annotate the latest committed transition, never roll an
                // uncertain add/send back into a state which repeats its POST.
                val latest = store.jobs(session.aid).firstOrNull { it.localId == job.localId } ?: continue
                store.save(latest.copy(error = if (failure is ApiFailure) failure.message.orEmpty() else "连接未完成，联网后继续处理", nextCheck = clock() + 15000))
            }
        }
    }
    suspend fun retry(session: Session, localId: String) = mutex.withLock {
        val job = store.jobs(session.aid).firstOrNull { it.localId == localId } ?: return@withLock
        if (job.phase == ImagePhase.SEND_FAILED) store.save(job.copy(phase = if (job.item == null) ImagePhase.ADD else ImagePhase.REVIEW, error = "", nextCheck = 0))
        else store.save(job.copy(nextCheck = 0))
    }
    suspend fun cancel(session: Session, localId: String) = mutex.withLock {
        val job = store.jobs(session.aid).firstOrNull { it.localId == localId } ?: return@withLock
        if (job.phase !in setOf(ImagePhase.REVIEW, ImagePhase.ADD_UNCERTAIN, ImagePhase.REJECTED, ImagePhase.SEND_FAILED)) return@withLock
        store.save(job.copy(phase = ImagePhase.CLEANUP, cancelled = true, error = "", nextCheck = 0))
    }
    private suspend fun advance(session: Session, initial: ImageJob) {
        var job = initial
        // Continue immediate transitions, but poll a pending audit/history once per tick.
        repeat(8) {
            when (job.phase) {
                ImagePhase.UPLOAD -> {
                    job = job.copy(phase = ImagePhase.ADD, uploaded = upload(job), error = "", nextCheck = 0)
                    store.save(job)
                }
                ImagePhase.ADD -> {
                    val existing = api.customEmoticons(session)
                    val same = existing.firstOrNull { sameImage(it, job.uploaded) }
                    if (same != null) job = job.copy(phase = ImagePhase.REVIEW, item = same, owned = false)
                    else {
                        // The before-set is used only in this successful request;
                        // crash/timeout recovery adopts an item without claiming ownership.
                        store.save(job.copy(phase = ImagePhase.ADD_UNCERTAIN))
                        val item = try { api.addCustomEmoticon(session, job.uploaded) }
                        catch (failure: ApiFailure) {
                            store.save(job.copy(phase = ImagePhase.SEND_FAILED, error = failure.message.orEmpty())); return
                        }
                        if (item == null || !sameImage(item, job.uploaded)) return
                        job = job.copy(phase = ImagePhase.REVIEW, item = item, owned = existing.none { it.customId == item.customId })
                    }
                    store.save(job)
                }
                ImagePhase.ADD_UNCERTAIN -> {
                    val matches = api.customEmoticons(session).filter { sameImage(it, job.uploaded) }
                    if (matches.size != 1) { store.save(job.copy(nextCheck = clock() + 15000)); return }
                    job = job.copy(phase = ImagePhase.REVIEW, item = matches.single(), owned = false, error = "")
                    store.save(job)
                }
                ImagePhase.REVIEW -> {
                    val item = api.customEmoticons(session).firstOrNull { it.customId == job.item?.customId }
                    if (item == null) { store.save(job.copy(error = "图片审核状态暂不可用", nextCheck = clock() + 15000)); return }
                    job = job.copy(item = item)
                    when {
                        item.canSend -> { job = job.copy(phase = ImagePhase.READY, error = "", nextCheck = 0); store.save(job) }
                        item.auditStatus == "CustomEmoticonAuditStatusNoPass" -> { store.save(job.copy(phase = ImagePhase.REJECTED, error = "图片未通过审核")); return }
                        else -> { store.save(job.copy(error = if (item.auditStatus == "CustomEmoticonAuditStatusPend") "图片审核中" else "图片审核状态待确认", nextCheck = clock() + 15000)); return }
                    }
                }
                ImagePhase.READY -> {
                    check(job.item?.canSend == true)
                    // Persist before sending: recovery must reconcile, never POST again.
                    val submitted = job.copy(phase = ImagePhase.VERIFY, error = "", nextCheck = 0)
                    store.save(submitted)
                    val receipt = try { api.send(session, job.message().copy(delivery = Delivery.SENDING)) }
                    catch (failure: ApiFailure) {
                        store.save(job.copy(phase = ImagePhase.SEND_FAILED, error = failure.message.orEmpty())); return
                    }
                    job = submitted.copy(receiptId = receipt.id)
                    store.save(job)
                }
                ImagePhase.VERIFY -> {
                    val cached = store.observed(job)?.takeIf { confirms(job, it) }
                    val found = cached ?: api.history(session, job.chatId, size = 50).messages.firstOrNull { confirms(job, it) }
                    if (found == null) { store.save(job.copy(nextCheck = clock() + 15000)); return }
                    job = job.copy(phase = ImagePhase.CLEANUP, receiptId = found.id, error = "", nextCheck = 0)
                    store.save(job)
                }
                ImagePhase.CLEANUP -> {
                    if (job.owned && job.item != null) {
                        try { api.removeCustomEmoticon(session, job.item!!.customId) }
                        catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            // A failed cleanup never sends again. Check absence before retrying DELETE.
                            val remains = runCatching { api.customEmoticons(session).any { it.customId == job.item!!.customId } }.getOrDefault(true)
                            if (remains) { store.save(job.copy(error = if (job.cancelled) "已取消，临时表情清理待重试" else "已发送，临时表情清理待重试", nextCheck = clock() + 60000)); return }
                        }
                    }
                    store.finish(job.copy(phase = ImagePhase.DONE, error = "")); return
                }
                ImagePhase.SEND_FAILED, ImagePhase.REJECTED, ImagePhase.DONE -> return
            }
        }
    }
    companion object {
        fun sameImage(item: Emoticon, uploaded: String): Boolean = runCatching {
            val a = JSONObject(item.content); val b = JSONObject(uploaded)
            item.customId.isNotBlank() && a.getString("url") == b.getString("url") &&
                a.getLong("file_size") == b.getLong("file_size") &&
                a.getJSONObject("size").getInt("width") == b.getJSONObject("size").getInt("width") &&
                a.getJSONObject("size").getInt("height") == b.getJSONObject("size").getInt("height")
        }.getOrDefault(false)
        fun confirms(job: ImageJob, message: Message): Boolean = message.id.isNotBlank() &&
            message.senderId == job.account && message.chatId == job.chatId && message.type == 8 &&
            (message.localId == job.localId || (job.receiptId.isNotBlank() && message.id == job.receiptId)) &&
            runCatching { val value = JSONObject(message.content); value.getString("id") == job.item?.customId && value.getString("url") == job.item?.url }.getOrDefault(false)

        fun encode(job: ImageJob): String = JSONObject().put("account", job.account).put("local_id", job.localId)
            .put("chat_id", job.chatId).put("time", job.time).put("phase", job.phase.name).put("uploaded", job.uploaded)
            .put("item", job.item?.let { EmoticonLibrary.encode(listOf(it)).getJSONObject(0) })
            .put("owned", job.owned).put("error", job.error).put("next_check", job.nextCheck)
            .put("cancelled", job.cancelled).put("receipt_id", job.receiptId).toString()
        fun decode(value: String): ImageJob = JSONObject(value).let {
            ImageJob(it.getString("account"), it.getString("local_id"), it.getString("chat_id"), it.getLong("time"),
                ImagePhase.valueOf(it.getString("phase")), it.optString("uploaded"),
                it.optJSONObject("item")?.let { item -> EmoticonLibrary.decode(JSONArray().put(item)).single() },
                it.optBoolean("owned"), it.optString("error"), it.optLong("next_check"), it.optBoolean("cancelled"), it.optString("receipt_id"))
        }
    }
}

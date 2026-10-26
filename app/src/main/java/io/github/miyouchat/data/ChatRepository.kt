package io.github.miyouchat.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.io.File
import org.json.JSONObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import io.github.miyouchat.sync.IncomingPolicy
import io.github.miyouchat.sync.MessageNotifications

class ChatRepository(val context: Context, val store: SessionStore, val api: MiyouApi, val database: ChatDatabase) {
    val notifications = MessageNotifications(context)
    private fun imageFile(account: String, localId: String) = File(context.filesDir, "pending-images/${MessageCodec.md5("$account:$localId")}.jpg")
    private val temporaryImages = TemporaryImages(api, object : ImageJobStore {
        override fun jobs(account: String) = database.imageJobs(account)
        override fun save(job: ImageJob) { database.saveImageJob(job); changed() }
        override fun finish(job: ImageJob) { database.finishImageJob(job); imageFile(job.account, job.localId).delete(); changed() }
        override fun observed(job: ImageJob) = database.messages(job.account, job.chatId).firstOrNull { TemporaryImages.confirms(job, it) }
    }, upload = { job ->
        val bytes = imageFile(job.account, job.localId).readBytes()
        val size = JSONObject(job.uploaded).getJSONObject("size")
        val account = session()?.takeIf { it.aid == job.account } ?: throw ApiFailure(-100)
        api.uploadImage(account, bytes, size.getInt("width"), size.getInt("height"))
    })
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private val syncMutex = Mutex()
    private val profileMutex = Mutex()
    @Volatile var activeChat: String? = null
    @Volatile var foreground: Boolean = false
    private fun changed() { changes.value += 1 }
    fun session() = store.load()
    suspend fun refreshAccountProfiles(force: Boolean = false): Int = profileMutex.withLock {
        var failed = 0
        val saved = withContext(Dispatchers.IO) { store.savedSessions().filter { session -> force || !AccountProfiles.fresh(store.accounts().firstOrNull { it.id == session.aid }?.profile) } }
        for (account in saved) {
            currentCoroutineContext().ensureActive()
            try {
                val profile = api.profile(account)
                withContext(Dispatchers.IO) { store.updateProfile(account.aid,profile) }
                changed()
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { failed++ }
        }
        failed
    }
    suspend fun signIn(session: Session) = withContext(Dispatchers.IO) {
        api.chats(session) // Verify the account before persisting credentials.
        syncMutex.withLock { store.save(session); activeChat = null; notifications.clearAllMessages(); database.markInterrupted(session.aid); changed() }
    }
    suspend fun selectAccount(id: String) = syncMutex.withLock { withContext(Dispatchers.IO) {
        store.select(id); activeChat = null; notifications.clearAllMessages(); database.markInterrupted(id); changed()
    } }
    suspend fun addAnotherAccount() = syncMutex.withLock { withContext(Dispatchers.IO) {
        store.addAnother(); activeChat = null; notifications.clearAllMessages(); changed()
    } }
    suspend fun localChats(): List<Chat> = withContext(Dispatchers.IO) { session()?.let { database.chats(it.aid) } ?: emptyList() }
    suspend fun localMessages(chatId: String): List<Message> = withContext(Dispatchers.IO) { session()?.let { account ->
        val pending = database.imageJobs(account.aid).associateBy { it.localId }
        database.messages(account.aid, chatId).map { message -> pending[message.localId]?.message()?.let {
            message.copy(delivery = it.delivery, error = it.error)
        } ?: message }
    } ?: emptyList() }
    suspend fun sync(): List<Pair<Chat, Message>> = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            val account = session() ?: return@withContext emptyList()
            val priorChats = database.chats(account.aid).associateBy { it.id }
            val initialized = database.initialized(account.aid)
            val chats = api.chats(account); database.saveChats(account.aid, chats)
            val notifications = mutableListOf<Pair<Chat, Message>>()
            for (chat in chats) {
                // Reconcile the open chat and changed/unread conversations.
                if (chat.id != activeChat && priorChats[chat.id]?.updatedAt == chat.updatedAt && chat.unread == 0) continue
                val page = api.history(account, chat.id)
                val existing = database.messages(account.aid, chat.id).map { it.id }.filter { it.isNotBlank() }.toHashSet()
                val collected = page.messages.toMutableList()
                var next = page
                var loaded = 1
                // Bridge the gap after offline periods until reaching a known
                // server message; cap work per synchronization cycle.
                while (existing.isNotEmpty() && !next.isLast && next.messages.none { it.id in existing } && loaded < 20) {
                    val cursor = next.cursor
                    next = api.history(account, chat.id, cursor)
                    check(next.isLast || next.cursor != cursor)
                    collected += next.messages; loaded++
                }
                val inserted = database.saveMessages(account.aid, collected)
                if (database.cursor(account.aid, chat.id) == null) database.cursor(account.aid, chat.id, page.cursor, page.isLast)
                IncomingPolicy.newest(account.aid, priorChats[chat.id], chat, inserted, initialized)?.let { message ->
                    notifications += chat to message
                    if (session()?.aid == account.aid && IncomingPolicy.show(store.backgroundSync, foreground, activeChat, chat.id, database.muted(account.aid,chat.id)))
                        this@ChatRepository.notifications.post(account.aid, chat, message)
                }
                if (IncomingPolicy.markRead(foreground,activeChat,chat.id) && chat.unread > 0) runCatching { api.clearUnread(account, chat.id) }
            }
            temporaryImages.tick(account)
            database.markInitialized(account.aid)
            changed(); notifications
        }
    }
    suspend fun refreshChat(chatId: String) = withContext(Dispatchers.IO) {
        val account = session() ?: return@withContext
        notifications.clear(account.aid,chatId)
        val page = api.history(account, chatId)
        database.saveMessages(account.aid, page.messages)
        if (database.cursor(account.aid, chatId) == null) database.cursor(account.aid, chatId, page.cursor, page.isLast)
        changed()
        // This reflects the user's intentional opening of this conversation.
        runCatching { api.clearUnread(account, chatId) }
    }
    suspend fun setChatOptions(chat: Chat, pinned: Boolean = chat.pinned, muted: Boolean = chat.muted) = withContext(Dispatchers.IO) {
        val account = session() ?: return@withContext
        database.setOptions(account.aid,chat.id,pinned,muted)
        if (muted) notifications.clear(account.aid,chat.id)
        changed()
    }
    suspend fun cacheHistory(chatIds: Set<String>, progress: (Int, Boolean) -> Unit) {
        val account = session() ?: throw ApiFailure(-100)
        val selected = withContext(Dispatchers.IO) { database.chats(account.aid).filter { chatIds.isEmpty() || it.id in chatIds }.map { it.id } }
        var loaded = 0
        repeat(200) {
            currentCoroutineContext().ensureActive()
            val chatId = withContext(Dispatchers.IO) { selected.firstOrNull { database.cursor(account.aid,it)?.second != true } }
                ?: run { progress(loaded,true); return }
            syncMutex.withLock { withContext(Dispatchers.IO) {
                check(session()?.aid == account.aid) { "Account changed" }
                val previous = database.cursor(account.aid,chatId)
                val page = api.history(account,chatId,previous?.first.orEmpty())
                check(page.isLast || page.cursor != previous?.first) { "History cursor did not advance" }
                database.saveMessages(account.aid,page.messages)
                database.cursor(account.aid,chatId,page.cursor,page.isLast); loaded += page.messages.size; changed()
            } }
            progress(loaded,false); delay(120)
        }
    }
    suspend fun older(chatId: String): Boolean = withContext(Dispatchers.IO) {
        val account = session() ?: return@withContext true
        val previous = database.cursor(account.aid, chatId)
        if (previous?.second == true) return@withContext true
        val page = api.history(account, chatId, previous?.first.orEmpty())
        if (!page.isLast && page.cursor == previous?.first) throw IOException("History cursor did not advance")
        database.saveMessages(account.aid, page.messages)
        database.cursor(account.aid, chatId, page.cursor, page.isLast); changed(); page.isLast
    }
    fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
    suspend fun sendTemporaryImage(chatId: String, bytes: ByteArray, width: Int, height: Int) = withContext(Dispatchers.IO) {
        val account = session() ?: throw ApiFailure(-100)
        val localId = UUID.randomUUID().toString()
        val file = imageFile(account.aid, localId); file.parentFile!!.mkdirs(); file.writeBytes(bytes)
        val metadata = JSONObject().put("size", JSONObject().put("width", width).put("height", height)).toString()
        database.saveImageJob(ImageJob(account.aid, localId, chatId, System.currentTimeMillis(), uploaded = metadata)); changed()
        temporaryImages.tick(account)
    }
    suspend fun retryImage(message: Message) = withContext(Dispatchers.IO) {
        val account = session() ?: throw ApiFailure(-100)
        if (database.imageJobs(account.aid).none { it.localId == message.localId }) {
            check(message.type == 3 && message.delivery == Delivery.FAILED && message.id.isBlank())
            database.saveImageJob(ImageJob(account.aid, message.localId, message.chatId, message.time, ImagePhase.ADD, uploaded = message.content))
        }
        temporaryImages.retry(account, message.localId); temporaryImages.tick(account); changed()
    }
    suspend fun cancelImage(message: Message) = withContext(Dispatchers.IO) {
        val account = session() ?: return@withContext
        temporaryImages.cancel(account, message.localId); temporaryImages.tick(account); changed()
    }
    suspend fun send(chatId: String, content: String, type: Int = 9, retry: Message? = null) = withContext(Dispatchers.IO) {
        val account = session() ?: throw ApiFailure(-100)
        check(retry == null || retry.delivery == Delivery.FAILED)
        var message = retry?.copy(delivery = Delivery.SENDING, error = "") ?: Message("", UUID.randomUUID().toString(), chatId, account.aid, type, content, System.currentTimeMillis(), Delivery.SENDING)
        database.saveMessages(account.aid, listOf(message)); changed()
        if (!online()) {
            database.saveMessages(account.aid, listOf(message.copy(delivery = Delivery.FAILED, error = "当前无网络，联网后可重试"))); changed(); return@withContext
        }
        try {
            message = api.send(account, message)
            database.saveMessages(account.aid, listOf(message)); changed()
            if (message.delivery == Delivery.UNCERTAIN) runCatching { refreshChat(chatId) }
        } catch (failure: ApiFailure) {
            database.saveMessages(account.aid, listOf(message.copy(delivery = Delivery.FAILED, error = failure.message.orEmpty()))); changed()
        } catch (failure: Exception) {
            // A transport timeout cannot tell whether the server accepted POST.
            // Keep local_id for history reconciliation; never blindly retry it.
            database.saveMessages(account.aid, listOf(message.copy(delivery = Delivery.UNCERTAIN, error = "发送结果待确认，请刷新核对"))); changed()
            runCatching { refreshChat(chatId) }
        }
    }
    suspend fun logout(clearCache: Boolean) = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            session()?.let { if (clearCache) {
                database.imageJobs(it.aid).forEach { job -> imageFile(job.account, job.localId).delete() }
                database.clearAccount(it.aid)
            } }
            store.clear(); activeChat = null; notifications.clearAllMessages(); changed()
        }
    }
}

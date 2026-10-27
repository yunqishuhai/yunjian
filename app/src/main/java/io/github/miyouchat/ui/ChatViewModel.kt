package io.github.miyouchat.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.graphics.ImageDecoder
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.EncodeHintType
import io.github.miyouchat.MiyouApplication
import io.github.miyouchat.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AppState(val loggedIn: Boolean = false, val chats: List<Chat> = emptyList(),
                    val active: Chat? = null, val messages: List<Message> = emptyList(),
                    val busy: Boolean = false, val error: String? = null, val endReached: Boolean = false,
                    val qrBitmap: Bitmap? = null, val qrUrl: String = "", val loginStatus: String = "",
                    val sms: SmsLoginState = SmsLoginState(), val syncEnabled: Boolean = false,
                    val emoticons: List<Emoticon> = emptyList(), val customEmoticons: List<Emoticon> = emptyList(),
                    val customLoading: Boolean = false, val customError: String? = null,
                    val favorites: List<FavoriteGroup> = emptyList(),
                    val accountId: String = "", val accounts: List<AccountSummary> = emptyList(), val authExpired: Boolean = false,
                    val profilesLoading: Boolean = false, val profilesError: String? = null,
                    val history: HistoryState = HistoryState(), val archive: ArchiveState = ArchiveState(), val focusMessage: String? = null,
                    val backgrounds: ChatBackgrounds = ChatBackgrounds(), val backgroundBusy: Boolean = false, val backgroundError: String? = null)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    val repository = (application as MiyouApplication).repository
    private val appearanceStore = AppearanceStore(application)
    val appearance = appearanceStore.state
    fun setAppearance(value: Appearance) { appearanceStore.set(value) }
    private val library = EmoticonLibrary(application)
    private val backgroundStore = ChatBackgroundStore(application)
    private var backgroundRevision = 0L
    private val favoriteMutex = Mutex()
    private val operationMutex = Mutex()
    private val mutable = MutableStateFlow(AppState(loggedIn = repository.session() != null, syncEnabled = repository.store.backgroundSync))
    val state = mutable.asStateFlow()
    private var qrJob: Job? = null
    private var pollJob: Job? = null
    private var searchJob: Job? = null
    private var historyJob: Job? = null
    private var archiveJob: Job? = null
    private var customJob: Job? = null
    private var profileJob: Job? = null
    private var pendingArchive: Triple<HistoryFilter,Boolean,String>? = null
    val smsLogin = SmsLogin(viewModelScope,
        send = { phone, aigis -> repository.api.sms(phone, repository.store.deviceId, aigis) },
        login = { phone, code, action, aigis -> repository.api.loginSms(phone, code, action, repository.store.deviceId, aigis) },
        signedIn = { session ->
            operationMutex.withLock {
                repository.signIn(session); qrJob?.cancel()
                mutable.update { it.copy(authExpired = false) }
                loadLocal(); setVisible(true); loadEmoticons(); refreshAccountProfiles()
            }
        })
    val accountId: String get() = repository.session()?.aid.orEmpty()
    init {
        viewModelScope.launch { smsLogin.state.collect { sms -> mutable.update { it.copy(sms = sms) } } }
        viewModelScope.launch { repository.revision.collect { loadLocal() } }
        if (repository.session() != null) loadEmoticons()
        refreshAccountProfiles()
    }
    private suspend fun loadLocal() {
        val account = repository.session()
        val backgroundVersion = backgroundRevision
        val chats = withContext(Dispatchers.IO) { account?.let { repository.database.chats(it.aid) } ?: emptyList() }
        val active = mutable.value.active.takeIf { mutable.value.accountId == account?.aid }
        val messages = active?.let { repository.localMessages(it.id) } ?: emptyList()
        val ended = if (account != null && active != null) withContext(Dispatchers.IO) { repository.database.cursor(account.aid, active.id)?.second ?: false } else false
        val favorites = if (account != null) withContext(Dispatchers.IO) { library.load(account.aid) } else emptyList()
        val accounts = withContext(Dispatchers.IO) { repository.store.accounts() }
        val backgrounds = withContext(Dispatchers.IO) { account?.let { backgroundStore.load(it.aid) } ?: ChatBackgrounds() }
        if (repository.session()?.aid != account?.aid) return
        mutable.update { current ->
            // A notification can open a conversation while these disk reads are
            // suspended. An older snapshot must not put the UI back on the list.
            val latest = current.active.takeIf { current.accountId == account?.aid }
            val sameConversation = latest?.id == active?.id
            current.copy(loggedIn = account != null, accountId = account?.aid.orEmpty(), accounts = accounts, chats = chats,
                messages = if (sameConversation) messages else current.messages,
                active = latest?.let { old -> chats.find { chat -> chat.id == old.id } ?: old },
                syncEnabled = repository.store.backgroundSync, endReached = if (sameConversation) ended else current.endReached,
                favorites = favorites, backgrounds = if (backgroundVersion == backgroundRevision) backgrounds else current.backgrounds)
        }
    }
    fun errorDismiss() { mutable.update { it.copy(error = null) } }
    fun setVisible(visible: Boolean) {
        repository.foreground = visible
        pollJob?.cancel()
        if (visible && repository.session() != null) pollJob = viewModelScope.launch {
            while (isActive) {
                runCatching { repository.sync() }.onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    if (failure is ApiFailure && failure.code in setOf(-100,10001)) mutable.update { it.copy(authExpired = true) }
                    else if (!repository.online()) mutable.update { it.copy(error = "当前离线，正在显示本地记录") }
                }
                delay(6000)
            }
        }
    }
    private fun work(block: suspend () -> Unit): Job {
        val expectedAccount = accountId
        return viewModelScope.launch { operationMutex.withLock {
            if (accountId != expectedAccount) return@withLock
            mutable.update { it.copy(busy = true, error = null) }
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                mutable.update { it.copy(error = if (failure is ApiFailure) failure.message else "连接未完成，请检查网络后重试") }
            } finally { mutable.update { it.copy(busy = false) }; loadLocal() }
        } }
    }
    fun refresh() = work {
        repository.sync(); mutable.value.active?.let { repository.refreshChat(it.id) }
    }
    fun open(chat: Chat) {
        repository.activeChat = chat.id
        mutable.update { it.copy(active = chat, messages = emptyList(), endReached = false) }
        work { loadLocal(); repository.refreshChat(chat.id) }
    }
    fun back() { repository.activeChat = null; mutable.update { it.copy(active = null, messages = emptyList()) } }
    fun setOptions(chat: Chat, pinned: Boolean = chat.pinned, muted: Boolean = chat.muted) = work { repository.setChatOptions(chat,pinned,muted) }
    fun setBackground(uri: Uri?, chat: String?, expectedAccount: String) = viewModelScope.launch {
        operationMutex.withLock {
            // A picker can return after a switch or logout. Never apply it to a new account.
            if (expectedAccount.isBlank() || accountId != expectedAccount) return@withLock
            mutable.update { it.copy(backgroundBusy = true,backgroundError = null) }
            try {
                val backgrounds = withContext(Dispatchers.IO) {
                    if (uri == null) backgroundStore.reset(expectedAccount,chat) else backgroundStore.set(expectedAccount,chat,uri)
                    backgroundStore.load(expectedAccount)
                }
                if (accountId == expectedAccount) {
                    backgroundRevision++
                    mutable.update { it.copy(backgrounds = backgrounds) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                mutable.update { it.copy(backgroundError = if (failure is IllegalArgumentException) failure.message else "背景未能保存，请换一张图片或检查手机可用空间") }
            } finally { mutable.update { it.copy(backgroundBusy = false) } }
        }
    }
    fun clearBackgroundError() { mutable.update { it.copy(backgroundError = null) } }
    fun openHistory() {
        val filter = HistoryFilter(chatIds = mutable.value.active?.let { setOf(it.id) } ?: emptySet())
        mutable.update { it.copy(history = HistoryState(visible = true, filter = filter)) }
        searchHistory(filter)
    }
    fun closeHistory() { searchJob?.cancel(); mutable.update { it.copy(history = it.history.copy(visible = false)) } }
    fun searchHistory(filter: HistoryFilter, append: Boolean = false) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val account = accountId; if (account.isBlank()) return@launch
            val previous = mutable.value.history
            mutable.update { it.copy(history = it.history.copy(filter = filter, loading = true, hits = if (append) previous.hits else emptyList())) }
            try {
                if (!append) delay(250)
                val (page,coverage) = withContext(Dispatchers.IO) { repository.database.search(account,filter,if (append) previous.next else null) to repository.database.coverage(account) }
                if (accountId == account) mutable.update { it.copy(history = it.history.copy(hits = if (append) previous.hits + page.hits else page.hits, next = page.next, loading = false, coverage = coverage)) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutable.update { it.copy(error = "搜索未完成，请重试", history = it.history.copy(loading = false)) } }
        }
    }
    fun showHit(hit: SearchHit) {
        val chat = mutable.value.chats.firstOrNull { it.id == hit.message.chatId } ?: return
        closeHistory(); open(chat); mutable.update { it.copy(focusMessage = hit.message.key) }
    }
    fun consumeFocus() { mutable.update { it.copy(focusMessage = null) } }
    fun syncHistory(chatIds: Set<String>) {
        if (historyJob?.isActive == true) return
        historyJob = viewModelScope.launch {
            mutable.update { it.copy(history = it.history.copy(syncing = true, progress = "正在同步更早记录")) }
            try {
                repository.cacheHistory(chatIds) { count,complete -> mutable.update { it.copy(history = it.history.copy(progress = if (complete) "已同步到最早记录" else "本次已拉取 $count 条记录")) } }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { mutable.update { it.copy(error = if (failure is ApiFailure) failure.message else "历史同步暂停，可稍后继续") } }
            finally {
                mutable.update { it.copy(history = it.history.copy(syncing = false)) }
                searchHistory(mutable.value.history.filter)
            }
        }
    }
    fun cancelHistorySync() { historyJob?.cancel() }
    fun openArchive() { mutable.update { it.copy(archive = ArchiveState(visible = true)) } }
    fun closeArchive() { if (!mutable.value.archive.running) mutable.update { it.copy(archive = ArchiveState()) } }
    fun cancelArchive() { archiveJob?.cancel() }
    fun prepareArchive(filter: HistoryFilter, images: Boolean) { pendingArchive = Triple(filter,images,accountId) }
    fun archiveDestination(uri: Uri?) {
        val request = pendingArchive; pendingArchive = null
        if (uri != null && request != null) exportArchive(uri,request.first,request.second,request.third)
    }
    fun exportArchive(uri: Uri, filter: HistoryFilter, images: Boolean, expectedAccount: String) {
        if (accountId != expectedAccount || archiveJob?.isActive == true) return
        archiveJob = viewModelScope.launch {
            val account = accountId
            val label = mutable.value.accounts.firstOrNull { it.id == account }?.displayName.orEmpty()
            val official = mutable.value.emoticons.associate { it.name to it.url }
            mutable.update { it.copy(archive = it.archive.copy(running = true, progress = "准备导出", result = "")) }
            var snapshot: java.io.File? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    val coroutine = currentCoroutineContext()
                    val file = java.io.File.createTempFile("history-export-", ".jsonl", getApplication<Application>().cacheDir); snapshot = file
                    val count = repository.database.exportSnapshot(account,filter,file) { coroutine.ensureActive() }
                    val coverage = repository.database.coverage(account).filter { filter.chatIds.isEmpty() || it.chatId in filter.chatIds }
                    val client = okhttp3.OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(10,java.util.concurrent.TimeUnit.SECONDS).readTimeout(15,java.util.concurrent.TimeUnit.SECONDS).build()
                    coroutine.ensureActive()
                    getApplication<Application>().contentResolver.openOutputStream(uri,"wt")!!.use { output ->
                        ChatArchive.write(file,output,account,label,coverage,images,official,fetch = { url ->
                            coroutine.ensureActive()
                            try { client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                                if (!response.isSuccessful) null else response.body?.byteStream()?.use { input ->
                                    val bytes = ByteArrayOutputStream(); val buffer = ByteArray(32768); var total = 0
                                    while (true) { coroutine.ensureActive(); val n = input.read(buffer); if (n < 0) break; total += n; if (total > 20*1024*1024) error("Attachment too large"); bytes.write(buffer,0,n) }
                                    bytes.toByteArray()
                                }
                            } } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
                        }, progress = { stage,done,total ->
                            coroutine.ensureActive()
                            mutable.update { it.copy(archive = it.archive.copy(progress = "$stage · $done/${if (total > 0) total else count}")) }
                        })
                    }
                }
                mutable.update { it.copy(archive = it.archive.copy(result = "已导出 ${result.messages} 条消息、${result.images} 张图片${if (result.missing > 0) "；${result.missing} 个附件未获取，详见清单" else ""}")) }
            } catch (cancel: CancellationException) {
                mutable.update { it.copy(archive = it.archive.copy(result = "导出已取消，目标文件可能不完整")) }; throw cancel
            } catch (_: Exception) { mutable.update { it.copy(archive = it.archive.copy(result = "导出未完成，请检查目标位置；文件可能不完整")) } }
            finally { withContext(NonCancellable + Dispatchers.IO) { snapshot?.delete() }; mutable.update { it.copy(archive = it.archive.copy(running = false)) } }
        }
    }
    private suspend fun stopAccountWork() {
        smsLogin.reset()
        pendingArchive = null
        qrJob?.cancelAndJoin(); pollJob?.cancelAndJoin(); historyJob?.cancelAndJoin(); archiveJob?.cancelAndJoin(); searchJob?.cancelAndJoin(); customJob?.cancelAndJoin()
    }
    fun selectAccount(id: String) = work {
        stopAccountWork(); repository.selectAccount(id); mutable.value = AppState(loggedIn = true, busy = true)
        loadLocal(); setVisible(true); loadEmoticons()
    }
    fun addAnotherAccount() = work {
        stopAccountWork(); getApplication<Application>().stopService(android.content.Intent(getApplication(),io.github.miyouchat.sync.MessageSyncService::class.java))
        repository.addAnotherAccount(); mutable.value = AppState(busy = true); loadLocal()
    }
    fun renameAccount(id: String, label: String) = work { withContext(Dispatchers.IO) { repository.store.rename(id,label) } }
    fun refreshAccountProfiles(force: Boolean = false) {
        if (profileJob?.isActive == true) return
        profileJob = viewModelScope.launch {
            mutable.update { it.copy(profilesLoading = true,profilesError = null) }
            try {
                val failed = repository.refreshAccountProfiles(force)
                mutable.update { it.copy(profilesError = if (failed > 0) "部分账号资料暂未更新，可点右上角刷新" else null) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutable.update { it.copy(profilesError = "账号资料暂未更新，可点右上角刷新") } }
            finally { loadLocal(); mutable.update { it.copy(profilesLoading = false) } }
        }
    }
    fun older() = work {
        mutable.value.active?.let { chat -> val end = repository.older(chat.id); mutable.update { it.copy(endReached = end) } }
    }
    fun send(text: String) = work {
        val chat = mutable.value.active ?: return@work
        if (text.isBlank()) return@work
        repository.send(chat.id, MessageCodec.textContent(text.trim()))
    }
    fun sendEmoticon(name: String) = work {
        val chat = mutable.value.active ?: return@work
        repository.send(chat.id, "_($name)", 2)
    }
    fun sendEmoticon(item: Emoticon) = work {
        val chat = mutable.value.active ?: return@work
        if (!item.canSend) { mutable.update { it.copy(error = "这个表情尚未通过米游社审核") }; return@work }
        repository.send(chat.id, if (item.customId.isNotBlank()) item.content else "_(${item.name})", if (item.customId.isNotBlank()) 8 else 2)
    }
    fun loadCustomEmoticons() {
        if (customJob?.isActive == true) return
        customJob = viewModelScope.launch {
            mutable.update { it.copy(customLoading = true, customError = null) }
            try { refreshCustomEmoticons() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { mutable.update { it.copy(customError = if (failure is ApiFailure) failure.message else "表情库加载失败，请检查网络后重试") } }
            finally { mutable.update { it.copy(customLoading = false) }; loadLocal() }
        }
    }
    private suspend fun refreshCustomEmoticons() {
        val account = repository.session() ?: return
        val items = repository.api.customEmoticons(account)
        if (accountId != account.aid) return
        // Refresh saved copies so audit status and image metadata cannot go stale.
        val latest = items.associateBy { it.key }
        updateFavorites(account.aid) { groups -> groups.map { group -> group.copy(items = group.items.map { item ->
            if (item.customId.isBlank()) item else latest[item.key] ?: item.copy(auditStatus = "")
        }) } }
        val temporaryIds = withContext(Dispatchers.IO) { repository.database.imageJobs(account.aid).filter { it.owned }.mapNotNull { it.item?.customId }.toSet() }
        if (accountId != account.aid) return
        mutable.update { it.copy(customEmoticons = items.filterNot { item -> item.customId in temporaryIds }, customError = null) }
    }
    fun addCustomEmoticon(uri: Uri, groupId: String?) = work {
        val account = repository.session() ?: return@work
        val (bytes, width, height) = readImage(uri)
        val upload = repository.api.uploadImage(account, bytes, width, height)
        val item = repository.api.addCustomEmoticon(account, upload)
        if (groupId != null && item != null) updateFavorites { groups -> groups.map {
            if (it.id == groupId) it.copy(items = (it.items + item).distinctBy { value -> value.key }) else it
        } }
        refreshCustomEmoticons()
        mutable.update { it.copy(error = "已提交到米游社表情库，请查看审核状态") }
    }
    fun removeCustomEmoticon(item: Emoticon) = work {
        val account = repository.session() ?: return@work
        if (item.customId.isBlank()) return@work
        repository.api.removeCustomEmoticon(account, item.customId)
        updateFavorites { groups -> groups.map { group -> group.copy(items = group.items.filterNot { it.key == item.key }) } }
        refreshCustomEmoticons()
    }
    fun editFavoriteGroup(id: String?, name: String) = work {
        if (name.isBlank()) return@work
        updateFavorites { groups -> if (id == null) groups + EmoticonLibrary.newGroup(name)
            else groups.map { if (it.id == id) it.copy(name = name.trim().take(24)) else it } }
    }
    fun deleteFavoriteGroup(id: String) = work { updateFavorites { groups -> groups.filterNot { it.id == id }
        .ifEmpty { listOf(FavoriteGroup("favorites", "默认收藏")) } } }
    fun favorite(item: Emoticon, group: String, remove: Boolean = false) = work {
        updateFavorites { groups -> groups.map { if (it.id != group) it else it.copy(items =
            if (remove) it.items.filterNot { saved -> saved.key == item.key } else (it.items + item).distinctBy { saved -> saved.key }) } }
    }
    private suspend fun updateFavorites(account: String = accountId, change: (List<FavoriteGroup>) -> List<FavoriteGroup>) {
        if (account.isBlank()) return
        favoriteMutex.withLock { withContext(Dispatchers.IO) { library.save(account, change(library.load(account))) } }
    }
    fun loadEmoticons() = work {
        if (mutable.value.emoticons.isNotEmpty()) return@work
        withContext(Dispatchers.IO) {
            val file = java.io.File(getApplication<Application>().filesDir, "emoticons-v2.json")
            val cached = runCatching {
                val values = org.json.JSONArray(file.readText())
                EmoticonLibrary.decode(values)
            }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) mutable.update { it.copy(emoticons = cached) }
            runCatching {
                val items = repository.api.emoticons()
                val array = EmoticonLibrary.encode(items)
                file.writeText(array.toString()); mutable.update { it.copy(emoticons = items) }
            }.getOrElse { if (cached.isEmpty()) throw it }
        }
    }
    fun sendImage(uri: Uri, expectedAccount: String = accountId, expectedChat: String = mutable.value.active?.id.orEmpty(), captured: Boolean = false): Job {
        val job = work {
            if (expectedAccount.isBlank() || accountId != expectedAccount || mutable.value.active?.id != expectedChat) {
                mutable.update { it.copy(error = "会话已改变，请重新选择图片") }
                return@work
            }
            val (bytes, width, height) = readImage(uri)
            repository.sendTemporaryImage(expectedChat, bytes, width, height)
        }
        if (captured) job.invokeOnCompletion { CameraCaptureStore(getApplication()).discard(uri) }
        return job
    }
    private suspend fun readImage(uri: Uri): Triple<ByteArray, Int, Int> = withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val bitmap = if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    val scale = maxOf(info.size.width, info.size.height) / 2048f
                    if (scale > 1) decoder.setTargetSize((info.size.width / scale).toInt(), (info.size.height / scale).toInt())
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) } ?: error("Cannot read image")
            val output = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
            Triple(output.toByteArray(), bitmap.width, bitmap.height).also { bitmap.recycle() }
    }
    fun retry(message: Message) = work {
        if (message.type == 3 || withContext(Dispatchers.IO) { repository.database.imageJobs(accountId).any { it.localId == message.localId } }) repository.retryImage(message)
        else repository.send(message.chatId, message.content, message.type, message)
    }
    fun cancelImage(message: Message) = work { repository.cancelImage(message) }
    fun createQr() {
        smsLogin.reset()
        qrJob?.cancel()
        qrJob = viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null, loginStatus = "正在创建二维码") }
            try {
                val qr = repository.api.createQr(repository.store.deviceId)
                val bitmap = withContext(Dispatchers.Default) {
                    val matrix = MultiFormatWriter().encode(qr.url, BarcodeFormat.QR_CODE, 640, 640, mapOf(EncodeHintType.MARGIN to 3))
                    Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).apply {
                        val pixels = IntArray(640 * 640) { index -> if (matrix[index % 640, index / 640]) Color.BLACK else Color.WHITE }
                        setPixels(pixels, 0, 640, 0, 0, 640, 640)
                    }
                }
                mutable.update { it.copy(busy = false, qrBitmap = bitmap, qrUrl = qr.url, loginStatus = "请用米游社扫码并确认") }
                repeat(75) {
                    delay(1600)
                    val (status, session) = repository.api.queryQr(qr)
                    if (session != null) {
                        repository.signIn(session)
                        mutable.update { it.copy(qrBitmap = null, qrUrl = "", loginStatus = "登录成功", authExpired = false) }
                        loadLocal(); setVisible(true); loadEmoticons(); refreshAccountProfiles(); return@launch
                    }
                    if (status == "Scanned") mutable.update { it.copy(loginStatus = "已扫码，请在米游社确认登录") }
                }
                mutable.update { it.copy(loginStatus = "二维码已过期，请刷新") }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { mutable.update { it.copy(error = if (failure is ApiFailure) failure.message else "暂时无法创建登录，请重试", loginStatus = "请刷新二维码") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun requestSms(phone: String) { qrJob?.cancel(); smsLogin.request(phone) }
    fun loginSms(phone: String, code: String) { qrJob?.cancel(); smsLogin.submit(phone, code) }
    fun loggedOut(clear: Boolean) = work {
        stopAccountWork()
        if (clear && accountId.isNotBlank()) withContext(Dispatchers.IO) { backgroundStore.clear(accountId) }
        repository.logout(clear); mutable.update { AppState() }
    }
    fun setSync(value: Boolean) { repository.store.backgroundSync = value; mutable.update { it.copy(syncEnabled = value) } }
}

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.miyouchat.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.miyouchat.update.AppUpdateViewModel
import coil.compose.AsyncImage
import io.github.miyouchat.BuildConfig
import io.github.miyouchat.data.*
import io.github.miyouchat.sync.MessageSyncService
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun MiyouChatApp(model: ChatViewModel, destination: io.github.miyouchat.ChatDestination?, consumed: () -> Unit) {
    val updater: AppUpdateViewModel = viewModel()
    val state by model.state.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    fun openSettings() { scope.launch { drawer.open() } }
    fun closeSettings(after: () -> Unit = {}) { scope.launch { drawer.close(); after() } }
    var accounts by rememberSaveable { mutableStateOf(false) }
    var backgrounds by rememberSaveable(state.accountId) { mutableStateOf(false) }
    var updates by rememberSaveable { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(state.loggedIn) { if (!state.loggedIn) drawer.close() }
    LaunchedEffect(state.loggedIn,state.accountId,state.syncEnabled) {
        val service = Intent(context,MessageSyncService::class.java)
        if (state.loggedIn && state.syncEnabled) ContextCompat.startForegroundService(context,service)
        else context.stopService(service)
    }
    LaunchedEffect(state.error) { state.error?.let { snack.showSnackbar(it); model.errorDismiss() } }
    LaunchedEffect(destination, state.accountId, state.chats, state.accounts, state.busy) {
        val target = destination ?: return@LaunchedEffect
        if (state.busy) return@LaunchedEffect
        if (target.account != null && target.account != state.accountId) {
            if (state.accounts.any { it.id == target.account }) model.selectAccount(target.account)
            else { drawer.close(); accounts = true; consumed() }
        } else state.chats.firstOrNull { it.id == target.chat }?.let {
            drawer.close(); accounts = false; model.closeHistory(); model.closeArchive()
            model.open(it); consumed()
        }
    }
    YunjianTheme {
        ModalNavigationDrawer(drawerState = drawer,gesturesEnabled = state.loggedIn && (state.active == null || drawer.isOpen),drawerContent = {
            ModalDrawerSheet(Modifier.fillMaxHeight().width((LocalConfiguration.current.screenWidthDp.dp - 48.dp).coerceIn(240.dp,360.dp)),drawerContainerColor = MaterialTheme.colorScheme.surface) {
                if (state.loggedIn) SettingsContent(state,model,
                    manageAccounts = { closeSettings { accounts = true } },
                    export = { closeSettings { model.openArchive() } },
                    updates = { closeSettings { updates = true } },
                    background = { closeSettings { model.clearBackgroundError(); backgrounds = true } },close = { closeSettings() })
            }
        }) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(containerColor = Color.Transparent, snackbarHost = { SnackbarHost(snack) },
                topBar = {
                    if (state.loggedIn) TopAppBar(title = {
                        Text(state.active?.name ?: "云笺",fontSize = if (state.active == null) 23.sp else 18.sp,
                            fontWeight = FontWeight.SemiBold,maxLines = 1,overflow = TextOverflow.Ellipsis)
                    }, navigationIcon = {
                        if (state.active != null) IconButton(onClick = model::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回聊天列表") }
                        else IconButton(onClick = ::openSettings) { Icon(Icons.Outlined.Menu,"打开侧栏") }
                    }, actions = {
                        IconButton(onClick = model::openHistory) { Icon(Icons.Outlined.Search, "搜索聊天记录") }
                        IconButton(onClick = { model.refresh() }, enabled = !state.busy) { Icon(Icons.Outlined.Refresh, "刷新消息") }
                        if (state.active != null) IconButton(onClick = ::openSettings) { Icon(Icons.Outlined.MoreVert,"打开侧栏") }
                    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
                }) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    key(state.accountId) {
                        AnimatedContent(targetState = state,contentKey = { if (!it.loggedIn) "login" else it.active?.id ?: "home" },
                            modifier = Modifier.fillMaxSize(),label = "会话页面",transitionSpec = {
                                val direction = if (targetState.active != null) 1 else -1
                                (fadeIn(tween(YunjianMotion.PAGE)) + slideInHorizontally(tween(YunjianMotion.PAGE,easing = FastOutSlowInEasing)) { it / 14 * direction })
                                    .togetherWith(fadeOut(tween(YunjianMotion.FEEDBACK)) + slideOutHorizontally(tween(YunjianMotion.PAGE,easing = FastOutSlowInEasing)) { -it / 20 * direction })
                            }) { screen -> when {
                                !screen.loggedIn -> LoginScreen(screen,model) { accounts = true }
                                screen.active != null -> Conversation(screen,model)
                                else -> ChatList(screen,model,::openSettings)
                            } }
                    }
                    if (state.authExpired && state.loggedIn) Surface(Modifier.align(Alignment.TopCenter),color = MaterialTheme.colorScheme.errorContainer) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),verticalAlignment = Alignment.CenterVertically) {
                            Text("登录已失效，缓存仍可查看",Modifier.weight(1f),fontSize = 13.sp)
                            TextButton(onClick = model::addAnotherAccount) { Text("重新登录") }
                        }
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
            }
        }
        }
        if (accounts) AccountSheet(state,model) { accounts = false }
        if (updates) UpdateScreen(updater) { updates = false }
        key(state.accountId) {
            if (state.history.visible && state.loggedIn) HistoryScreen(state,model)
            if (state.archive.visible && state.loggedIn) ArchiveScreen(state,model)
            if (backgrounds && state.loggedIn) BackgroundScreen(state,model) { backgrounds = false }
        }
    }
    val drawerVisible = drawer.currentValue != DrawerValue.Closed || drawer.targetValue != DrawerValue.Closed
    BackHandler(state.active != null || drawerVisible) {
        // Read the live animation target here as well: Back may arrive before
        // composition has caught up with the drawer's opening animation.
        if (drawer.currentValue != DrawerValue.Closed || drawer.targetValue != DrawerValue.Closed) closeSettings()
        else model.back()
    }
}

@Composable private fun LoginScreen(state: AppState, model: ChatViewModel, accounts: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val saveQr = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) runCatching { context.contentResolver.openOutputStream(uri)?.use { state.qrBitmap?.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 28.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(io.github.miyouchat.R.drawable.yunjian_icon), "云笺图标", Modifier.size(80.dp).clip(RoundedCornerShape(22.dp)))
        Spacer(Modifier.height(20.dp)); Text("云笺", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("登录米游社，继续你的对话", Modifier.padding(top = 8.dp, bottom = 28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        TabRow(tab, containerColor = Color.Transparent) {
            Tab(tab == 0, onClick = { tab = 0 }, text = { Text("短信登录") })
            Tab(tab == 1, onClick = { tab = 1 }, text = { Text("扫码登录") })
        }
        Spacer(Modifier.height(24.dp))
        if (tab == 0) {
            OutlinedTextField(phone, { phone = it.filter(Char::isDigit).take(11) }, Modifier.fillMaxWidth(), label = { Text("手机号") }, prefix = { Text("+86  ") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(8) }, Modifier.weight(1f), label = { Text("验证码") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true)
                TextButton(onClick = { model.requestSms(phone) }, enabled = phone.length == 11 && !state.busy && state.smsCooldown == 0) { Text(if (state.smsCooldown > 0) "${state.smsCooldown}s" else "获取验证码") }
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = { model.loginSms(phone, code) }, enabled = code.length >= 4 && state.actionType.isNotBlank() && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("登录米游社账号") }
            Text("账号验证由米游社提供。遇到安全验证时，可尝试扫码登录。", Modifier.padding(top = 16.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            state.qrBitmap?.let { bitmap ->
                Image(bitmap.asImageBitmap(), "米游社登录二维码", Modifier.sizeIn(maxWidth = 280.dp, maxHeight = 280.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
                Text(state.loginStatus, Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    TextButton(onClick = { saveQr.launch("miyou-login.png") }) { Text("保存二维码") }
                    TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.qrUrl))) } }) { Text("打开官方确认页") }
                }
            } ?: Text("用已登录的米游社扫描二维码并在手机上确认。也可将二维码放到另一块屏幕上扫描。", Modifier.padding(bottom = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = model::createQr, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (state.qrBitmap == null) "生成登录二维码" else "刷新二维码") }
        }
        if (state.loginStatus.isNotBlank() && tab == 0) Text(state.loginStatus, Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(32.dp)); Text("账号与聊天记录保存在这台设备上", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.accounts.isNotEmpty()) TextButton(onClick = accounts) { Text("选择已保存的账号") }
    }
}

@Composable private fun ChatList(state: AppState, model: ChatViewModel, settings: () -> Unit) {
    var managing by remember { mutableStateOf<Chat?>(null) }
    if (state.chats.isEmpty()) Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Forum, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text("对话会出现在这里", Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleLarge)
            Text("首次同步可能需要一点时间", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { model.refresh() }, enabled = !state.busy) { Text("同步聊天") }
        }
    } else LazyColumn(Modifier.fillMaxSize()) {
        if (!state.syncEnabled) item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("后台消息提醒未开启", Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = settings) { Text("开启提醒", fontSize = 12.sp) }
            }
        }
        item { Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp,vertical = 12.dp),verticalAlignment = Alignment.CenterVertically) {
            Text("消息",style = MaterialTheme.typography.labelLarge,color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp)); Text(state.chats.size.toString(),style = MaterialTheme.typography.labelMedium,color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        items(state.chats, key = { it.id }) { chat ->
            ListItem(modifier = Modifier.animateItem(fadeInSpec = tween(YunjianMotion.SHORT),placementSpec = tween(YunjianMotion.PAGE)).combinedClickable(onClick = { model.open(chat) },onLongClick = { managing = chat },onLongClickLabel = "会话设置").padding(vertical = 3.dp),
                colors = ListItemDefaults.colors(containerColor = if (chat.pinned) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
                leadingContent = { Avatar(chat.name,chat.avatar,50) }, headlineContent = { Text(chat.name,style = MaterialTheme.typography.titleMedium,maxLines = 1,overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(chat.preview,style = MaterialTheme.typography.bodyMedium,color = MaterialTheme.colorScheme.onSurfaceVariant,maxLines = 1,overflow = TextOverflow.Ellipsis,modifier = Modifier.padding(top = 3.dp)) },
                trailingContent = { Column(horizontalAlignment = Alignment.End,verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(timeText(chat.updatedAt,short = true),style = MaterialTheme.typography.labelSmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp),verticalAlignment = Alignment.CenterVertically) {
                        if (chat.pinned) Icon(Icons.Outlined.PushPin,"已置顶",Modifier.size(14.dp),tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (chat.muted) Icon(Icons.Outlined.NotificationsOff,"已免打扰",Modifier.size(14.dp),tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (chat.unread > 0) Badge(containerColor = if (chat.muted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary) { Text(if (chat.unread > 99) "99+" else chat.unread.toString()) }
                    }
                } })
            HorizontalDivider(Modifier.padding(start = 88.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
        }
    }
    managing?.let { chat -> AlertDialog(onDismissRequest = { managing = null },title = { Text(chat.name) },text = {
        Column {
            Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) { Text("置顶会话",Modifier.weight(1f)); Switch(chat.pinned,{ model.setOptions(chat,pinned = it); managing = null }) }
            Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) { Text("消息免打扰",Modifier.weight(1f)); Switch(chat.muted,{ model.setOptions(chat,muted = it); managing = null }) }
            Text("这些设置只保存在当前账号的这台设备上。",fontSize = 12.sp,color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    },confirmButton = { TextButton(onClick = { managing = null }) { Text("完成") } }) }
}

@Composable internal fun Avatar(name: String, url: String, size: Int = 48, description: String? = "${name}的头像") {
    Box(Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        if (url.isNotBlank()) AsyncImage(url,description,Modifier.fillMaxSize(),contentScale = ContentScale.Crop)
        else Text(name.take(1), fontSize = (size / 2.4).sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable private fun Conversation(state: AppState, model: ChatViewModel) {
    val chat = state.active ?: return
    val self = state.accounts.firstOrNull { it.id == state.accountId }
    val background = state.backgrounds.image(chat.id)
    var draft by rememberSaveable(chat.id) { mutableStateOf("") }
    var emoji by remember { mutableStateOf(false) }
    var selectedImage by remember { mutableStateOf<Uri?>(null) }
    var previewImage by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val showLatest by remember { derivedStateOf { list.firstVisibleItemIndex > 3 } }
    val coroutine = rememberCoroutineScope()
    val sendInteraction = remember { MutableInteractionSource() }
    val sendPressed by sendInteraction.collectIsPressedAsState()
    val sendScale = animateFloatAsState(if (sendPressed) 0.94f else 1f,tween(YunjianMotion.FEEDBACK),label = "发送按压")
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { selectedImage = it }
    fun sendDraft() { val value = draft; if (value.isNotBlank() && !state.busy) { model.send(value); draft = "" } }
    LaunchedEffect(state.messages.lastOrNull()?.key) {
        if (state.focusMessage == null && list.firstVisibleItemIndex < 3) list.animateScrollToItem(0)
    }
    LaunchedEffect(state.focusMessage,state.messages.size) {
        val target = state.focusMessage ?: return@LaunchedEffect
        val index = state.messages.asReversed().indexOfFirst { it.key == target }
        if (index >= 0) { list.scrollToItem(index); model.consumeFocus() }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
        ConversationBackground(background)
        LazyColumn(state = list, reverseLayout = true, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val messages = state.messages.asReversed()
            itemsIndexed(messages,key = { _,message -> message.key }) { index,message ->
                val own = message.senderId == state.accountId
                val previous = messages.getOrNull(index+1)
                val timeGroup = startsTimeGroup(previous,message)
                Column(Modifier.fillMaxWidth().animateItem(fadeInSpec = tween(YunjianMotion.SHORT),placementSpec = tween(YunjianMotion.PAGE),fadeOutSpec = null)) {
                    if (timeGroup) Box(Modifier.fillMaxWidth().padding(top = 12.dp,bottom = 14.dp),contentAlignment = Alignment.Center) {
                        Surface(color = MaterialTheme.colorScheme.background.copy(alpha = if (background != null) 0.94f else 0f),shape = RoundedCornerShape(8.dp)) {
                            Text(timeText(message.time),Modifier.padding(horizontal = 8.dp,vertical = 3.dp),style = MaterialTheme.typography.labelSmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    MessageBubble(message,own,chat,self,state.emoticons,model::retry,model::cancelImage,{ previewImage = it },{ model.refresh() },timeGroup || previous?.senderId != message.senderId,background != null)
                }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { model.older() }, enabled = !state.busy && !state.endReached) {
                        Text(if (state.endReached) "已加载到最早记录" else "加载更早消息", fontSize = 13.sp)
                    }
                }
            }
        }
        }
        AnimatedVisibility(showLatest,enter = fadeIn(tween(YunjianMotion.SHORT)) + expandVertically(tween(YunjianMotion.SHORT)),exit = fadeOut(tween(YunjianMotion.FEEDBACK)) + shrinkVertically(tween(YunjianMotion.SHORT))) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { coroutine.launch { list.animateScrollToItem(0) } }) { Text("回到最新消息") }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp,vertical = 8.dp),verticalAlignment = Alignment.Bottom,horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !state.busy) { Icon(Icons.Outlined.AddPhotoAlternate, "发送图片") }
                TextField(draft,{ draft = it },Modifier.weight(1f).heightIn(min = 52.dp,max = 160.dp),placeholder = { Text("发送消息",style = MaterialTheme.typography.bodyMedium) },
                    shape = RoundedCornerShape(18.dp),maxLines = 5,keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),keyboardActions = KeyboardActions(onSend = { sendDraft() }),
                    trailingIcon = { IconButton(onClick = { emoji = true; model.loadEmoticons() },enabled = !state.busy) { Icon(Icons.Outlined.SentimentSatisfiedAlt,"表情与收藏") } },
                    colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedIndicatorColor = Color.Transparent,unfocusedIndicatorColor = Color.Transparent,disabledIndicatorColor = Color.Transparent))
                FilledIconButton(onClick = { sendDraft() },enabled = draft.isNotBlank() && !state.busy,interactionSource = sendInteraction,
                    modifier = Modifier.size(48.dp).graphicsLayer { scaleX = sendScale.value; scaleY = sendScale.value }) { Icon(Icons.AutoMirrored.Outlined.Send,"发送",Modifier.size(21.dp)) }
            }
        }
    }
    if (emoji) EmoticonSheet(state, model) { emoji = false }
    selectedImage?.let { uri -> AlertDialog(onDismissRequest = { selectedImage = null }, title = { Text("发送这张图片？") },
        text = { Column { AsyncImage(uri, "待发送图片", Modifier.fillMaxWidth().heightIn(max = 280.dp)); Text("图片会作为临时自定义表情上传。审核中会显示提示，审核通过后继续发送；确认送达后清理本次新增的临时表情。", Modifier.padding(top = 12.dp), fontSize = 13.sp) } },
        confirmButton = { TextButton(onClick = { model.sendImage(uri); selectedImage = null }) { Text("发送") } }, dismissButton = { TextButton(onClick = { selectedImage = null }) { Text("取消") } }) }
    previewImage?.let { url -> ImagePreview(url) { previewImage = null } }
}

@Composable private fun MessageBubble(message: Message, own: Boolean, chat: Chat, self: AccountSummary?, emoticons: List<Emoticon>, retry: (Message) -> Unit, cancelImage: (Message) -> Unit, preview: (String) -> Unit, reconcile: () -> Unit, showAvatar: Boolean, hasBackground: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            if (!own) { if (showAvatar) Avatar(chat.name,chat.avatar,30) else Spacer(Modifier.width(30.dp)) }
            val maxBubble = ((LocalConfiguration.current.screenWidthDp - 76).dp).coerceIn(80.dp,310.dp)
            Surface(shape = RoundedCornerShape(topStart = if (own) 18.dp else 6.dp,topEnd = if (own) 6.dp else 18.dp,bottomStart = 18.dp,bottomEnd = 18.dp),
                color = if (own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,modifier = Modifier.widthIn(max = maxBubble)) {
                val image = if (message.type == 3 || message.type == 8) runCatching { JSONObject(message.content).optString("url") }.getOrDefault("") else ""
                val name = if (message.type == 2 && message.content.startsWith("_(") && message.content.endsWith(")")) message.content.substring(2, message.content.length - 1) else ""
                val icon = emoticons.firstOrNull { it.name == name }?.url
                when {
                    image.startsWith("https://") -> AsyncImage(image,"聊天图片，点击查看",Modifier.sizeIn(minWidth = 100.dp,maxWidth = maxBubble.coerceAtMost(248.dp),minHeight = 80.dp,maxHeight = 280.dp).clickable { preview(image) },contentScale = ContentScale.Fit)
                    icon != null -> AsyncImage(icon,name,Modifier.padding(10.dp).size(72.dp))
                    else -> SelectionContainer { Text(if (name.isNotBlank()) "[$name]" else MessageCodec.text(message),Modifier.padding(horizontal = 14.dp,vertical = 10.dp),style = MaterialTheme.typography.bodyLarge,
                        color = if (own) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface) }
                }
            }
            if (own) { if (showAvatar) Avatar(self?.displayName ?: "我",self?.avatarUrl.orEmpty(),30,description = "我的头像") else Spacer(Modifier.width(30.dp)) }
        }
        if (own && (message.delivery != Delivery.SENT || message.error.isNotBlank())) Row(Modifier.padding(end = 38.dp,top = 4.dp).widthIn(max = 310.dp)
            .background(MaterialTheme.colorScheme.background.copy(alpha = if (hasBackground) 0.94f else 0f),RoundedCornerShape(8.dp)), verticalAlignment = Alignment.CenterVertically) {
            Text(message.error.ifBlank { when (message.delivery) { Delivery.SENDING -> "发送中"; Delivery.REVIEW -> "图片审核中"; Delivery.REJECTED -> "图片未通过审核"; Delivery.UNCERTAIN -> "结果待确认"; else -> "" } }, modifier = Modifier.weight(1f, fill = false), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (message.delivery == Delivery.FAILED) TextButton(onClick = { retry(message) }) { Text("重试", fontSize = 12.sp) }
            if (message.delivery == Delivery.UNCERTAIN) TextButton(onClick = reconcile) { Text("核对状态", fontSize = 12.sp) }
            if (message.delivery in setOf(Delivery.REVIEW, Delivery.REJECTED) && !message.error.startsWith("已取消")) TextButton(onClick = { cancelImage(message) }) { Text("取消发送", fontSize = 12.sp) }
        }
    }
}

private fun startsTimeGroup(previous: Message?, message: Message): Boolean {
    if (previous == null || message.time - previous.time >= 5 * 60 * 1000L) return true
    val zone = java.time.ZoneId.systemDefault()
    return java.time.Instant.ofEpochMilli(previous.time).atZone(zone).toLocalDate() != java.time.Instant.ofEpochMilli(message.time).atZone(zone).toLocalDate()
}

@Composable private fun ImagePreview(url: String, close: () -> Unit) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        if (uri != null) scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            saved = runCatching {
                val request = okhttp3.Request.Builder().url(url).build()
                okhttp3.OkHttpClient().newCall(request).execute().use { response ->
                    check(response.isSuccessful); context.contentResolver.openOutputStream(uri)!!.use { output -> response.body!!.byteStream().copyTo(output) }
                }; true
            }.getOrDefault(false)
        }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.systemBarsPadding()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "关闭图片") }
                    TextButton(onClick = { save.launch("miyou-image.jpg") }) { Text(if (saved) "已保存" else "保存图片") }
                }
                AsyncImage(url, "聊天图片", Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit)
            }
        }
    }
}

@Composable private fun SettingsContent(state: AppState, model: ChatViewModel, manageAccounts: () -> Unit, export: () -> Unit, background: () -> Unit, updates: () -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    var logout by remember { mutableStateOf(false) }
    var clearCache by remember { mutableStateOf(false) }
    fun sync(value: Boolean) {
        model.setSync(value)
        if (value) ContextCompat.startForegroundService(context, Intent(context, MessageSyncService::class.java))
        else context.stopService(Intent(context, MessageSyncService::class.java))
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) sync(true) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp,bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
                Text("设置",Modifier.weight(1f),style = MaterialTheme.typography.headlineSmall,fontWeight = FontWeight.SemiBold)
                IconButton(onClick = close) { Icon(Icons.Outlined.Close,"关闭侧栏") }
            }
            val account = state.accounts.firstOrNull { it.active }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp),verticalAlignment = Alignment.CenterVertically) {
                Avatar(account?.displayName ?: "账号",account?.avatarUrl.orEmpty(),56,description = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(account?.displayName ?: "账号资料加载中",style = MaterialTheme.typography.titleMedium)
                    Text("UID ${account?.uid ?: state.accountId}",fontSize = 12.sp,color = MaterialTheme.colorScheme.onSurfaceVariant)
                    account?.remark?.takeIf { it.isNotBlank() }?.let { Text("备注：$it",fontSize = 12.sp,color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            val actionState = Modifier.alpha(if (state.busy) 0.38f else 1f).semantics { if (state.busy) disabled() }
            NavigationDrawerItem(label = { Text("切换／添加账号") },selected = false,onClick = { if (!state.busy) manageAccounts() },modifier = actionState,icon = { Icon(Icons.Outlined.ManageAccounts,null) })
            NavigationDrawerItem(label = { Text("导出聊天记录") },selected = false,onClick = { if (!state.busy) export() },modifier = actionState,icon = { Icon(Icons.Outlined.FileDownload,null) })
            NavigationDrawerItem(label = { Text("聊天背景") },selected = false,onClick = background,icon = { Icon(Icons.Outlined.Wallpaper,null) })
            NavigationDrawerItem(label = { Text("检查更新") },selected = false,onClick = updates,icon = { Icon(Icons.Outlined.SystemUpdate,null) })
            HorizontalDivider(Modifier.padding(vertical = 8.dp),color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("后台接收新消息", style = MaterialTheme.typography.titleMedium)
                    Text("开启后显示常驻同步通知", Modifier.padding(top = 4.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.width(12.dp))
                Switch(state.syncEnabled, onCheckedChange = { value ->
                    if (value && Build.VERSION.SDK_INT >= 33 && !NotificationManagerCompat.from(context).areNotificationsEnabled()) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else sync(value)
                })
            }
            Text("强行停止应用后，需要重新打开才能恢复消息同步。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NavigationDrawerItem(label = { Text("系统通知设置") },selected = false,onClick = { runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,context.packageName)) } },icon = { Icon(Icons.Outlined.Notifications,null) })
            NavigationDrawerItem(label = { Text("系统电池优化设置") },selected = false,onClick = { runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },icon = { Icon(Icons.Outlined.BatterySaver,null) })
            Spacer(Modifier.height(24.dp)); OutlinedButton(onClick = { clearCache = false; logout = true },modifier = Modifier.fillMaxWidth(),shape = RoundedCornerShape(14.dp)) { Text("退出登录") }
            Text("云笺 ${BuildConfig.VERSION_NAME}", Modifier.padding(top = 20.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    if (logout) AlertDialog(onDismissRequest = { logout = false }, title = { Text("退出当前账号？") }, text = {
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(clearCache, { clearCache = it }); Text("同时删除这台设备上的聊天缓存") }
    }, confirmButton = { TextButton(onClick = { sync(false); model.loggedOut(clearCache); logout = false; close() }) { Text("退出登录") } }, dismissButton = { TextButton(onClick = { logout = false }) { Text("取消") } })
}

private fun timeText(timestamp: Long, short: Boolean = false): String {
    if (timestamp <= 0) return ""
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val sameDay = today.format(Date(timestamp)) == today.format(Date())
    val years = SimpleDateFormat("yyyy", Locale.getDefault())
    val sameYear = years.format(Date(timestamp)) == years.format(Date())
    val pattern = when {
        short && sameDay -> "HH:mm"
        short && !sameYear -> "yy-MM-dd"
        short -> "MM-dd"
        !sameYear -> "yyyy年MM月dd日 HH:mm"
        else -> "MM月dd日 HH:mm"
    }
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
}

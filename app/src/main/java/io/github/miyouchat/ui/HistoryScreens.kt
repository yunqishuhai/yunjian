@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.miyouchat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.miyouchat.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun whenText(time: Long) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))

@Composable fun HistoryScreen(state: AppState, model: ChatViewModel) {
    val history = state.history
    var keyword by rememberSaveable { mutableStateOf(history.filter.keyword) }
    var scope by rememberSaveable { mutableStateOf(history.filter.chatIds.firstOrNull().orEmpty()) }
    var scopeMenu by remember { mutableStateOf(false) }
    var filters by rememberSaveable { mutableStateOf(false) }
    var from by rememberSaveable { mutableStateOf("") }
    var until by rememberSaveable { mutableStateOf("") }
    var appliedFrom by rememberSaveable { mutableStateOf<Long?>(null) }
    var appliedUntil by rememberSaveable { mutableStateOf<Long?>(null) }
    var sender by rememberSaveable { mutableStateOf(SenderFilter.ALL) }
    var type by rememberSaveable { mutableStateOf(MessageFilter.ALL) }
    val filter = HistoryFilter(keyword,scope.takeIf { it.isNotBlank() }?.let { setOf(it) } ?: emptySet(),appliedFrom,appliedUntil,sender,type)
    LaunchedEffect(keyword,scope,appliedFrom,appliedUntil,sender,type) { model.searchHistory(filter) }
    val coverage = history.coverage.filter { scope.isBlank() || it.chatId == scope }
    val complete = coverage.isNotEmpty() && coverage.all { it.complete }
    Dialog(onDismissRequest = model::closeHistory, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = { TopAppBar(title = { Text("搜索聊天记录") }, navigationIcon = { IconButton(onClick = model::closeHistory) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"关闭搜索") } },
            actions = { IconButton(onClick = { filters = !filters }) { Icon(Icons.Outlined.Tune,"展开搜索筛选") } }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                OutlinedTextField(keyword,{ keyword = it },Modifier.fillMaxWidth().padding(horizontal = 16.dp),label = { Text("关键词") },singleLine = true,
                    trailingIcon = { if (keyword.isNotEmpty()) IconButton(onClick = { keyword = "" }) { Icon(Icons.Outlined.Close,"清空关键词") } })
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { TextButton(onClick = { scopeMenu = true }) { Text(state.chats.firstOrNull { it.id == scope }?.name ?: "全部会话",maxLines = 1,overflow = TextOverflow.Ellipsis) }
                        DropdownMenu(scopeMenu,{ scopeMenu = false }) {
                            DropdownMenuItem({ Text("全部会话") },{ scope = ""; scopeMenu = false })
                            state.chats.forEach { chat -> DropdownMenuItem({ Text(chat.name) },{ scope = chat.id; scopeMenu = false }) }
                        }
                    }
                    Text("仅搜索本机缓存",fontSize = 12.sp,color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (history.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f),contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (filters) item {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item { FilterChip(sender == SenderFilter.ALL,{ sender = SenderFilter.ALL },label = { Text("全部发送者") }) }
                                item { FilterChip(sender == SenderFilter.SELF,{ sender = SenderFilter.SELF },label = { Text("我发送的") }) }
                                item { FilterChip(sender == SenderFilter.OTHER,{ sender = SenderFilter.OTHER },label = { Text("对方发送的") }) }
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item { FilterChip(type == MessageFilter.ALL,{ type = MessageFilter.ALL },label = { Text("全部类型") }) }
                                item { FilterChip(type == MessageFilter.TEXT,{ type = MessageFilter.TEXT },label = { Text("文字与表情") }) }
                                item { FilterChip(type == MessageFilter.IMAGES,{ type = MessageFilter.IMAGES },label = { Text("图片") }) }
                            }
                            DateFields(from,until,{ start,end ->
                                from = start; until = end
                                val range = HistoryDates.range(start,end); appliedFrom = range.first; appliedUntil = range.second
                            })
                        }
                    }
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp,vertical = 8.dp)) {
                            Text("已缓存 ${coverage.sumOf { it.cached }} 条 · ${if (complete) "已同步到最早" else "可能仍有更早记录"}",fontSize = 12.sp,color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (history.syncing) { Text(history.progress,fontSize = 12.sp); TextButton(onClick = model::cancelHistorySync) { Text("暂停同步") } }
                            else TextButton(onClick = { model.syncHistory(filter.chatIds) },enabled = !complete) { Text("补齐更早记录") }
                        }
                        HorizontalDivider()
                    }
                    if (!history.loading && history.hits.isEmpty()) item {
                        Box(Modifier.fillMaxWidth().padding(32.dp),contentAlignment = Alignment.Center) { Text("缓存中没有符合条件的记录",color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    items(history.hits,key = { it.message.key }) { hit ->
                        ListItem(modifier = Modifier.clickable { model.showHit(hit) },headlineContent = { Text(MessageCodec.text(hit.message),maxLines = 3,overflow = TextOverflow.Ellipsis) },
                            overlineContent = { Text("${hit.chatName} · ${if (hit.message.senderId == state.accountId) "我" else "对方"}",maxLines = 1) },
                            supportingContent = { Text(whenText(hit.message.time) + if (hit.message.id.isBlank()) " · 尚未确认发送" else "",fontSize = 12.sp) })
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                    if (history.next != null) item { TextButton(onClick = { model.searchHistory(history.filter,true) },enabled = !history.loading,modifier = Modifier.fillMaxWidth()) { Text("加载更多结果") } }
                }
            }
        }
    }
}

@Composable internal fun DateFields(from: String, until: String, change: (String,String)->Unit, enabled: Boolean = true) {
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    val today = LocalDate.now()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("今天" to 1L,"近 7 天" to 7L,"近 30 天" to 30L).forEach { (label,days) ->
                val start = today.minusDays(days-1).toString(); val end = today.toString()
                FilterChip(from == start && until == end,{ change(start,end) },label = { Text(label) },enabled = enabled)
            }
            FilterChip(from.isBlank() && until.isBlank(),{ change("","") },label = { Text("全部日期") },enabled = enabled)
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("start" to ("开始日期" to from),"end" to ("结束日期" to until)).forEach { (key,value) ->
                OutlinedButton(onClick = { picking = key },enabled = enabled,modifier = Modifier.weight(1f).heightIn(min = 64.dp),shape = RoundedCornerShape(14.dp),contentPadding = PaddingValues(horizontal = 12.dp,vertical = 10.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(value.first,style = MaterialTheme.typography.labelMedium,color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value.second.ifBlank { "不限" }.replace('-','/'),style = MaterialTheme.typography.bodyMedium)
                    }
                    Icon(Icons.Outlined.CalendarMonth,null,Modifier.size(20.dp))
                }
            }
        }
    }
    picking?.let { field ->
        val selected = if (field == "start") from else until
        val initial = runCatching { HistoryDates.pickerMillis(selected) }.getOrNull() ?: HistoryDates.pickerMillis(today.toString())
        val compact = LocalConfiguration.current.screenHeightDp < 600 || LocalDensity.current.fontScale > 1.3f
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial,initialDisplayMode = if (compact) DisplayMode.Input else DisplayMode.Picker)
        DatePickerDialog(onDismissRequest = { picking = null },confirmButton = {
            TextButton(enabled = picker.selectedDateMillis != null,onClick = {
                val date = HistoryDates.pickerDate(picker.selectedDateMillis!!)
                if (field == "start") change(date,if (until.isBlank() || date > until) date else until)
                else change(if (from.isBlank() || date < from) date else from,date)
                picking = null
            }) { Text("确定") }
        },dismissButton = { TextButton(onClick = { picking = null }) { Text("取消") } }) {
            DatePicker(picker,title = { Text(if (field == "start") "选择开始日期" else "选择结束日期",Modifier.padding(start = 24.dp,top = 16.dp)) })
        }
    }
}

@Composable fun ArchiveScreen(state: AppState, model: ChatViewModel) {
    var selected by remember { mutableStateOf(state.active?.let { setOf(it.id) } ?: state.chats.map { it.id }.toSet()) }
    var from by rememberSaveable { mutableStateOf("") }; var until by rememberSaveable { mutableStateOf("") }
    var images by rememberSaveable { mutableStateOf(true) }; var dateError by remember { mutableStateOf<String?>(null) }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> model.archiveDestination(uri) }
    val archive = state.archive
    Dialog(onDismissRequest = model::closeArchive,properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = { TopAppBar(title = { Text("导出聊天记录") },navigationIcon = { IconButton(onClick = model::closeArchive,enabled = !archive.running) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"关闭导出") } }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(),contentPadding = PaddingValues(20.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("导出为 ZIP，内含可离线阅读的 HTML 和 JSON 备份。只包含所选会话已缓存、已确认的消息。",color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { DateFields(from,until,{ start,end -> from = start; until = end; dateError = null },enabled = !archive.running) }
                item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(images,{ images = it },enabled = !archive.running); Text("保存可获取的图片与表情附件") }
                    Text("未获取的附件会写入清单；如需更早记录，可先在搜索页补齐历史。",fontSize = 12.sp,color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
                    Text("选择会话",Modifier.weight(1f),style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { selected = state.chats.map { it.id }.toSet() },enabled = !archive.running) { Text("全选") }
                } }
                items(state.chats,key = { it.id }) { chat ->
                    Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(chat.id in selected,{ checked -> selected = if (checked) selected + chat.id else selected - chat.id },enabled = !archive.running)
                        Text(chat.name,Modifier.weight(1f),maxLines = 2,overflow = TextOverflow.Ellipsis)
                    }
                }
                dateError?.let { error -> item { Text(error,color = MaterialTheme.colorScheme.error) } }
                item {
                    Button(enabled = selected.isNotEmpty() && !archive.running,modifier = Modifier.fillMaxWidth(),onClick = {
                        try { val range = HistoryDates.range(from,until); dateError = null
                            model.prepareArchive(HistoryFilter(chatIds = selected,from = range.first,until = range.second),images)
                            document.launch("yunjian-history-${LocalDate.now()}.zip")
                        } catch (_: Exception) { dateError = "请选择有效的日期范围" }
                    }) { Text("选择保存位置并导出") }
                }
                if (archive.running) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(archive.progress,Modifier.padding(top = 8.dp)); TextButton(onClick = model::cancelArchive) { Text("取消导出") } }
                if (archive.result.isNotBlank()) item { Text(archive.result,color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@Composable fun AccountSheet(state: AppState, model: ChatViewModel, close: ()->Unit) {
    var editing by remember { mutableStateOf<AccountSummary?>(null) }; var label by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { model.refreshAccountProfiles() }
    ModalBottomSheet(onDismissRequest = close,sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(contentPadding = PaddingValues(start = 24.dp,end = 24.dp,bottom = 32.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("账号管理",Modifier.weight(1f),style = MaterialTheme.typography.headlineSmall)
                    IconButton(onClick = { model.refreshAccountProfiles(true) },enabled = !state.profilesLoading) { Icon(Icons.Outlined.Refresh,"刷新账号头像和昵称") }
                    IconButton(onClick = close) { Icon(Icons.Outlined.Close,"关闭账号面板") }
                }
                Text("仅接收当前账号消息，记录与收藏独立保存。",Modifier.padding(top = 8.dp,bottom = 20.dp),style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.profilesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.profilesError?.let { Text(it,Modifier.padding(bottom = 8.dp),fontSize = 12.sp,color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(state.accounts,key = { it.id }) { account ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(if (account.active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).selectable(selected = account.active,enabled = !state.busy,role = Role.RadioButton,onClick = { model.selectAccount(account.id); close() }).padding(horizontal = 12.dp,vertical = 14.dp),verticalAlignment = Alignment.CenterVertically) {
                        Avatar(account.displayName,account.avatarUrl,44,description = null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(account.displayName,style = MaterialTheme.typography.titleMedium,maxLines = 2,overflow = TextOverflow.Ellipsis)
                            Text("UID ${account.uid}",fontSize = 12.sp,color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (account.remark.isNotBlank()) Text("备注：${account.remark}",fontSize = 12.sp,color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (account.active) Text("当前账号",style = MaterialTheme.typography.labelSmall,color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(8.dp))
                        if (account.active) Icon(Icons.Outlined.CheckCircle,null,Modifier.size(22.dp),tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { editing = account; label = account.remark },enabled = !state.busy) { Icon(Icons.Outlined.Edit,"修改本地账号备注") }
                }
            }
            item { OutlinedButton(onClick = { model.addAnotherAccount(); close() },enabled = !state.busy,modifier = Modifier.fillMaxWidth().padding(top = 20.dp),shape = RoundedCornerShape(14.dp)) { Text("添加另一个账号") } }
        }
    }
    editing?.let { account -> AlertDialog(onDismissRequest = { editing = null },title = { Text("本地账号备注") },text = { Column {
        OutlinedTextField(label,{ label = it.take(24) },label = { Text("备注，可留空") },singleLine = true)
        Text("备注仅在云笺显示，不会修改米游社昵称。",Modifier.padding(top = 12.dp),fontSize = 12.sp,color = MaterialTheme.colorScheme.onSurfaceVariant)
    } },confirmButton = { TextButton(onClick = { model.renameAccount(account.id,label); editing = null }) { Text("保存") } },dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }) }
}

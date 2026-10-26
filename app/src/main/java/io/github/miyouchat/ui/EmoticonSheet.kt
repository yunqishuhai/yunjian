@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package io.github.miyouchat.ui

import androidx.compose.foundation.combinedClickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.miyouchat.data.*

@Composable fun EmoticonSheet(state: AppState, model: ChatViewModel, close: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var officialId by rememberSaveable { mutableStateOf("") }
    var favoriteId by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<Emoticon?>(null) }
    var manage by remember { mutableStateOf(false) }
    var deletingCustom by remember { mutableStateOf<Emoticon?>(null) }
    var pendingImage by remember { mutableStateOf<android.net.Uri?>(null) }
    var importGroup by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { pendingImage = it }
    val official = remember(state.emoticons) { state.emoticons.groupBy { it.groupId } }
    val currentOfficial = officialId.takeIf { official.containsKey(it) } ?: official.keys.firstOrNull().orEmpty()
    val currentFavorite = state.favorites.firstOrNull { it.id == favoriteId } ?: state.favorites.firstOrNull()
    val items = when (tab) { 0 -> official[currentOfficial].orEmpty(); 1 -> state.customEmoticons; else -> currentFavorite?.items.orEmpty() }
    LaunchedEffect(Unit) { model.loadCustomEmoticons() }
    ModalBottomSheet(onDismissRequest = close, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().height(560.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("表情", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (tab == 1) {
                IconButton(onClick = { model.loadCustomEmoticons() }, enabled = !state.busy && !state.customLoading) { Icon(Icons.Outlined.Refresh, "刷新审核状态") }
                IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !state.busy) { Icon(Icons.Outlined.AddPhotoAlternate, "添加自定义表情") }
            }
            if (tab == 2) IconButton(onClick = { manage = true }) { Icon(Icons.Outlined.FolderOpen, "管理收藏分组") }
        }
        TabRow(tab, containerColor = MaterialTheme.colorScheme.surface) {
            Tab(tab == 0, onClick = { tab = 0 }, text = { Text("官方表情") })
            Tab(tab == 1, onClick = { tab = 1 }, text = { Text("自定义") })
            Tab(tab == 2, onClick = { tab = 2 }, text = { Text("收藏分组") })
        }
        LazyRow(modifier = Modifier.height(64.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tab == 0) items(official.keys.toList(), key = { it }) { id ->
                FilterChip(id == currentOfficial, { officialId = id }, label = { Text(official[id]?.firstOrNull()?.groupName.orEmpty()) }, modifier = Modifier.heightIn(min = 48.dp))
            } else if (tab == 2) items(state.favorites, key = { it.id }) { group ->
                FilterChip(group.id == currentFavorite?.id, { favoriteId = group.id }, label = { Text("${group.name} · ${group.items.size}") }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        if (items.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (state.busy || (tab == 1 && state.customLoading)) CircularProgressIndicator()
            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(when (tab) { 0 -> "暂时没有表情"; 1 -> state.customError ?: "米游社表情库中暂无自定义表情"; else -> "这个分组还没有收藏" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (tab == 0) TextButton(onClick = { model.loadEmoticons() }) { Text("重新加载") }
                else if (tab == 1 && state.customError != null) TextButton(onClick = { model.loadCustomEmoticons() }) { Text("重新加载") }
                else Text(if (tab == 1) "点击右上角添加图片" else "长按表情可加入分组", Modifier.padding(top = 8.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else LazyVerticalGrid(GridCells.Adaptive(80.dp), Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(12.dp)) {
            gridItems(items, key = { it.key }) { item ->
                Column(Modifier.clip(RoundedCornerShape(12.dp)).combinedClickable(
                    enabled = !state.busy,
                    onClickLabel = "发送${item.name}", onLongClickLabel = "收藏与分组",
                    onClick = { if (item.canSend) { model.sendEmoticon(item); close() } else selected = item }, onLongClick = { selected = item }).padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    AsyncImage(item.url, item.name, Modifier.size(56.dp))
                    Text(if (item.canSend) item.name else when (item.auditStatus) { "CustomEmoticonAuditStatusNoPass" -> "审核未通过"; "CustomEmoticonAuditStatusPend" -> "待审核"; else -> "状态待确认" }, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Text("轻点发送 · 长按收藏或调整分组", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    selected?.let { item -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("收藏到分组") }, text = {
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(state.favorites, key = { it.id }) { group ->
                val saved = group.items.any { it.key == item.key }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(group.name, Modifier.weight(1f))
                    TextButton(onClick = { model.favorite(item, group.id, remove = saved) }, enabled = !state.busy) { Text(if (saved) "移出" else "加入") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { selected = null }) { Text("完成") } },
        dismissButton = { Row {
            TextButton(onClick = { selected = null; manage = true }) { Text("管理分组") }
            if (item.customId.isNotBlank()) TextButton(onClick = { selected = null; deletingCustom = item }) { Text("从表情库删除") }
        } }) }
    if (manage) FavoriteGroupManager(state, model) { manage = false }
    pendingImage?.let { uri -> AlertDialog(onDismissRequest = { pendingImage = null }, title = { Text("添加到米游社表情库") },
        text = { Column {
            AsyncImage(uri, "待添加表情", Modifier.fillMaxWidth().heightIn(max = 200.dp))
            Text("图片会上传到米游社，审核通过后可发送。", Modifier.padding(vertical = 12.dp), fontSize = 13.sp)
            Text("同时加入本地分组", fontSize = 13.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(importGroup.isBlank(), { importGroup = "" }, label = { Text("暂不分组") }) }
                items(state.favorites, key = { it.id }) { group -> FilterChip(importGroup == group.id, { importGroup = group.id }, label = { Text(group.name) }) }
            }
        } }, confirmButton = { TextButton(enabled = !state.busy, onClick = { model.addCustomEmoticon(uri, importGroup.takeIf { it.isNotBlank() }); pendingImage = null }) { Text("添加") } },
        dismissButton = { TextButton(onClick = { pendingImage = null }) { Text("取消") } }) }
    deletingCustom?.let { item -> AlertDialog(onDismissRequest = { deletingCustom = null }, title = { Text("从米游社表情库删除？") },
        text = { Text("其他设备的米游社表情库也会移除此项。已发送消息的图片能否长期保留，尚未验证。") },
        confirmButton = { TextButton(onClick = { model.removeCustomEmoticon(item); deletingCustom = null }, enabled = !state.busy) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deletingCustom = null }) { Text("取消") } }) }
}

@Composable private fun FavoriteGroupManager(state: AppState, model: ChatViewModel, close: () -> Unit) {
    var edit by remember { mutableStateOf<FavoriteGroup?>(null) }
    var new by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<FavoriteGroup?>(null) }
    AlertDialog(onDismissRequest = close, title = { Text("收藏分组") }, text = {
        Column {
            Text("分组保存在当前账号的这台设备上。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(state.favorites, key = { it.id }) { group ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(group.name, Modifier.weight(1f))
                        IconButton(onClick = { edit = group; name = group.name }) { Icon(Icons.Outlined.Edit, "重命名${group.name}") }
                        IconButton(onClick = { deleting = group }) { Icon(Icons.Outlined.DeleteOutline, "删除${group.name}") }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { new = true; name = "" }) { Text("新建分组") } }, dismissButton = { TextButton(onClick = close) { Text("完成") } })
    if (new || edit != null) AlertDialog(onDismissRequest = { new = false; edit = null }, title = { Text(if (new) "新建分组" else "重命名分组") },
        text = { OutlinedTextField(name, { name = it.take(24) }, label = { Text("分组名称") }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank() && !state.busy, onClick = { model.editFavoriteGroup(edit?.id, name); new = false; edit = null }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { new = false; edit = null }) { Text("取消") } })
    deleting?.let { group -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除“${group.name}”？") },
        text = { Text("删除此分组和其中的本地收藏。已发送的消息和米游社表情库不受影响。") },
        confirmButton = { TextButton(onClick = { model.deleteFavoriteGroup(group.id); deleting = null }, enabled = !state.busy) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

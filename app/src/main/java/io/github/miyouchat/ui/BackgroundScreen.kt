@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.miyouchat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import java.io.File

/** Decorative backdrop only; toolbar, composer and bubbles keep opaque theme surfaces. */
@Composable internal fun ConversationBackground(image: String?) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (image != null) {
            AsyncImage(File(image),null,Modifier.fillMaxSize(),contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(
                alpha = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) 0.34f else 0.14f)))
        }
    }
}

@Composable internal fun BackgroundScreen(state: AppState, model: ChatViewModel, close: () -> Unit) {
    val chat = state.active
    var conversation by rememberSaveable { mutableStateOf(chat != null) }
    // Keep picker provenance, even if recomposition happens while the system picker is open.
    var pickerAccount by rememberSaveable { mutableStateOf("") }
    var pickerChat by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) model.setBackground(uri,pickerChat,pickerAccount)
    }
    val scopeChat = if (conversation) chat?.id else null
    val image = state.backgrounds.image(scopeChat)
    val hasOwnImage = if (scopeChat == null) state.backgrounds.defaultImage != null else scopeChat in state.backgrounds.conversations
    val self = state.accounts.firstOrNull { it.id == state.accountId }
    val previewHeight = (LocalConfiguration.current.screenHeightDp * 0.38f).dp.coerceIn(160.dp,320.dp)
    Dialog(onDismissRequest = close,properties = DialogProperties(usePlatformDefaultWidth = false,decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(),color = MaterialTheme.colorScheme.background) {
            Scaffold(Modifier.systemBarsPadding(),containerColor = MaterialTheme.colorScheme.background,
                topBar = { TopAppBar(title = { Text("聊天背景") },navigationIcon = {
                    IconButton(onClick = close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"关闭背景设置") }
                }) }) { padding ->
                Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (chat != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !conversation,onClick = { conversation = false; model.clearBackgroundError() },label = { Text("默认背景") })
                        FilterChip(selected = conversation,onClick = { conversation = true; model.clearBackgroundError() },label = { Text("仅当前会话") })
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (scopeChat == null) "当前账号的默认背景" else "${chat?.name}的聊天背景",style = MaterialTheme.typography.titleMedium)
                        Text(if (scopeChat == null) "应用到当前账号的所有会话，单独设置的会话除外。" else if (hasOwnImage) "已为这个会话单独设置背景。" else "当前跟随默认背景。",
                            style = MaterialTheme.typography.bodyMedium,color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(Modifier.fillMaxWidth().height(previewHeight).clip(RoundedCornerShape(22.dp))) {
                        ConversationBackground(image)
                        Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Surface(Modifier.align(Alignment.CenterHorizontally),shape = RoundedCornerShape(8.dp),color = MaterialTheme.colorScheme.background.copy(alpha = 0.94f)) {
                                Text("背景预览",Modifier.padding(horizontal = 10.dp,vertical = 4.dp),style = MaterialTheme.typography.labelMedium,color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Avatar(chat?.name ?: "云笺",chat?.avatar.orEmpty(),30,description = null)
                                Surface(Modifier.weight(1f,fill = false),shape = RoundedCornerShape(16.dp),color = MaterialTheme.colorScheme.surface) {
                                    Text("把喜欢的风景带进对话",Modifier.padding(horizontal = 14.dp,vertical = 10.dp),style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            Row(Modifier.align(Alignment.End),horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(Modifier.weight(1f,fill = false),shape = RoundedCornerShape(16.dp),color = MaterialTheme.colorScheme.primaryContainer) {
                                    Text("今天也有好心情",Modifier.padding(horizontal = 14.dp,vertical = 10.dp),style = MaterialTheme.typography.bodyMedium,color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                Avatar(self?.displayName ?: "我",self?.avatarUrl.orEmpty(),30,description = null)
                            }
                        }
                    }
                    if (state.backgroundBusy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("正在保存背景…",style = MaterialTheme.typography.bodyMedium)
                    }
                    state.backgroundError?.let { Text(it,color = MaterialTheme.colorScheme.error,style = MaterialTheme.typography.bodyMedium) }
                    Button(onClick = {
                        pickerAccount = state.accountId; pickerChat = scopeChat
                        model.clearBackgroundError()
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },enabled = !state.backgroundBusy,modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Outlined.AddPhotoAlternate,null); Spacer(Modifier.width(8.dp)); Text("从相册选择")
                    }
                    OutlinedButton(onClick = { model.setBackground(null,scopeChat,state.accountId) },enabled = hasOwnImage && !state.backgroundBusy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),shape = RoundedCornerShape(14.dp)) {
                        Text(if (scopeChat == null) "恢复纯色背景" else "恢复跟随默认")
                    }
                    Text("选择后立即应用。图片居中铺满，超出的边缘会裁切；只保存在这台手机，不会上传或发送给对方。",
                        style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

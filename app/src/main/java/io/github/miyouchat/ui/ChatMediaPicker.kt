@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.miyouchat.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.miyouchat.data.CameraCaptureStore

internal class CapturePhoto : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent = super.createIntent(context, input).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        clipData = ClipData.newRawUri("camera-output", input)
    }
}

/** Register at the app root so a camera result cannot be delivered to a different conversation. */
@Composable internal fun ChatMediaPicker(state: AppState, model: ChatViewModel, menu: Boolean, dismissMenu: () -> Unit) {
    val context = LocalContext.current
    val captures = remember(context) { CameraCaptureStore(context.applicationContext) }
    var targetAccount by rememberSaveable { mutableStateOf("") }
    var targetChat by rememberSaveable { mutableStateOf("") }
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedImage by rememberSaveable { mutableStateOf<String?>(null) }
    var capturedImage by rememberSaveable { mutableStateOf(false) }
    var inFlight by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val current by rememberUpdatedState(state)
    fun matches() = current.loggedIn && current.accountId == targetAccount && current.active?.id == targetChat
    fun discardSelection() {
        if (capturedImage) selectedImage?.let { captures.discard(Uri.parse(it)) }
        selectedImage = null; capturedImage = false
    }
    val camera = rememberLauncherForActivityResult(CapturePhoto()) { saved ->
        val uri = pendingCapture?.let(Uri::parse)
        pendingCapture = null; inFlight = false
        if (saved && uri != null && matches() && captures.ready(uri)) {
            selectedImage = uri.toString(); capturedImage = true
        } else {
            uri?.let(captures::discard)
            if (saved) error = if (!matches()) "会话已改变，请重新拍照" else "照片未能保存，请重新拍照"
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        inFlight = false
        if (uri != null) {
            if (matches()) { selectedImage = uri.toString(); capturedImage = false }
            else error = "会话已改变，请重新选择图片"
        }
    }
    fun target(): Boolean {
        val chat = current.active ?: return false
        if (!current.loggedIn || current.busy || inFlight) return false
        targetAccount = current.accountId; targetChat = chat.id
        return true
    }
    fun takePhoto() {
        if (!target()) return
        try {
            val uri = captures.create()
            pendingCapture = uri.toString(); inFlight = true
            camera.launch(uri)
        } catch (_: Exception) {
            pendingCapture?.let { captures.discard(Uri.parse(it)) }
            pendingCapture = null; inFlight = false
            error = "无法打开相机，请检查是否有可用的相机应用后重试"
        }
    }
    LaunchedEffect(state.accountId, state.active?.id, selectedImage) {
        if (selectedImage != null && !matches()) { discardSelection(); error = "会话已改变，请重新选择图片" }
        if (menu && state.active == null) dismissMenu()
    }
    if (menu && state.loggedIn && state.active != null) ModalBottomSheet(onDismissRequest = dismissMenu) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("发送图片", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
            ListItem(headlineContent = { Text("从相册选择") }, leadingContent = { Icon(Icons.Outlined.AddPhotoAlternate, null) },
                modifier = Modifier.clickable(enabled = !state.busy && !inFlight) {
                    dismissMenu()
                    if (target()) try {
                        inFlight = true
                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } catch (_: Exception) { inFlight = false; error = "无法打开相册，请稍后重试" }
                })
            ListItem(headlineContent = { Text("拍照") }, supportingContent = { Text("拍摄后预览，确认再发送") }, leadingContent = { Icon(Icons.Outlined.PhotoCamera, null) },
                modifier = Modifier.clickable(enabled = !state.busy && !inFlight) { dismissMenu(); takePhoto() })
        }
    }
    selectedImage?.let { image ->
        AlertDialog(onDismissRequest = ::discardSelection, title = { Text(if (capturedImage) "发送这张照片？" else "发送这张图片？") },
            text = { Column {
                AsyncImage(Uri.parse(image), "待发送照片", Modifier.fillMaxWidth().heightIn(max = 280.dp))
                Text("图片需经米游社审核，通过后发送。可在消息气泡中查看审核与发送状态。", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(enabled = !state.busy && matches(), onClick = {
                val owned = capturedImage
                selectedImage = null; capturedImage = false
                model.sendImage(Uri.parse(image), targetAccount, targetChat, owned)
            }) { Text("发送") } },
            dismissButton = { Row {
                if (capturedImage) TextButton(onClick = { discardSelection(); takePhoto() }) { Text("重拍") }
                TextButton(onClick = ::discardSelection) { Text("取消") }
            } })
    }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("图片未能打开") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } }) }
}

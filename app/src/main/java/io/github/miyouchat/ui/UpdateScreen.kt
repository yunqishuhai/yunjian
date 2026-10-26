@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.miyouchat.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.miyouchat.BuildConfig
import io.github.miyouchat.update.AppUpdateViewModel
import java.util.Locale

@Composable internal fun UpdateScreen(model: AppUpdateViewModel,close: () -> Unit) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    var canInstall by remember { mutableStateOf(context.packageManager.canRequestPackageInstalls()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        canInstall = context.packageManager.canRequestPackageInstalls()
    }
    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }
            .onFailure { model.reportError("未找到可打开链接的浏览器") }
    }
    Dialog(onDismissRequest = close,properties = DialogProperties(usePlatformDefaultWidth = false,decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(),color = MaterialTheme.colorScheme.background) {
            Scaffold(Modifier.systemBarsPadding(),topBar = { TopAppBar(title = { Text("检查更新") },navigationIcon = {
                IconButton(onClick = close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"关闭更新页面") }
            }) }) { padding ->
                Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("云笺",style = MaterialTheme.typography.headlineMedium)
                    Text("当前版本 ${BuildConfig.VERSION_NAME}",style = MaterialTheme.typography.bodyMedium,color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (BuildConfig.DEBUG) Text("开发预览版",style = MaterialTheme.typography.labelLarge,color = MaterialTheme.colorScheme.primary)
                    if (model.repository == null) Text("更新服务尚未启用。",style = MaterialTheme.typography.bodyLarge)
                    if (state.checking) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在检查新版本…") }
                    if (state.checked && state.available == null) Text("当前已是最新版本",style = MaterialTheme.typography.titleMedium)
                    state.available?.let { release ->
                        HorizontalDivider()
                        Text("发现新版本 ${release.versionName}",style = MaterialTheme.typography.titleLarge)
                        Text(String.format(Locale.getDefault(),"安装包 %.1f MB",release.bytes/1024.0/1024.0),color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (release.notes.isNotBlank()) Text(release.notes,style = MaterialTheme.typography.bodyMedium)
                        if (state.downloading) {
                            LinearProgressIndicator(progress = { (state.downloadedBytes.toFloat()/release.bytes).coerceIn(0f,1f) },modifier = Modifier.fillMaxWidth())
                            Text("正在下载 ${(state.downloadedBytes*100/release.bytes).coerceIn(0,100)}%")
                            OutlinedButton(onClick = model::cancelDownload,modifier = Modifier.heightIn(min = 48.dp)) { Text("取消下载") }
                        } else if (state.ready != null) {
                            Text("下载完成，安装包校验通过",style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = {
                                if (!context.packageManager.canRequestPackageInstalls()) {
                                    runCatching { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}"))) }
                                        .onFailure { model.reportError("请在系统设置中允许云笺安装应用后重试") }
                                } else {
                                    val intent = model.installIntent()
                                    if (intent == null) model.reportError("安装包已被清理，请重新检查并下载")
                                    else runCatching { context.startActivity(intent) }.onFailure { model.reportError("无法打开系统安装页面，请在发布页手动下载") }
                                }
                            },modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),shape = RoundedCornerShape(14.dp)) {
                                Text(if (canInstall) "安装更新" else "允许安装更新")
                            }
                        } else Button(onClick = model::download,modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),shape = RoundedCornerShape(14.dp)) { Text("下载更新") }
                        TextButton(onClick = { open(release.pageUrl) },modifier = Modifier.heightIn(min = 48.dp)) { Text("在浏览器查看此版本") }
                    }
                    state.error?.let { Text(it,color = MaterialTheme.colorScheme.error,style = MaterialTheme.typography.bodyMedium) }
                    OutlinedButton(onClick = model::check,enabled = model.repository != null && !state.checking && !state.downloading,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),shape = RoundedCornerShape(14.dp)) { Text(if (state.checked || state.error != null) "重新检查" else "检查新版本") }
                    if (state.available == null) model.repository?.let { source -> TextButton(onClick = { open(source.releasesUrl) },modifier = Modifier.heightIn(min = 48.dp)) { Text("在浏览器打开发行页") } }
                    Text("更新由 GitHub Releases 提供，安装需要你在系统页面确认。",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

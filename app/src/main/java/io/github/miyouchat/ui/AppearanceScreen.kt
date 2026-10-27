@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.miyouchat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.miyouchat.data.Appearance
import io.github.miyouchat.data.ThemeMode
import io.github.miyouchat.data.ThemePalette

@Composable internal fun AppearanceScreen(value: Appearance, change: (Appearance) -> Unit, close: () -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ThemeSystemBars(value.dark(isSystemInDarkTheme()))
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(Modifier.systemBarsPadding(), containerColor = MaterialTheme.colorScheme.background, topBar = {
                TopAppBar(title = { Text("外观主题") }, navigationIcon = {
                    IconButton(onClick = close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "关闭主题设置") }
                })
            }) { padding ->
                Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("明暗模式", style = MaterialTheme.typography.titleMedium)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(selected = value.mode == mode, onClick = { change(value.copy(mode = mode)) },
                                shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size)) { Text(mode.title, maxLines = 1) }
                        }
                    }
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("${value.palette.title} · ${value.mode.title}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
                                Text("我喜欢你。", Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                            Surface(Modifier.align(Alignment.End), color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                                Text("我也...", Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                    Text("配色", style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.selectableGroup()) {
                        ThemePalette.entries.forEach { palette ->
                            val colors = yunjianColors(palette, false)
                            val selected = value.palette == palette
                            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, MaterialTheme.shapes.medium)
                                .selectable(selected = selected, role = Role.RadioButton,
                                onClick = { change(value.copy(palette = palette)) }).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(44.dp).clip(CircleShape).background(colors.primaryContainer).border(1.dp, colors.outlineVariant, CircleShape), contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(22.dp).clip(CircleShape).background(colors.primary))
                                }
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(palette.title, style = MaterialTheme.typography.titleMedium)
                                    Text(palette.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                RadioButton(selected = value.palette == palette, onClick = null)
                            }
                        }
                    }
                    Text("选择后立即生效，所有账号共用。聊天背景图片可单独设置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

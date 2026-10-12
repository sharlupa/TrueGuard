package com.trueguard.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.trueguard.core.ConfigLink
import com.trueguard.core.ExportKind
import com.trueguard.core.Settings
import com.trueguard.ui.LocalSnackbar
import com.trueguard.ui.components.Clipboard
import com.trueguard.ui.components.MorphIconButton
import kotlinx.coroutines.launch

private val ExportKind.icon: ImageVector
    get() = when (this) {
        ExportKind.FULL -> Icons.Filled.Inventory2
        ExportKind.SERVERS -> Icons.Filled.Dns
        ExportKind.ACTIVE_SERVER -> Icons.Filled.RadioButtonChecked
        ExportKind.NETWORK -> Icons.Filled.Language
        ExportKind.PERFORMANCE -> Icons.Filled.Speed
        ExportKind.INTERFACE -> Icons.Filled.Palette
    }

/** «Экспорт конфигурации» (c.m / t1): ссылка trueguard://config копируется в буфер. */
@Composable
fun ExportDialog(onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(30.dp), color = cs.surfaceContainerHigh, contentColor = cs.onSurface) {
            Column(Modifier.padding(22.dp).verticalScroll(rememberScrollState()).widthIn(max = 480.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(Modifier.size(48.dp), shape = RoundedCornerShape(18.dp), color = cs.primaryContainer) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.IosShare, null, tint = cs.onPrimaryContainer) }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Экспорт конфигурации", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                        Text("Выберите, что положить в ссылку", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                    MorphIconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Закрыть") }
                }
                HorizontalDivider(Modifier.padding(vertical = 16.dp), color = cs.outlineVariant.copy(alpha = 0.5f))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ExportKind.entries.forEach { kind ->
                        val enabled = when (kind) {
                            ExportKind.SERVERS -> Settings.instance.servers.isNotEmpty()
                            ExportKind.ACTIVE_SERVER -> Settings.instance.activeServer != null
                            else -> true
                        }
                        Surface(
                            onClick = {
                                Clipboard.copy(ConfigLink.buildLink(Settings.instance, kind))
                                onDismiss()
                                scope.launch { snackbar.showSnackbar("Скопировано: ${kind.title}") }
                            },
                            enabled = enabled,
                            shape = RoundedCornerShape(22.dp),
                            color = if (kind == ExportKind.FULL) cs.secondaryContainer else cs.surfaceContainer,
                            border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.3f)),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(kind.icon, null, tint = if (enabled) cs.primary else cs.outline)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(kind.title, fontWeight = FontWeight.Bold, color = if (enabled) cs.onSurface else cs.outline)
                                    Text(kind.subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                }
                                Icon(Icons.Filled.ContentCopy, null, Modifier.size(20.dp), tint = cs.outline)
                            }
                        }
                    }
                }
            }
        }
    }
}

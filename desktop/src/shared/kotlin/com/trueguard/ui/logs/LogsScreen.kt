package com.trueguard.ui.logs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trueguard.core.LogStore
import com.trueguard.core.Settings
import com.trueguard.ui.LocalSnackbar
import com.trueguard.ui.components.Clipboard
import com.trueguard.ui.components.ConnectedChoice
import com.trueguard.ui.components.MorphIconButton
import kotlinx.coroutines.launch

/** Вкладка «Логи» (c.s): краткий журнал событий и сырые строки ядра. */
@Composable
fun LogsScreen() {
    val cs = MaterialTheme.colorScheme
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var detailed by remember { mutableStateOf(Settings.instance.detailedLogs) }
    val version = LogStore.version.intValue
    val entries = remember(version, detailed) { LogStore.snapshot(detailed) }
    val list = rememberLazyListState()
    // новые строки — прокрутка вниз, если пользователь и так был внизу
    LaunchedEffect(entries.size, detailed) {
        val atEnd = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= entries.size - 3 } ?: true
        if (entries.isNotEmpty() && atEnd) list.scrollToItem(entries.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Лог событий", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            MorphIconButton(
                onClick = {
                    Clipboard.copy(LogStore.dump(detailed))
                    scope.launch { snackbar.showSnackbar("Все логи скопированы") }
                },
                container = cs.secondaryContainer, content = cs.onSecondaryContainer,
            ) { Icon(Icons.Filled.ContentCopy, "Скопировать") }
            Spacer(Modifier.width(8.dp))
            MorphIconButton(onClick = { LogStore.clear() }, container = cs.errorContainer, content = cs.onErrorContainer) {
                Icon(Icons.Filled.DeleteOutline, "Очистить")
            }
        }
        ConnectedChoice(listOf(false to "События", true to "Подробно (ядро)"), detailed, { detailed = it }, Modifier.padding(bottom = 12.dp))
        Surface(
            Modifier.fillMaxSize(), shape = RoundedCornerShape(24.dp), color = cs.surfaceContainerLowest,
            tonalElevation = 4.dp, border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.24f)),
        ) {
            if (entries.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Terminal, null, Modifier.size(48.dp), tint = cs.outline)
                    Spacer(Modifier.height(12.dp))
                    Text("Журнал пуст", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Повторяющиеся строки схлопываются в одну запись со счётчиком (x2, x3...)",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                }
            } else {
                SelectionContainer {
                    LazyColumn(state = list, contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(if (detailed) 2.dp else 8.dp)) {
                        itemsIndexed(entries) { _, (text, error) ->
                            Text(
                                text,
                                fontFamily = if (detailed) FontFamily.Monospace else null,
                                fontSize = if (detailed) 12.sp else 14.sp,
                                lineHeight = if (detailed) 16.sp else 20.sp,
                                color = if (error) cs.error else cs.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

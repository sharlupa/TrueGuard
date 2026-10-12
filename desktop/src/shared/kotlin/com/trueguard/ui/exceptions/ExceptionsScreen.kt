package com.trueguard.ui.exceptions

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trueguard.core.AppScanner
import com.trueguard.core.DesktopApp
import com.trueguard.core.ExcludedApp
import com.trueguard.core.Settings
import com.trueguard.core.Platform
import com.trueguard.core.TunnelController
import com.trueguard.ui.components.AppTextField
import com.trueguard.ui.components.ConnectedChoice
import com.trueguard.ui.components.PrimaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Вкладка «Исключения» (c.l): программы, которые идут мимо туннеля или только через него. */
@Composable
fun ExceptionsScreen() {
    if (com.trueguard.core.Os.isWindows) return WindowsExceptionsNotice()
    val s = Settings.instance
    val cs = MaterialTheme.colorScheme
    var apps by remember { mutableStateOf<List<DesktopApp>?>(null) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { apps = com.trueguard.core.Platform.hooks.installedApps() }

    val selected = s.excludedApps.map { it.key }.toSet()
    val visible = remember(apps, query, showSystem, selected) {
        val q = query.trim().lowercase()
        (apps ?: emptyList())
            .filter { showSystem || !it.system || it.key in selected }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.key.lowercase().contains(q) }
            .sortedByDescending { it.key in selected }
    }
    // ключи, добавленные вручную или с другой машины, которых нет среди .desktop
    val orphans = s.excludedApps.filter { e -> apps?.none { it.key == e.key } == true }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val wide = maxWidth >= 760.dp
        Column(Modifier.fillMaxSize().widthIn(max = 1500.dp).padding(horizontal = if (wide) 24.dp else 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Исключения", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Surface(shape = RoundedCornerShape(12.dp), color = cs.primaryContainer) {
                    AnimatedContent(selected.size, transitionSpec = { fadeIn() togetherWith fadeOut() }) { n ->
                        Text("$n", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
                    }
                }
            }

            val search: @Composable (Modifier) -> Unit = { m ->
            SearchBarDefaults.InputField(
                query = query, onQueryChange = { query = it }, onSearch = {}, expanded = false, onExpandedChange = {},
                placeholder = { Text("Поиск приложений...") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Очистить") } }
                } else null,
                modifier = m.clip(SearchBarDefaults.inputFieldShape)
                    .background(cs.surfaceContainerHigh),
            )
            }
            val systemSwitch: @Composable (Modifier) -> Unit = { m ->
                Row(m, verticalAlignment = Alignment.CenterVertically) {
                    Text("Показывать системные", Modifier.weight(1f, fill = !wide), style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
                    Spacer(Modifier.width(12.dp))
                    Switch(showSystem, { showSystem = it })
                }
            }
            if (wide) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    search(Modifier.weight(1f))
                    Spacer(Modifier.width(24.dp))
                    systemSwitch(Modifier)
                }
            } else {
                search(Modifier.fillMaxWidth())
                systemSwitch(Modifier.fillMaxWidth().padding(vertical = 12.dp))
            }

            Surface(
                Modifier.fillMaxWidth().padding(bottom = 12.dp), shape = RoundedCornerShape(20.dp), color = cs.surfaceContainerHigh,
                contentColor = cs.onSurface, shadowElevation = 2.dp, border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.24f)),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Режим работы", fontWeight = FontWeight.Bold)
                            Text(
                                if (s.isWhitelist) "Белый список: Отмеченные пускаются в VPN" else "Черный список: Отмеченные исключаются",
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                            )
                        }
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                            tooltip = {
                                PlainTooltip {
                                    Text("Работает только в режиме TUN. ЧС — отмеченные программы ходят напрямую, БС — только они идут через VPN.")
                                }
                            },
                            state = rememberTooltipState(),
                        ) { Icon(Icons.Filled.Info, "Подсказка", Modifier.size(16.dp), tint = cs.onSurfaceVariant) }
                    }
                    Spacer(Modifier.height(10.dp))
                    ConnectedChoice(listOf(false to "ЧС", true to "БС"), s.isWhitelist, { s.isWhitelist = it }, enabled = !TunnelController.phase.active)
                    if (s.tunnelMode != "tun") {
                        Text(
                            "Сейчас выбран режим «Прокси» — исключения применяются только в режиме TUN.",
                            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = cs.error,
                        )
                    }
                }
            }

            if (apps == null) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { LoadingIndicator() }
            } else {
                // на телефоне один столбец, на широком окне — карточки в несколько колонок
                LazyVerticalGrid(
                    GridCells.Adaptive(340.dp), Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(orphans, key = { "o:" + it.key }) { e ->
                        AppRow(DesktopApp(e.key, e.name, e.key, null, false, "вручную: ${e.key}"), true, Modifier.animateItem()) { toggle(e.key, e.name) }
                    }
                    items(visible, key = { it.key }) { app ->
                        AppRow(app, app.key in selected, Modifier.animateItem()) { toggle(app.key, app.name) }
                    }
                    // на Android список пакетов полный — ручное добавление только для программ ПК
                    if (!Platform.isAndroid) item(span = { GridItemSpan(maxLineSpan) }) {
                        PrimaryButton(onClick = { custom = true }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(52.dp)) {
                            Icon(Icons.Filled.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Добавить программу вручную")
                        }
                    }
                }
            }
        }
    }

    if (custom) CustomAppDialog(onDismiss = { custom = false }) { key ->
        if (key.isNotBlank()) toggle(key.trim(), key.trim().substringAfterLast('/'))
        custom = false
    }
}

private fun toggle(key: String, name: String) {
    val s = Settings.instance
    s.excludedApps = if (s.excludedApps.any { it.key == key }) s.excludedApps.filterNot { it.key == key } else s.excludedApps + ExcludedApp(key, name)
}

private val iconCache = ConcurrentHashMap<String, ImageBitmap>()

/** Строка приложения (c.c): выбранная — на secondaryContainer, значок грузится в фоне. */
@Composable
private fun AppRow(app: DesktopApp, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val container by animateColorAsState(if (selected) cs.secondaryContainer else cs.surfaceContainerHigh, spring(stiffness = 200f))
    val icon by produceState(iconCache[app.key], app.key) {
        if (value == null) value = withContext(Dispatchers.IO) {
            com.trueguard.core.Platform.hooks.loadAppIcon(app)
        }?.also { iconCache[app.key] = it }
    }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = container,
        contentColor = if (selected) cs.onSecondaryContainer else cs.onSurface,
        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = if (selected) 0.36f else 0.18f)),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val img = icon
            if (img != null) {
                Image(img, null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
            } else {
                Box(Modifier.size(40.dp).background(cs.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Apps, null, tint = cs.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(app.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    app.comment.ifEmpty { app.key }, style = MaterialTheme.typography.bodySmall,
                    color = if (selected) cs.onSecondaryContainer.copy(alpha = 0.8f) else cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Checkbox(selected, { onClick() })
        }
    }
}

@Composable
private fun CustomAppDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Add, null) },
        title = { Text("Добавить программу") },
        text = {
            Column {
                Text("Имя процесса, полный путь, flatpak:ID или snap:имя.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                AppTextField(key, { key = it }, "Программа", placeholder = "firefox, /usr/bin/steam, flatpak:org.telegram.desktop")
            }
        },
        confirmButton = { PrimaryButton(onClick = { onAdd(key) }, enabled = key.isNotBlank()) { Text("Добавить") } },
        dismissButton = { com.trueguard.ui.components.PlainButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** В Windows раздельная маршрутизация по программам требует драйвера WFP — пока её нет. */
@Composable
private fun WindowsExceptionsNotice() {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(Modifier.widthIn(max = 520.dp), shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerHigh, border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.3f))) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.FilterList, null, Modifier.size(48.dp), tint = cs.primary)
                Spacer(Modifier.height(12.dp))
                Text("Исключения по программам", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "В Windows для этого нужен драйвер фильтрации трафика (WFP) — пока его нет. " +
                        "Чтобы адреса шли мимо VPN, перечислите их в Настройки → Сеть → «Не пускать в туннель».",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

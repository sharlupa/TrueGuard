package com.trueguard.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.trueguard.core.ServerProfile
import com.trueguard.core.Settings
import com.trueguard.core.TunnelController
import com.trueguard.ui.components.AppTextField
import com.trueguard.ui.components.MorphIconButton
import com.trueguard.ui.components.OutlineButton
import com.trueguard.ui.components.PrimaryButton

/** «Список серверов» — нижняя шторка с выбором, правкой и добавлением (w2 / x3). */
@Composable
fun ServerSheet(onDismiss: () -> Unit) {
    val s = Settings.instance
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editing by remember { mutableStateOf<ServerProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var reorder by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Список серверов", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (s.servers.size > 1) {
                    OutlineButton(onClick = { reorder = !reorder }) { Text(if (reorder) "Готово" else "Порядок") }
                }
            }
            if (s.servers.isEmpty()) {
                Text(
                    "Список пуст", Modifier.padding(vertical = 32.dp).align(Alignment.CenterHorizontally),
                    fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 460.dp).padding(bottom = 16.dp)) {
                    items(s.servers, key = { it.id }) { srv ->
                        ServerRow(
                            srv,
                            active = srv.id == s.activeServer?.id,
                            reorder = reorder,
                            modifier = Modifier.animateItem(),
                            onSelect = {
                                if (TunnelController.phase.active) return@ServerRow
                                s.activeServerId = srv.id
                                onDismiss()
                            },
                            onEdit = { editing = srv },
                            onMove = { delta ->
                                val list = s.servers.toMutableList()
                                val i = list.indexOfFirst { it.id == srv.id }
                                val j = (i + delta).coerceIn(0, list.lastIndex)
                                if (i != j) {
                                    list.add(j, list.removeAt(i))
                                    s.servers = list
                                }
                            },
                        )
                    }
                }
            }
            PrimaryButton(
                onClick = { creating = true },
                modifier = Modifier.fillMaxWidth().height(60.dp),
            ) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Добавить сервер", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (creating || editing != null) {
        ServerDialog(
            server = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { srv ->
                s.upsertServer(srv)
                if (s.activeServerId.isEmpty() || creating) s.activeServerId = srv.id
                creating = false
                editing = null
            },
            onDelete = { srv ->
                s.removeServer(srv.id)
                editing = null
            },
        )
    }
}

@Composable
private fun ServerRow(
    srv: ServerProfile,
    active: Boolean,
    reorder: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val container by animateColorAsState(if (active) cs.primaryContainer else cs.surfaceContainerHigh)
    val scale by animateFloatAsState(if (active) 1f else 0.98f, spring(0.5f, 400f))
    Surface(
        onClick = onSelect,
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp).scale(scale),
        shape = RoundedCornerShape(24.dp),
        color = container,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(reorder) {
                Column {
                    IconButton(onClick = { onMove(-1) }, Modifier.size(32.dp)) { Icon(Icons.Filled.KeyboardArrowUp, "Выше", tint = cs.outline) }
                    IconButton(onClick = { onMove(1) }, Modifier.size(32.dp)) { Icon(Icons.Filled.KeyboardArrowDown, "Ниже", tint = cs.outline) }
                }
            }
            if (reorder) Spacer(Modifier.width(12.dp))
            Icon(Icons.Filled.Public, null, tint = if (active) cs.primary else cs.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    srv.name, color = if (active) cs.onPrimaryContainer else cs.onSurface, fontSize = 16.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    srv.ip, style = MaterialTheme.typography.bodyMedium, fontSize = 14.sp,
                    color = if (active) cs.onPrimaryContainer.copy(alpha = 0.8f) else cs.onSurfaceVariant,
                )
                Text(
                    "DTLS ${srv.dtlsPort}  |  WG ${srv.wgPort}", style = MaterialTheme.typography.labelSmall,
                    color = if (active) cs.onPrimaryContainer.copy(alpha = 0.72f) else cs.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Filled.Edit, "Настройки сервера") }
            if (active) Icon(Icons.Filled.CheckCircle, "Выбран", tint = cs.primary)
        }
    }
}

/** «Новый сервер» / «Настройки сервера» (c.b / c3). */
@Composable
fun ServerDialog(server: ServerProfile?, onDismiss: () -> Unit, onSave: (ServerProfile) -> Unit, onDelete: (ServerProfile) -> Unit) {
    var name by remember { mutableStateOf(server?.name ?: "") }
    var ip by remember { mutableStateOf(server?.ip ?: "") }
    var password by remember { mutableStateOf(server?.password ?: "") }
    var dtls by remember { mutableStateOf((server?.dtlsPort ?: 56000).toString()) }
    var wg by remember { mutableStateOf((server?.wgPort ?: 56001).toString()) }
    val ipOk = ip.trim().isNotEmpty()
    val dtlsOk = dtls.toIntOrNull() in 1..65535
    val wgOk = wg.toIntOrNull() in 1..65535

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp).widthIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (server == null) "Новый сервер" else "Настройки сервера", Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    )
                    if (server != null) {
                        MorphIconButton(onClick = { onDelete(server) }, container = MaterialTheme.colorScheme.errorContainer) {
                            Icon(Icons.Filled.DeleteOutline, "Удалить сервер")
                        }
                    }
                }
                AppTextField(name, { name = it }, "Имя (напр. Германия)", icon = Icons.Filled.Label)
                AppTextField(
                    ip, { ip = it.trim() }, "IP адрес", placeholder = "1.2.3.4 (без порта)", icon = Icons.Filled.Dns,
                    isError = !ipOk && ip.isNotEmpty(),
                )
                AppTextField(password, { password = it }, "Пароль от туннеля", icon = Icons.Filled.Key, password = true)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppTextField(
                        dtls, { dtls = it.filter(Char::isDigit).take(5) }, "DTLS", Modifier.weight(1f), isError = !dtlsOk,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    AppTextField(
                        wg, { wg = it.filter(Char::isDigit).take(5) }, "WireGuard", Modifier.weight(1f), isError = !wgOk,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlineButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(56.dp)) { Text("Отмена", fontWeight = FontWeight.Bold) }
                    PrimaryButton(
                        onClick = {
                            val host = ip.trim()
                            onSave(
                                (server ?: ServerProfile(name = host, ip = host)).copy(
                                    name = name.trim().ifEmpty { host }, ip = host, password = password,
                                    dtlsPort = dtls.toInt(), wgPort = wg.toInt(),
                                )
                            )
                        },
                        enabled = ipOk && dtlsOk && wgOk,
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("Сохранить", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

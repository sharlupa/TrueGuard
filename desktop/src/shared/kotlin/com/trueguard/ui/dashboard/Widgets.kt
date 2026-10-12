package com.trueguard.ui.dashboard

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.trueguard.core.DashWidget
import com.trueguard.core.Phase
import com.trueguard.core.Settings
import com.trueguard.core.TunnelController
import com.trueguard.core.formatClock

private val DashWidget.icon: ImageVector
    get() = when (this) {
        DashWidget.SESSION -> Icons.Filled.Timer
        DashWidget.WORKERS -> Icons.Filled.Hub
        DashWidget.TRAFFIC -> Icons.Filled.DataUsage
        DashWidget.HEALTH -> Icons.Filled.HealthAndSafety
        DashWidget.GRAPH -> Icons.Filled.QueryStats
    }

/** Значение виджета — те же строки, что в APK (b5/i3). */
private fun widgetValue(w: DashWidget, connected: Boolean, session: String): String {
    val t = TunnelController
    val s = Settings.instance
    return when (w) {
        DashWidget.SESSION -> session
        DashWidget.WORKERS -> "${t.activeWorkers}"
        DashWidget.TRAFFIC -> {
            val total = t.bytesUp + t.bytesDown
            val mb = total / 1048576f
            when {
                total <= 0 -> "0 MB"
                mb >= 1024 -> "%.1f GB".format(mb / 1024f)
                else -> "%.1f MB".format(mb)
            }
        }
        DashWidget.HEALTH -> when {
            connected && t.activeWorkers > 0 -> "OK"
            connected -> "Warmup"
            s.activeServer == null || s.allHashes.isEmpty() -> "Setup"
            else -> "Idle"
        }
        DashWidget.GRAPH -> ""
    }
}

/**
 * Сетка виджетов: в режиме редактирования карточки дрожат, их можно перетащить
 * на новое место или убрать в «Доступные виджеты».
 */
@Composable
fun WidgetsArea(editMode: Boolean, connected: Boolean, jiggle: Jiggle) {
    val s = Settings.instance
    val order = remember { mutableStateListOf<DashWidget>() }
    LaunchedEffect(s.dashWidgets) {
        val fromSettings = s.dashWidgets.mapNotNull { n -> DashWidget.entries.firstOrNull { it.name == n } }
        if (fromSettings != order.toList()) {
            order.clear()
            order.addAll(fromSettings)
        }
    }
    val hidden = s.hiddenWidgets.mapNotNull { n -> DashWidget.entries.firstOrNull { it.name == n } }

    fun persist() {
        s.dashWidgets = order.map { it.name }
    }

    // таймер сессии обновляется раз в секунду
    val clock by produceState(0L, connected) {
        while (connected) {
            kotlinx.coroutines.delay(1000)
            value++
        }
    }
    val session = if (connected && clock >= 0) formatClock(TunnelController.sessionSeconds) else "00:00"

    var diagnostics by remember { mutableStateOf(false) }
    if (diagnostics) DiagnosticsDialog(onDismiss = { diagnostics = false })

    val grid = rememberLazyGridState()
    var dragged by remember { mutableStateOf<DashWidget?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }

    LazyVerticalGrid(
        // на телефоне два столбца, как в APK; на широком окне — сколько поместится
        columns = GridCells.Adaptive(170.dp),
        state = grid,
        modifier = Modifier.fillMaxWidth().heightIn(max = 1000.dp)
            .pointerInput(editMode) {
                if (!editMode) return@pointerInput
                detectDragGestures(
                    onDragStart = { pos ->
                        val item = grid.layoutInfo.visibleItemsInfo.firstOrNull {
                            pos.x >= it.offset.x && pos.x < it.offset.x + it.size.width && pos.y >= it.offset.y && pos.y < it.offset.y + it.size.height
                        } ?: return@detectDragGestures
                        dragged = item.key as? DashWidget
                        grab = pos - Offset(item.offset.x.toFloat(), item.offset.y.toFloat())
                        pointer = pos
                    },
                    onDrag = { change, delta ->
                        val d = dragged ?: return@detectDragGestures
                        change.consume()
                        pointer += delta
                        val target = grid.layoutInfo.visibleItemsInfo.firstOrNull {
                            it.key != d && pointer.x >= it.offset.x && pointer.x < it.offset.x + it.size.width &&
                                pointer.y >= it.offset.y && pointer.y < it.offset.y + it.size.height
                        }
                        if (target != null) {
                            val from = order.indexOf(d)
                            val to = order.indexOf(target.key as DashWidget)
                            if (from >= 0 && to >= 0) order.add(to, order.removeAt(from))
                        }
                    },
                    onDragEnd = { dragged = null; persist() },
                    onDragCancel = { dragged = null; persist() },
                )
            },
        userScrollEnabled = false,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(12.dp),
    ) {
        items(order, key = { it }, span = { GridItemSpan(if (it.wide) maxLineSpan else 1) }) { w ->
            val isDragged = dragged == w
            val itemModifier = if (isDragged) {
                Modifier.zIndex(10f).graphicsLayer {
                    val info = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == w }
                    if (info != null) {
                        translationX = pointer.x - grab.x - info.offset.x
                        translationY = pointer.y - grab.y - info.offset.y
                    }
                    scaleX = 1.04f
                    scaleY = 1.04f
                    shadowElevation = 12f
                }
            } else {
                val i = order.indexOf(w)
                Modifier.animateItem().graphicsLayer {
                    if (editMode) {
                        rotationZ = if (i % 2 == 0) jiggle.rotation.value else -jiggle.rotation.value
                        translationX = if (i % 3 == 0) jiggle.tx.value else -jiggle.tx.value
                        translationY = if (i % 2 != 0) jiggle.ty.value else -jiggle.ty.value
                    }
                }
            }
            Box(itemModifier) {
                if (w == DashWidget.GRAPH) NetworkChart(connected, Modifier.height(160.dp))
                else WidgetCard(
                    w.title, w.icon, Modifier.height(130.dp),
                    onClick = if (w == DashWidget.HEALTH && !editMode) ({ diagnostics = true }) else null,
                ) {
                    AnimatedContent(widgetValue(w, connected, session), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "") { v ->
                        Text(v, style = MaterialTheme.typography.titleLarge, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    editMode, Modifier.align(Alignment.TopEnd).padding(12.dp),
                    enter = scaleIn(spring(stiffness = 400f)), exit = scaleOut(spring(stiffness = 400f)),
                ) {
                    Surface(
                        onClick = {
                            order.remove(w)
                            persist()
                            s.hiddenWidgets = s.hiddenWidgets + w.name
                        },
                        modifier = Modifier.size(32.dp), shape = CircleShape, color = MaterialTheme.colorScheme.errorContainer,
                    ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Remove, "Скрыть виджет", Modifier.size(18.dp)) } }
                }
            }
        }
    }

    AnimatedVisibility(
        editMode && hidden.isNotEmpty(),
        enter = expandVertically(spring(stiffness = 400f)) + fadeIn(),
        exit = shrinkVertically(spring(stiffness = 400f)) + fadeOut(),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(
                "Доступные виджеты", Modifier.padding(start = 12.dp, bottom = 12.dp),
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            )
            hidden.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { w ->
                        Box(Modifier.weight(1f)) {
                            Surface(
                                onClick = {
                                    s.hiddenWidgets = s.hiddenWidgets - w.name
                                    order.add(w)
                                    persist()
                                },
                                shape = RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                            ) {
                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(w.icon, null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(10.dp))
                                    Text(w.title, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Icon(Icons.Filled.Add, "Добавить", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Карточка виджета (c.h). */
@Composable
private fun WidgetCard(title: String, icon: ImageVector, modifier: Modifier, onClick: (() -> Unit)? = null, value: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick ?: {}, enabled = onClick != null,
        modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = cs.surfaceContainerHigh, contentColor = cs.onSurface,
        shadowElevation = 2.dp, border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.24f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).background(cs.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(16.dp), tint = cs.onPrimaryContainer)
                }
                Spacer(Modifier.width(8.dp))
                Text(title, color = cs.onSurfaceVariant, fontSize = 14.sp, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
            Spacer(Modifier.height(16.dp))
            value()
        }
    }
}

/** «Трафик сети»: сглаженная линия последних 30 замеров скорости (c.R). */
@Composable
private fun NetworkChart(connected: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val t = TunnelController
    val points = remember(t.speed) {
        val last = t.speed.takeLast(30).map { (it.up + it.down).toFloat() }
        List(30 - last.size) { 0f } + last
    }
    val max = (points.maxOrNull() ?: 0f).coerceAtLeast(1f)
    val live = connected && t.phase == Phase.Connected && (t.upSpeed + t.downSpeed) > 1024
    val dot by animateColorAsState(if (live) cs.primary else cs.outline)
    Surface(
        modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), color = cs.surfaceContainerHigh, contentColor = cs.onSurface,
        shadowElevation = 2.dp, border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.26f)),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.QueryStats, null, Modifier.size(20.dp), tint = cs.primary)
                Spacer(Modifier.width(8.dp))
                Text("Трафик сети", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (live) {
                    Text(
                        "↓ ${com.trueguard.core.formatSpeed(t.downSpeed)}  ↑ ${com.trueguard.core.formatSpeed(t.upSpeed)}",
                        Modifier.padding(end = 10.dp), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
                    )
                }
                Box(Modifier.size(10.dp).background(dot, CircleShape))
            }
            Spacer(Modifier.height(16.dp))
            val line = cs.primary
            Canvas(Modifier.fillMaxSize()) {
                val step = size.width / (points.size - 1)
                fun y(v: Float) = size.height - size.height * (v / max)
                val path = Path()
                points.forEachIndexed { i, v ->
                    val x = i * step
                    if (i == 0) path.moveTo(x, y(v)) else {
                        val px = (i - 1) * step + step / 2
                        path.cubicTo(px, y(points[i - 1]), px, y(v), x, y(v))
                    }
                }
                drawPath(path, line, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val fill = Path().apply {
                    addPath(path)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(fill, line.copy(alpha = 0.1f))
            }
        }
    }
}

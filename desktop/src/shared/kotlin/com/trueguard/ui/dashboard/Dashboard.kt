package com.trueguard.ui.dashboard

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trueguard.core.Phase
import com.trueguard.core.Platform
import com.trueguard.core.Settings
import com.trueguard.core.TunnelController
import com.trueguard.ui.LocalOpenTab
import com.trueguard.ui.LocalSnackbar
import com.trueguard.ui.components.MorphIconButton
import com.trueguard.ui.components.PlainButton

/** Общая «дрожь» карточек в режиме редактирования (как на домашнем экране Android). */
class Jiggle(val rotation: State<Float>, val tx: State<Float>, val ty: State<Float>)

@Composable
fun rememberJiggle(): Jiggle {
    val t = rememberInfiniteTransition(label = "jiggle")
    fun spec(ms: Int) = infiniteRepeatable<Float>(tween(ms, easing = LinearEasing), RepeatMode.Reverse)
    return Jiggle(
        t.animateFloat(-1.5f, 1.5f, spec(120), label = "rotation"),
        t.animateFloat(-1.5f, 1.5f, spec(130), label = "tx"),
        t.animateFloat(-1.5f, 1.5f, spec(110), label = "ty"),
    )
}

/** Главная: карточка сервера, протокол, кнопка подключения, статус и виджеты (c.H). */
@Composable
fun Dashboard() {
    val s = Settings.instance
    val t = TunnelController
    val cs = MaterialTheme.colorScheme
    var editMode by rememberSaveable { mutableStateOf(false) }
    var showServers by remember { mutableStateOf(false) }
    val jiggle = rememberJiggle()
    val snackbar = LocalSnackbar.current
    val openTab = LocalOpenTab.current

    val connected = t.phase == Phase.Connected || (t.phase == Phase.WaitingWorkers && t.tunnelUp)
    val connecting = t.phase.active && !connected

    // ошибки запуска показываем снэкбаром, как Toast в Android
    LaunchedEffect(t.lastError) {
        val e = t.lastError ?: return@LaunchedEffect
        val r = snackbar.showSnackbar(e, actionLabel = "Логи", withDismissAction = true, duration = SnackbarDuration.Long)
        if (r == SnackbarResult.ActionPerformed) openTab(3)
    }

    val editButton: @Composable () -> Unit = {
        MorphIconButton(
            onClick = { editMode = !editMode },
            container = if (editMode) cs.primary else cs.surfaceContainerHighest,
            content = if (editMode) cs.onPrimary else cs.onSurfaceVariant,
        ) {
            AnimatedContent(editMode, transitionSpec = { scaleIn() + fadeIn() togetherWith scaleOut() + fadeOut() }) { e ->
                Icon(if (e) Icons.Filled.Check else Icons.Filled.Edit, if (e) "Готово" else "Настроить виджеты")
            }
        }
    }

    // главная часть экрана: сервер, протокол, кнопка и статус
    val controls: @Composable ColumnScope.() -> Unit = {
        AnimatedVisibility(t.protectionLost && !t.phase.active) {
            LostProtectionBanner(onReconnect = { t.connect() }, onDismiss = { t.protectionLost = false })
        }

        ServerCard(
            connected = connected,
            editMode = editMode,
            jiggle = jiggle,
            onClick = { if (!editMode) showServers = true },
        )

        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 32.dp), horizontalArrangement = Arrangement.Center) {
            ProtocolChoice(s.protocol, enabled = !t.phase.active) { s.protocol = it }
        }

        ConnectButton(connected = connected, connecting = connecting, enabled = !editMode) { t.toggle() }

        AnimatedContent(
            targetState = statusText(t.phase, connected, t.activeWorkers),
            transitionSpec = { (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut()) },
            label = "statusText",
        ) { text ->
            Text(
                text, Modifier.padding(top = 16.dp, bottom = 24.dp),
                color = if (connected) cs.primary else cs.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
        }

        AnimatedVisibility(t.captchaPending) {
            AssistChip(
                onClick = {},
                label = { Text(if (Platform.isAndroid) "Решите капчу в окне проверки" else "Решите капчу в открывшемся окне браузера") },
                leadingIcon = { Icon(Icons.Filled.Shield, null, Modifier.size(AssistChipDefaults.IconSize)) },
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        if (maxWidth >= 860.dp) {
            // широкое окно: слева управление, справа виджеты — без прокрутки всего экрана
            Row(Modifier.fillMaxSize().widthIn(max = 1500.dp).padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 10.dp)
                        .animateContentSize(spring(stiffness = 200f)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Дашборд", Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold,
                    )
                    Box(Modifier.widthIn(max = 560.dp)) { Column(horizontalAlignment = Alignment.CenterHorizontally) { controls() } }
                }
                Column(Modifier.weight(1.15f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Виджеты", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        editButton()
                    }
                    WidgetsArea(editMode = editMode, connected = connected, jiggle = jiggle)
                    Spacer(Modifier.height(32.dp))
                }
            }
        } else {
            Column(
                Modifier.fillMaxHeight().widthIn(max = com.trueguard.ui.ContentMaxWidth).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .animateContentSize(spring(stiffness = 200f)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Дашборд", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                    editButton()
                }
                controls()
                WidgetsArea(editMode = editMode, connected = connected, jiggle = jiggle)
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (showServers) ServerSheet(onDismiss = { showServers = false })
}

private fun statusText(phase: Phase, connected: Boolean, active: Int): String = when {
    connected && active == 0 -> "Ожидание воркеров..."
    connected -> "Подключено"
    else -> phase.title
}

/** «Защита отключена!» — туннель упал без участия пользователя. */
@Composable
private fun LostProtectionBanner(onReconnect: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth().padding(bottom = 16.dp), shape = RoundedCornerShape(24.dp),
        color = cs.errorContainer, contentColor = cs.onErrorContainer,
    ) {
        Row(Modifier.padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.GppBad, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Защита отключена!", fontWeight = FontWeight.Bold)
                Text(TunnelController.lastError ?: "Соединение с туннелем было разорвано.", style = MaterialTheme.typography.bodySmall)
            }
            PlainButton(onClick = onReconnect, colors = ButtonDefaults.textButtonColors(contentColor = cs.onErrorContainer)) { Text("Подключить") }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Скрыть") }
        }
    }
}

/** Карточка «Текущий сервер» (f1 case 3). */
@Composable
private fun ServerCard(connected: Boolean, editMode: Boolean, jiggle: Jiggle, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val server = Settings.instance.activeServer
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(0.6f, 400f))
    val container by animateColorAsState(if (connected) cs.primaryContainer else cs.surfaceContainerHighest, tween(500))
    val content by animateColorAsState(if (connected) cs.onPrimaryContainer else cs.onSurface, tween(500))
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().scale(scale).graphicsLayer {
            if (editMode) {
                rotationZ = jiggle.rotation.value * 0.4f
                translationX = jiggle.tx.value
                translationY = jiggle.ty.value
            }
        },
        shape = RoundedCornerShape(30.dp),
        color = container,
        contentColor = content,
        tonalElevation = if (connected) 5.dp else 2.dp,
        shadowElevation = if (connected) 10.dp else 3.dp,
        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.32f)),
        interactionSource = src,
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(54.dp), shape = CircleShape, color = if (connected) cs.primary else cs.primaryContainer) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Dns, null, tint = if (connected) cs.onPrimary else cs.onPrimaryContainer)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Текущий сервер", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Text(
                    server?.name ?: "Не выбран", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Filled.UnfoldMore, "Выбрать сервер", tint = cs.outline)
        }
    }
}

/** UDP / TCP: связанная группа MD3 Expressive вместо пары FilterChip. */
@Composable
private fun ProtocolChoice(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        listOf("udp" to "UDP", "tcp" to "TCP").forEachIndexed { i, (id, label) ->
            ToggleButton(
                checked = value == id,
                onCheckedChange = { onChange(id) },
                enabled = enabled,
                modifier = Modifier.width(104.dp),
                shapes = if (i == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
            ) {
                AnimatedVisibility(value == id) {
                    Row {
                        Icon(Icons.Filled.Check, null, Modifier.size(ToggleButtonDefaults.IconSize))
                        Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
                    }
                }
                Text(label, fontWeight = FontWeight.Bold)
            }
        }
    }
}

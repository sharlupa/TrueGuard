package com.trueguard.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Заголовок вложенной страницы настроек: круглая «назад», заголовок, подзаголовок и значок раздела (c.F). */
@Composable
fun PageHeader(title: String, onBack: (() -> Unit)?, subtitle: String? = null, icon: ImageVector? = null) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            MorphIconButton(onClick = onBack, container = cs.surfaceContainerHigh) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
        if (icon != null) {
            Surface(Modifier.size(44.dp), shape = RoundedCornerShape(16.dp), color = cs.surfaceContainerHigh) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp), tint = cs.primary) }
            }
        }
    }
}

/** Крупная карточка-вступление раздела (c.G): значок, заголовок, текст и пары «метка — значение». */
@Composable
fun HeroCard(icon: ImageVector, title: String, text: String, chips: List<Pair<String, String>> = emptyList()) {
    val cs = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = cs.primaryContainer, contentColor = cs.onPrimaryContainer,
        tonalElevation = 2.dp, border = BorderStroke(1.dp, cs.primary.copy(alpha = 0.18f)),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(18.dp), color = cs.primary.copy(alpha = 0.14f)) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(24.dp), tint = cs.onPrimaryContainer) }
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = cs.onPrimaryContainer)
                    Text(text, style = MaterialTheme.typography.bodyMedium, lineHeight = 20.sp, color = cs.onPrimaryContainer.copy(alpha = 0.78f))
                }
            }
            if (chips.isNotEmpty()) {
                chips.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (k, v) -> InfoChip(k, v, Modifier.weight(1f), onSurface = true) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Плашка «метка — значение» (c.T). */
@Composable
fun InfoChip(label: String, value: String, modifier: Modifier = Modifier, onSurface: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier.heightIn(min = 44.dp), shape = RoundedCornerShape(16.dp),
        color = if (onSurface) cs.surface.copy(alpha = 0.56f) else cs.secondaryContainer.copy(alpha = 0.72f),
        contentColor = if (onSurface) cs.onSurface else cs.onSecondaryContainer,
        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.28f)),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Карточка раздела настроек (c.d): квадратный значок, заголовок и содержимое. */
@Composable
fun SectionCard(title: String, icon: ImageVector, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(26.dp), color = cs.surfaceContainerHigh,
        contentColor = cs.onSurface, tonalElevation = 2.dp, border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.28f)),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(42.dp), shape = RoundedCornerShape(16.dp), color = cs.primaryContainer) {
                    Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(21.dp), tint = cs.onPrimaryContainer) }
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = cs.onSurface)
            }
            content()
        }
    }
}

/** Вторая разновидность карточки (c.e): круглый значок, заголовок цветом primary, лёгкая тень. */
@Composable
fun AccentCard(title: String, icon: ImageVector, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), color = cs.surfaceContainerHigh, contentColor = cs.onSurface,
        tonalElevation = 2.dp, shadowElevation = 3.dp, border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.26f)),
    ) {
        Column(Modifier.padding(22.dp)) {
            Row(Modifier.padding(bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(38.dp), shape = CircleShape, color = cs.primaryContainer) {
                    Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), tint = cs.onPrimaryContainer) }
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = cs.primary)
            }
            content()
        }
    }
}

/** Круг 40dp со значком 20dp, как у всех строк настроек. */
@Composable
fun TileIcon(icon: ImageVector) {
    Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(20.dp))
    }
}

/** Строка-переход (c.B): значок, заголовок, подпись; нажатие — с пружинным сжатием. */
@Composable
fun NavTile(icon: ImageVector, title: String, subtitle: String, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.97f else 1f, MaterialTheme.motionScheme.fastSpatialSpec())
    Row(
        Modifier.fillMaxWidth().scale(scale).clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = src, indication = androidx.compose.material3.ripple(), onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TileIcon(icon)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

/** Строка с переключателем (c.C / c.D). */
@Composable
fun SwitchTile(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f).clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TileIcon(icon)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            thumbContent = if (checked) {
                { Icon(Icons.Filled.Check, null, Modifier.size(SwitchDefaults.IconSize)) }
            } else null,
        )
    }
}

/** Поле ввода (c.E): OutlinedTextField со скруглением 18dp. */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    icon: ImageVector? = null,
    supporting: String? = null,
    isError: Boolean = false,
    password: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailing: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
) {
    var hidden by remember { mutableStateOf(true) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        leadingIcon = icon?.let { { Icon(it, null) } },
        trailingIcon = when {
            password -> {
                {
                    IconButton(onClick = { hidden = !hidden }) {
                        Icon(if (hidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, if (hidden) "Показать" else "Скрыть")
                    }
                }
            }
            else -> trailing
        },
        supportingText = supporting?.let { { Text(it) } },
        isError = isError,
        visualTransformation = if (password && hidden) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = keyboardOptions,
        singleLine = singleLine,
        minLines = minLines,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
    )
}

/** Подпись-группа внутри карточки: «Режим темы», «Палитра» и т.п. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Анимированный цвет контейнера по выбору (spring 200, как в APK). */
@Composable
fun selectedColor(selected: Boolean, on: androidx.compose.ui.graphics.Color, off: androidx.compose.ui.graphics.Color) =
    animateColorAsState(if (selected) on else off, spring(stiffness = 200f)).value

/**
 * Горизонтальный ряд, удобный мышью: обычное колесо (без Shift) листает ряд, пока есть куда,
 * а на краю отдаёт прокрутку странице; ряд можно перетаскивать мышью.
 */
fun Modifier.mouseHorizontalScroll(state: androidx.compose.foundation.ScrollState): Modifier = composed {
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val step = with(density) { 64.dp.toPx() }
    this
        .pointerInput(state) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    if (e.type != androidx.compose.ui.input.pointer.PointerEventType.Scroll) continue
                    val d = e.changes.firstOrNull()?.scrollDelta ?: continue
                    val delta = if (d.x != 0f) d.x else d.y
                    val can = (delta > 0 && state.canScrollForward) || (delta < 0 && state.canScrollBackward)
                    if (delta != 0f && can) {
                        e.changes.forEach { it.consume() }
                        scope.launch { state.animateScrollBy(delta * step) }
                    }
                }
            }
        }
        .draggable(
            state = androidx.compose.foundation.gestures.rememberDraggableState { state.dispatchRawDelta(-it) },
            orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
        )
        .horizontalScroll(state)
}

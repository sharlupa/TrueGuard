package com.trueguard.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trueguard.core.Settings
import com.trueguard.core.Platform
import com.trueguard.ui.components.*
import com.trueguard.ui.theme.Palettes
import com.trueguard.ui.theme.SystemAppearance
import com.trueguard.ui.theme.hsl
import com.trueguard.ui.theme.toHsl

@Composable
fun InterfacePage(back: (() -> Unit)?) {
    val s = Settings.instance
    SettingsPage {
        PageHeader("Интерфейс", back, "Тема, палитра и цветовая адаптация", Icons.Filled.Palette)
        val theme = when (s.themeMode) {
            "light" -> "Светлая"
            "dark" -> "Темная"
            "amoled" -> "Amoled"
            else -> "Авто"
        }
        HeroCard(
            Icons.Filled.AutoAwesome, "MD3 Expressive",
            "Без градиентов: чистые контейнеры, мягкий контраст и читаемые заголовки.",
            listOf("Тема" to theme, "Палитра" to (if (s.dynamicColor) "система" else Palettes.firstOrNull { it.id == s.themePalette }?.name ?: "Свой")),
        )
        SectionCard("Внешний вид", Icons.Filled.Palette) {
            GroupLabel("Режим темы")
            Spacer(Modifier.height(10.dp))
            ConnectedChoice(
                listOf("system" to "Авто", "light" to "Светлая", "dark" to "Темная", "amoled" to "Amoled"),
                s.themeMode, { s.themeMode = it },
            )
            Spacer(Modifier.height(16.dp))
            val hasAccent = com.trueguard.core.Platform.hooks.dynamicColorScheme(false) != null
            SwitchTile(
                Icons.Filled.ColorLens, "Динамические цвета",
                when {
                    com.trueguard.core.Platform.isAndroid -> if (hasAccent) "Цвета из обоев системы" else "Нужен Android 12 или новее"
                    !hasAccent -> "Акцентный цвет рабочего стола не найден"
                    com.trueguard.core.Os.isWindows -> "Цвета из акцентного цвета Windows"
                    else -> "Цвета из акцента рабочего стола"
                },
                s.dynamicColor && hasAccent, { s.dynamicColor = it }, enabled = hasAccent,
            )
            AnimatedVisibility(!(s.dynamicColor && hasAccent)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider(Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    GroupLabel("Палитра")
                    PaletteChooser()
                }
            }
        }
        // на телефоне масштаб задаёт система (размер шрифта и экрана)
        if (!Platform.isAndroid) SectionCard("Масштаб окна", Icons.Filled.ZoomIn) {
            ConnectedChoice(
                listOf(90 to "90%", 100 to "100%", 110 to "110%", 125 to "125%", 150 to "150%"),
                s.uiScale, { s.uiScale = it },
            )
            Spacer(Modifier.height(8.dp))
            Hint("Размер всего интерфейса. Раскладка сама подстраивается под ширину окна: узкое — как на телефоне, широкое — боковая панель и две колонки.")
        }
    }
}

/** Ряд образцов палитр + «Свой» с выбором цвета (c.f). */
@Composable
private fun PaletteChooser() {
    val s = Settings.instance
    var picker by remember { mutableStateOf(false) }
    // ряд, как на телефоне; колесо без Shift и перетаскивание мышью
    Row(Modifier.fillMaxWidth().mouseHorizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Palettes.forEach { p ->
            PaletteSwatch(p.name, p.seed, s.themePalette == p.id) { s.themePalette = p.id }
        }
        PaletteSwatch("Свой", Color(s.themeSeedColor), s.themePalette == "custom", custom = true) {
            if (s.themePalette == "custom") picker = true
            s.themePalette = "custom"
        }
    }
    AnimatedVisibility(s.themePalette == "custom") {
        OutlineButton(onClick = { picker = true }, modifier = Modifier.padding(top = 4.dp)) {
            Icon(Icons.Filled.Colorize, null)
            Spacer(Modifier.width(8.dp))
            Text("Изменить свой цвет")
        }
    }
    if (picker) {
        ColorPickerDialog(Color(s.themeSeedColor), onDismiss = { picker = false }) {
            s.themeSeedColor = it.toArgb()
            s.themePalette = "custom"
            picker = false
        }
    }
}

/** Образец палитры (c.w): 76dp, кружок цвета и подпись; у «Своего» — радужное кольцо. */
@Composable
private fun PaletteSwatch(name: String, color: Color, selected: Boolean, custom: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val corner by androidx.compose.animation.core.animateDpAsState(if (selected) 26.dp else 18.dp, MaterialTheme.motionScheme.defaultSpatialSpec())
    Surface(
        onClick = onClick,
        modifier = Modifier.width(76.dp),
        shape = RoundedCornerShape(corner),
        color = if (selected) cs.primaryContainer else cs.surfaceContainer,
        contentColor = if (selected) cs.onPrimaryContainer else cs.onSurface,
        border = if (selected) BorderStroke(2.dp, cs.primary) else BorderStroke(1.dp, cs.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (custom) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape)
                        .background(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
                        .padding(4.dp).clip(CircleShape).background(color)
                )
            } else {
                Box(Modifier.size(28.dp).clip(CircleShape).background(color))
            }
            Text(name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** «Свой цвет темы» (c.g): оттенок, насыщенность и светлота с живым образцом. */
@Composable
fun ColorPickerDialog(initial: Color, onDismiss: () -> Unit, onPick: (Color) -> Unit) {
    val start = remember { initial.toHsl() }
    var h by remember { mutableStateOf(start[0]) }
    var sat by remember { mutableStateOf(start[1].coerceIn(0.1f, 1f)) }
    var l by remember { mutableStateOf(start[2].coerceIn(0.2f, 0.8f)) }
    val color = hsl(h, sat, l)
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { PrimaryButton(onClick = { onPick(color) }) { Text("Применить") } },
        dismissButton = { PlainButton(onClick = onDismiss) { Text("Отмена") } },
        icon = { Icon(Icons.Filled.Colorize, null) },
        title = { Text("Свой цвет темы") },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).background(color))
                Spacer(Modifier.height(20.dp))
                Text("Оттенок", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Box(
                    Modifier.fillMaxWidth().padding(top = 6.dp).height(28.dp).clip(RoundedCornerShape(14.dp))
                        .background(Brush.horizontalGradient(List(13) { hsl(it * 30f, sat, l) }))
                )
                Slider(h, { h = it }, valueRange = 0f..360f)
                Spacer(Modifier.height(4.dp))
                Text("Насыщенность", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Slider(sat, { sat = it }, valueRange = 0.1f..1f)
                Spacer(Modifier.height(4.dp))
                Text("Светлота", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Slider(l, { l = it }, valueRange = 0.2f..0.8f)
            }
        },
    )
}

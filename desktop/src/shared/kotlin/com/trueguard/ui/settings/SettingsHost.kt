package com.trueguard.ui.settings

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trueguard.core.ConfigLink
import com.trueguard.core.ImportException
import com.trueguard.core.Settings
import com.trueguard.core.UPSTREAM_VERSION
import com.trueguard.ui.LocalSnackbar
import com.trueguard.ui.components.*
import kotlinx.coroutines.launch

enum class SettingsRoute { Main, Network, Performance, Interface, System, About, Help }

/**
 * Навигация внутри вкладки «Настройки» (c.p, «settings_navigation»).
 * Узкое окно — стопка страниц, как в APK; широкое — список слева и раздел справа.
 */
@Composable
fun SettingsHost(start: SettingsRoute = SettingsRoute.Main) {
    var stack by remember { mutableStateOf(listOf(start)) }
    val route = stack.last()
    val open: (SettingsRoute) -> Unit = { stack = stack + it }
    val back: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }
    val motion = MaterialTheme.motionScheme

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        if (maxWidth >= 900.dp) {
            val detail = stack.lastOrNull { it != SettingsRoute.Main } ?: SettingsRoute.Network
            Row(Modifier.fillMaxSize().widthIn(max = 1500.dp)) {
                Box(Modifier.width(440.dp).fillMaxHeight()) {
                    SettingsMain(open = { stack = listOf(SettingsRoute.Main, it) }, selected = detail)
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    AnimatedContent(
                        targetState = detail,
                        transitionSpec = {
                            (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.96f))
                                .togetherWith(fadeOut(motion.fastEffectsSpec()))
                        },
                        label = "settingsDetail",
                    ) { r ->
                        Box(Modifier.widthIn(max = 760.dp)) {
                            SettingsPageFor(r, back = if (r == SettingsRoute.Help) ({ stack = listOf(SettingsRoute.Main, SettingsRoute.About) }) else null) {
                                stack = listOf(SettingsRoute.Main, it)
                            }
                        }
                    }
                }
            }
        } else {
            AnimatedContent(
                targetState = route,
                modifier = Modifier.widthIn(max = com.trueguard.ui.ContentMaxWidth),
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal || initialState == SettingsRoute.Main
                    val dir = if (forward && targetState != SettingsRoute.Main) 1 else -1
                    (slideInHorizontally(motion.defaultSpatialSpec()) { it / 4 * dir } + fadeIn(motion.defaultEffectsSpec()))
                        .togetherWith(slideOutHorizontally(motion.defaultSpatialSpec()) { -it / 4 * dir } + fadeOut(motion.fastEffectsSpec()))
                },
                label = "settings",
            ) { r ->
                if (r == SettingsRoute.Main) SettingsMain(open) else SettingsPageFor(r, back, open)
            }
        }
    }
}

@Composable
private fun SettingsPageFor(r: SettingsRoute, back: (() -> Unit)?, open: (SettingsRoute) -> Unit) {
    when (r) {
        SettingsRoute.Main, SettingsRoute.Network -> NetworkPage(back)
        SettingsRoute.Performance -> PerformancePage(back)
        SettingsRoute.Interface -> InterfacePage(back)
        SettingsRoute.System -> SystemPage(back)
        SettingsRoute.About -> AboutPage(back, open)
        SettingsRoute.Help -> HelpPage(back)
    }
}

/** Общий каркас страницы настроек: прокрутка, отступ 16, промежуток 16. */
@Composable
fun SettingsPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        content()
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SettingsMain(open: (SettingsRoute) -> Unit, selected: SettingsRoute? = null) {
    val s = Settings.instance
    var exportOpen by remember { mutableStateOf(false) }
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    SettingsPage {
        HeroCard(
            Icons.Filled.Tune, "Настройки TrueGuard",
            "Сеть, ключи, капча и внешний вид собраны в короткие понятные разделы.",
            listOf("Сервер" to (s.activeServer?.name ?: "не выбран"), "Версия" to UPSTREAM_VERSION),
        )
        SettingsNavCard(Icons.Filled.Language, "Сеть", "Локальный порт, MTU, DNS и SNI домен", selected = selected == SettingsRoute.Network) { open(SettingsRoute.Network) }
        SettingsNavCard(Icons.Filled.Speed, "Производительность", "VK-хеши, потоки и режим капчи", selected = selected == SettingsRoute.Performance) { open(SettingsRoute.Performance) }
        SettingsNavCard(Icons.Filled.Palette, "Интерфейс", "Тема, палитра и динамические цвета", selected = selected == SettingsRoute.Interface) { open(SettingsRoute.Interface) }
        if (!com.trueguard.core.Platform.isAndroid) SettingsNavCard(Icons.Filled.Computer, "Система", "Режим туннеля, права root, автозапуск и трей", selected = selected == SettingsRoute.System) { open(SettingsRoute.System) }
        SectionCard("Синхронизация", Icons.Filled.Share) {
            Text(
                "Умный импорт понимает старые ссылки, новые пакеты настроек и сырой JSON. Экспорт можно сделать целиком или только нужной частью.",
                Modifier.padding(bottom = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(
                    onClick = {
                        val msg = try {
                            ConfigLink.import(s, Clipboard.paste() ?: "")
                        } catch (e: ImportException) {
                            e.message ?: "Ошибка импорта"
                        } catch (e: Exception) {
                            "Конфиг не найден или повреждён"
                        }
                        scope.launch { snackbar.showSnackbar(msg) }
                    },
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Icon(Icons.Filled.ContentPasteGo, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Умный импорт", fontSize = 15.sp)
                }
                TonalButton(onClick = { exportOpen = true }, modifier = Modifier.weight(1f).height(56.dp)) {
                    Icon(Icons.Filled.IosShare, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Экспорт", fontSize = 16.sp)
                }
            }
        }
        SettingsNavCard(Icons.Filled.Info, "О приложении", "Версия, Обновления, GitHub", selected = selected == SettingsRoute.About) { open(SettingsRoute.About) }
    }
    if (exportOpen) ExportDialog(onDismiss = { exportOpen = false })
}

/**
 * Карточка-переход корня настроек (c.u). Нажатие морфит скругление 24 → 36dp,
 * значок в квадрате 50dp на secondaryContainer.
 */
@Composable
fun SettingsNavCard(icon: ImageVector, title: String, subtitle: String, selected: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val corner by androidx.compose.animation.core.animateDpAsState(if (pressed) 36.dp else 24.dp, MaterialTheme.motionScheme.fastSpatialSpec())
    val iconCorner by androidx.compose.animation.core.animateDpAsState(if (pressed) 25.dp else 18.dp, MaterialTheme.motionScheme.fastSpatialSpec())
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (selected) 36.dp else corner),
        color = if (selected) cs.secondaryContainer else cs.surfaceContainerHigh,
        contentColor = if (selected) cs.onSecondaryContainer else cs.onSurface,
        tonalElevation = 1.dp,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) cs.primary else cs.outlineVariant.copy(alpha = 0.3f)),
        interactionSource = src,
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(50.dp), shape = RoundedCornerShape(iconCorner), color = cs.secondaryContainer) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = cs.onSecondaryContainer) }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, fontSize = 14.sp, color = cs.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = cs.outline)
        }
    }
}

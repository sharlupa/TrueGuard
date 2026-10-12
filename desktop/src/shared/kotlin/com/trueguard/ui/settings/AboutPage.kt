package com.trueguard.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trueguard.core.*
import com.trueguard.ui.LocalSnackbar
import com.trueguard.ui.components.*
import com.trueguard.ui.dashboard.DiagnosticsDialog
import kotlinx.coroutines.launch

@Composable
fun AboutPage(back: (() -> Unit)?, open: (SettingsRoute) -> Unit) {
    val s = Settings.instance
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var diagnostics by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme

    SettingsPage {
        PageHeader("О приложении", back)
        Surface(
            Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = cs.primaryContainer, contentColor = cs.onPrimaryContainer,
            border = BorderStroke(1.dp, cs.primary.copy(alpha = 0.18f)),
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    com.trueguard.core.Platform.hooks.appIcon(), null,
                    Modifier.size(88.dp).clip(com.trueguard.ui.components.animatedPolygonShape(MaterialShapes.Cookie9Sided)),
                )
                Spacer(Modifier.height(14.dp))
                Text("TrueGuard VPN", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(8.dp))
                InfoChip("Версия", "v$UPSTREAM_VERSION · $PLATFORM_NAME", onSurface = true)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Модифицированный Android-клиент для TURN/VK туннеля с измененным современным дизайном.",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer.copy(alpha = 0.78f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }

        SectionCard("Действия", Icons.Filled.Build) {
            NavTile(Icons.AutoMirrored.Filled.HelpOutline, "Справка", "Как решать капчу и потоки") { open(SettingsRoute.Help) }
            NavTile(Icons.Filled.HealthAndSafety, "Диагностика", "Интернет, сервер, хеши, ядро и права") { diagnostics = true }
            NavTile(Icons.Filled.Description, "Собрать отчёт", "Скопировать данные об устройстве") {
                scope.launch {
                    Clipboard.copy(Desktop.deviceReport())
                    snackbar.showSnackbar("Отчёт сформирован и скопирован")
                }
            }
            NavTile(
                if (checking) Icons.Filled.HourglassEmpty else Icons.Filled.Update, "Проверить обновления",
                when {
                    checking -> "Проверяем GitHub..."
                    s.updateLatestVersion.isNotEmpty() -> "Последняя на GitHub: ${s.updateLatestVersion}"
                    else -> "Релизы Android-клиента на GitHub"
                },
            ) {
                if (checking) return@NavTile
                checking = true
                scope.launch {
                    try {
                        val r = UpdateChecker.check()
                        s.updateLastCheckAt = System.currentTimeMillis()
                        r.version?.let { s.updateLatestVersion = it }
                        if (r.url != null) update = r else snackbar.showSnackbar(r.message)
                    } catch (e: Exception) {
                        snackbar.showSnackbar("Не удалось проверить: ${e.message}")
                    } finally {
                        checking = false
                    }
                }
            }
        }

        SectionCard("О проекте", Icons.Filled.Code) {
            NavTile(Icons.Filled.Person, "Автор Android-версии", "GitHub профиль amurcanov") { Desktop.openUrl("https://github.com/amurcanov") }
            NavTile(Icons.Filled.Code, "TrueGuard на GitHub", "Исходный код") { Desktop.openUrl("https://github.com/Nenormalniypon/TrueGuard") }
            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                onClick = { Desktop.openUrl("https://github.com/amurcanov/proxy-turn-vk-android") },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.Filled.Favorite, null)
                Spacer(Modifier.width(8.dp))
                Text("Поддержать проект (GitHub)", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    update?.let { u -> UpdateDialog(u, onDismiss = { update = null }) }
    if (diagnostics) DiagnosticsDialog(onDismiss = { diagnostics = false })
}

/** «Доступно обновление» / «Найден новый tag» (c1.a). */
@Composable
fun UpdateDialog(u: UpdateInfo, onDismiss: () -> Unit) {
    val s = Settings.instance
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Update, null) },
        title = { Text(if (u.newTag) "Найден новый tag" else "Доступно обновление") },
        text = { Text(u.message) },
        confirmButton = {
            PrimaryButton(onClick = { u.url?.let(Desktop::openUrl); onDismiss() }) { Text("Обновить", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            PlainButton(onClick = {
                s.updatePostponeUntil = System.currentTimeMillis() + 24 * 3600_000L
                onDismiss()
            }) { Text("Позже", fontWeight = FontWeight.SemiBold) }
        },
    )
}

@Composable
fun HelpPage(back: (() -> Unit)?) {
    SettingsPage {
        PageHeader("Справка", back, "Как решать капчу и потоки", Icons.AutoMirrored.Filled.HelpOutline)
        AccentCard("Капча и потоки", Icons.Filled.SmartToy) {
            Text(
                "• RJS-Капча — автоматическое решение (экспериментально). Временно отключено разработчиком.\n\n" +
                    "• Связь потоков — Рекомендую выбирать 12-36 потока для меньшего количества капч.\n\n" +
                    "• Как решать капчу — Нужно просто потянуть слайдер вправо так, чтобы все элементы идеально сошлись в пазле.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!Platform.isAndroid) AccentCard("Особенности $PLATFORM_NAME", Icons.Filled.Computer) {
            Text(
                if (Os.isWindows) {
                    "• Режим TUN поднимает туннель через WireGuard для Windows (его нужно установить; Windows спросит разрешение администратора).\n\n" +
                        "• Режим «Прокси» не требует прав администратора: настройте SOCKS5/HTTP-прокси в браузере или системе.\n\n" +
                        "• Капча решается в окне Chrome/Edge. Если браузера нет, работает только встроенный решатель."
                } else {
                    "• Режим TUN заворачивает в туннель всю систему через интерфейс wdtt0 (нужны wireguard-tools и права администратора).\n\n" +
                        "• Режим «Прокси» не требует root: настройте SOCKS5/HTTP-прокси в браузере или системе.\n\n" +
                        "• Капча решается в окне Chromium/Chrome. Если браузера нет, работает только встроенный решатель.\n\n" +
                        "• Исключения работают по программам: выбранные приложения идут мимо туннеля (или только они — в режиме белого списка)."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

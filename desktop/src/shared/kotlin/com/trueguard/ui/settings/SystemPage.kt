package com.trueguard.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trueguard.core.Desktop
import com.trueguard.core.Paths
import com.trueguard.core.Settings
import com.trueguard.core.RootAccess
import com.trueguard.core.RootPlan
import com.trueguard.core.RootPrompt
import com.trueguard.core.TunnelController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.trueguard.ui.LocalSnackbar
import com.trueguard.ui.components.*
import kotlinx.coroutines.launch

/** Настройки, которых нет в Android: права root, автозапуск, трей и браузер для капчи. */
@Composable
fun SystemPage(back: (() -> Unit)?) {
    val s = Settings.instance
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    SettingsPage {
        PageHeader("Система", back, "Права, автозапуск, трей и браузер", Icons.Filled.Computer)

        if (com.trueguard.core.Os.isWindows) WindowsRightsCard() else SectionCard("Права администратора", Icons.Filled.AdminPanelSettings) {
            ConnectedChoice(listOf("auto" to "Авто", "pkexec" to "pkexec", "sudo" to "sudo"), s.rootMethod, { s.rootMethod = it })
            Spacer(Modifier.height(10.dp))
            // кто спросит пароль: агент polkit (по D-Bus) или окно TrueGuard
            var refresh by remember { mutableIntStateOf(0) }
            val plan by produceState<RootPlan?>(null, s.rootMethod, refresh) {
                value = Paths.findCore()?.let { RootAccess.plan(it, s.rootMethod) }
            }
            val unit by produceState<String?>(null, refresh) {
                value = withContext(Dispatchers.IO) { if (RootAccess.agentRunning()) null else RootAccess.startableAgentUnit() }
            }
            val p = plan
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (p?.prompt) {
                        RootPrompt.None -> Icons.Filled.LockOpen
                        RootPrompt.Agent -> Icons.Filled.VerifiedUser
                        else -> Icons.Filled.Password
                    },
                    null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Hint(
                    when {
                        p == null -> "Проверяем polkit и sudo..."
                        p.method == null -> p.detail
                        else -> "Сейчас: ${p.method}. ${p.detail}."
                    },
                    Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                unit?.let { u ->
                    var starting by remember { mutableStateOf(false) }
                    TonalButton(onClick = {
                        starting = true
                        scope.launch {
                            val ok = RootAccess.startAgent(u)
                            starting = false
                            refresh++
                            snackbar.showSnackbar(if (ok) "Агент polkit запущен: TrueGuard больше не будет спрашивать пароль сам" else "Не удалось запустить $u")
                        }
                    }, enabled = !starting) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Запустить агент polkit")
                    }
                }
                OutlineButton(onClick = {
                    TunnelController.forgetRootPassword()
                    refresh++
                    scope.launch { snackbar.showSnackbar("Пароль администратора забыт") }
                }) {
                    Icon(Icons.Filled.LockReset, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Забыть пароль sudo")
                }
            }
            if (unit != null) {
                Spacer(Modifier.height(6.dp))
                Hint("Установлен $unit, но не запущен. Чтобы он стартовал со входом: systemctl --user enable $unit")
            }
        }

        SectionCard("Запуск", Icons.Filled.RocketLaunch) {
            SwitchTile(Icons.Filled.Login, "Автозапуск", "Запускать TrueGuard свёрнутым при входе в систему", s.autostart, {
                s.autostart = it
                Desktop.setAutostart(it)
            })
            SwitchTile(Icons.Filled.PowerSettingsNew, "Подключаться при запуске", "Сразу поднимать туннель после старта", s.connectOnLaunch, { s.connectOnLaunch = it })
            SwitchTile(Icons.Filled.Minimize, "Сворачивать в трей", "Крестик окна прячет TrueGuard, туннель продолжает работать", s.closeToTray, { s.closeToTray = it })
        }

        SectionCard("Надёжность", Icons.Filled.Shield) {
            SwitchTile(Icons.Filled.Autorenew, "Автопереподключение", "Перезапускать ядро после сбоя и смены сети", s.autoReconnect, { s.autoReconnect = it })
            SwitchTile(Icons.Filled.NotificationsActive, "Уведомления защиты", "«Защита отключена!» и запросы капчи", s.notifyDisconnects, { s.notifyDisconnects = it })
            SwitchTile(Icons.Filled.Terminal, "Подробный журнал", "Открывать вкладку «Логи» в режиме сырых строк ядра", s.detailedLogs, { s.detailedLogs = it })
        }

        SectionCard("Браузер для капчи", Icons.Filled.OpenInBrowser) {
            AppTextField(
                s.browserPath, { s.browserPath = it.trim() }, "Путь к Chromium/Chrome", placeholder = "авто",
                icon = Icons.Filled.Web,
                supporting = "Пусто — найти автоматически. «none» — без окна браузера, только встроенный решатель.",
            )
        }
    }
}

/** Windows: режим TUN работает через WireGuard для Windows, права спрашивает сама Windows (UAC). */
@Composable
private fun WindowsRightsCard() {
    val wg = remember { com.trueguard.core.WindowsTools.wireGuard() }
    SectionCard("Права администратора", Icons.Filled.AdminPanelSettings) {
        Hint(
            if (wg != null) "WireGuard для Windows найден. При подключении в режиме TUN Windows спросит разрешение администратора (UAC). " +
                "Чтобы не спрашивала, запускайте TrueGuard от имени администратора."
            else "Для режима TUN нужен WireGuard для Windows. Без него работает режим «Прокси» (Настройки → Сеть)."
        )
        if (wg == null) {
            Spacer(Modifier.height(10.dp))
            TonalButton(onClick = { com.trueguard.core.Desktop.openUrl("https://www.wireguard.com/install/") }) {
                Icon(Icons.Filled.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("Скачать WireGuard")
            }
        }
    }
}

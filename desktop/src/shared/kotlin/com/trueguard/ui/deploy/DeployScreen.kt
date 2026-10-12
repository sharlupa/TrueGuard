package com.trueguard.ui.deploy

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trueguard.core.DeployController
import com.trueguard.core.Settings
import com.trueguard.ui.LocalSnackbar
import com.trueguard.ui.components.*

/** Вкладка «Деплой» (c.j): SSH-доступ, порты, секреты и установка WDTT на VPS. */
@Composable
fun DeployScreen() {
    val s = Settings.instance
    val d = DeployController
    var secrets by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf(false) }
    val snackbar = LocalSnackbar.current

    LaunchedEffect(d.success, d.resultMessage) {
        if (d.success != null && d.resultMessage.isNotEmpty() && !d.running) snackbar.showSnackbar(d.resultMessage, withDismissAction = true)
    }

    val title: @Composable () -> Unit = {
        Text(
            "Установка сервера", Modifier.padding(bottom = 8.dp),
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold,
        )
    }
    val sshCard: @Composable () -> Unit = {
        AccentCard("SSH Доступ", Icons.Filled.Terminal) {
            AppTextField(
                s.deployIp, { s.deployIp = it.trim() }, "IP сервера или домен", placeholder = "1.2.3.4 (без порта)",
                icon = Icons.Filled.Dns, enabled = !d.running,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTextField(s.deployLogin, { s.deployLogin = it.trim() }, "Логин", Modifier.weight(1f), placeholder = "root", icon = Icons.Filled.Person, enabled = !d.running)
                PortField(s.deploySshPort, "Порт SSH", Modifier.weight(1f), enabled = !d.running) { s.deploySshPort = it }
            }
            Spacer(Modifier.height(12.dp))
            AppTextField(s.deployPassword, { s.deployPassword = it }, "Пароль", icon = Icons.Filled.Key, password = true, enabled = !d.running)
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SwitchTile(Icons.Filled.SettingsEthernet, "Ручные порты", "Настроить нестандартные порты", s.manualPortsEnabled, { s.manualPortsEnabled = it }, enabled = !d.running)
            AnimatedVisibility(s.manualPortsEnabled) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PortField(s.serverDtlsPort, "Порт DTLS сервера", Modifier.weight(1f), enabled = !d.running) { s.serverDtlsPort = it }
                    PortField(s.serverWgPort, "Порт WireGuard сервера", Modifier.weight(1f), enabled = !d.running) { s.serverWgPort = it }
                }
            }
        }
    }
    val controlCard: @Composable () -> Unit = {
        AccentCard("Управление", Icons.Filled.Build) {
            Text(
                "Обязательно задайте секреты перед первой установкой сервера.", Modifier.padding(bottom = 16.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TonalButton(onClick = { secrets = true }, modifier = Modifier.fillMaxWidth().height(56.dp), enabled = !d.running) {
                Icon(Icons.Filled.Key, null)
                Spacer(Modifier.width(8.dp))
                Text(if (s.manualPortsEnabled) "Секреты (BOT, Пароли, Порты)" else "Секреты (BOT, Пароли)", fontSize = 16.sp)
            }
            Spacer(Modifier.height(12.dp))
            val ready = s.deployIp.isNotBlank() && s.deployPassword.isNotEmpty()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(
                    onClick = { if (d.running) d.cancel() else d.run("install") },
                    modifier = Modifier.weight(1f).height(56.dp),
                    enabled = d.running && d.action == "install" || (!d.running && ready && s.deployMainPassword.isNotBlank()),
                ) {
                    Icon(if (d.running && d.action == "install") Icons.Filled.Stop else Icons.Filled.CloudUpload, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (d.running && d.action == "install") "Установка" else "Установить", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                PrimaryButton(
                    onClick = { confirmUninstall = true },
                    modifier = Modifier.weight(1f).height(56.dp),
                    enabled = !d.running && ready,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) {
                    Icon(Icons.Filled.DeleteForever, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Удалить", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            AnimatedVisibility(d.running || d.success != null) { DeployProgress() }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        if (maxWidth >= 900.dp) {
            // широкое окно: настройки слева, ход установки и вывод скрипта справа
            Row(Modifier.fillMaxSize().widthIn(max = 1500.dp).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    title()
                    sshCard()
                    controlCard()
                    Spacer(Modifier.height(16.dp))
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(top = 52.dp)) {
                    if (d.lines.isEmpty()) ConsolePlaceholder() else DeployConsole(Modifier.fillMaxSize())
                }
            }
        } else {
            Column(
                Modifier.fillMaxHeight().widthIn(max = com.trueguard.ui.ContentMaxWidth).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                title()
                sshCard()
                controlCard()
                AnimatedVisibility(
                    d.lines.isNotEmpty(),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) { DeployConsole(Modifier.height(280.dp)) }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (secrets) SecretsDialog(onDismiss = { secrets = false })
    if (confirmUninstall) ConfirmUninstallDialog(onDismiss = { confirmUninstall = false }) {
        confirmUninstall = false
        d.run("uninstall")
    }
}

@Composable
private fun PortField(value: Int, label: String, modifier: Modifier, enabled: Boolean, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    AppTextField(
        text, {
            text = it.filter(Char::isDigit).take(5)
            text.toIntOrNull()?.takeIf { p -> p in 1..65535 }?.let(onChange)
        },
        label, modifier, isError = text.toIntOrNull() !in 1..65535, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

/** «Прогресс установки» (b5.h): волнистая полоса MD3 Expressive и процент. */
@Composable
private fun DeployProgress() {
    val d = DeployController
    val cs = MaterialTheme.colorScheme
    val p by animateFloatAsState(d.progress, tween(1200), label = "progress")
    Column(Modifier.padding(top = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Прогресс установки", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = cs.primary)
        }
        Spacer(Modifier.height(10.dp))
        if (d.running) {
            LinearWavyProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { if (d.success == true) 1f else p },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = if (d.success == false) cs.error else cs.primary,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (d.running) d.stage else d.resultMessage,
            style = MaterialTheme.typography.bodySmall,
            color = if (d.success == false) cs.error else cs.onSurfaceVariant,
        )
    }
}

/** Вывод скрипта установки — моноширинная консоль. */
@Composable
private fun DeployConsole(modifier: Modifier) {
    val lines = DeployController.lines
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.animateScrollToItem(lines.lastIndex) }
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = cs.surfaceContainerLowest,
        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.4f)),
    ) {
        SelectionContainer {
            LazyColumn(state = state, contentPadding = PaddingValues(16.dp)) {
                items(lines) { l ->
                    Text(
                        l, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
                        color = if (l.contains("ошибка", true) || l.contains("error", true) || l.startsWith("[!]")) cs.error else cs.onSurface,
                    )
                }
            }
        }
    }
}

/** Пустая консоль на широком окне. */
@Composable
private fun ConsolePlaceholder() {
    val cs = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxSize(), shape = RoundedCornerShape(24.dp), color = cs.surfaceContainerLowest,
        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Terminal, null, Modifier.size(48.dp), tint = cs.outline)
            Spacer(Modifier.height(12.dp))
            Text("Вывод установки появится здесь", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
        }
    }
}

/** Секреты сервера (c.i): пароль туннеля, ID админа и токен бота. */
@Composable
private fun SecretsDialog(onDismiss: () -> Unit) {
    val s = Settings.instance
    var pass by remember { mutableStateOf(s.deployMainPassword) }
    var admin by remember { mutableStateOf(s.deployAdminId) }
    var token by remember { mutableStateOf(s.deployBotToken) }
    val passOk = pass.isEmpty() || Regex("^[A-Za-z0-9._@:+=,!#%^*~-]{4,128}$").matches(pass)
    val adminOk = admin.isEmpty() || Regex("^\\d{1,20}$").matches(admin)
    val tokenOk = token.isEmpty() || Regex("^\\d{3,20}:[A-Za-z0-9_-]{20,100}$").matches(token)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Key, null) },
        title = { Text(if (s.manualPortsEnabled) "Секреты (BOT, Пароли, Порты)" else "Секреты (BOT, Пароли)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTextField(
                    pass, { pass = it.trim() }, "Пароль туннеля (любой)", placeholder = "Задайте надежный пароль",
                    icon = Icons.Filled.Password, password = true, isError = !passOk,
                    supporting = if (!passOk) "4–128 символов: латиница, цифры и ._@:+=,!#%^*~-" else null,
                )
                AppTextField(
                    admin, { admin = it.filter(Char::isDigit) }, "ID Админа", placeholder = "ID из @getmyid_bot",
                    icon = Icons.Filled.Person, isError = !adminOk, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                AppTextField(
                    token, { token = it.trim() }, "Токен Бота", placeholder = "Токен от BotFather",
                    icon = Icons.Filled.SmartToy, password = true, isError = !tokenOk,
                )
            }
        },
        confirmButton = {
            PrimaryButton(
                onClick = {
                    s.deployMainPassword = pass
                    s.deployAdminId = admin
                    s.deployBotToken = token
                    onDismiss()
                },
                enabled = passOk && adminOk && tokenOk,
            ) { Text("Сохранить", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { PlainButton(onClick = onDismiss) { Text("Отмена", fontWeight = FontWeight.Bold) } },
    )
}

/** Подтверждение удаления (c.S): нужно ввести «да». */
@Composable
private fun ConfirmUninstallDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val ok = text.trim().lowercase() == "да"
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Удалить WDTT с сервера?") },
        text = {
            Column {
                Text("Сервис, бинарник, правила firewall и интерфейс WDTT будут удалены с VPS.")
                Spacer(Modifier.height(12.dp))
                AppTextField(text, { text = it }, "Введите «да» для подтверждения")
            }
        },
        confirmButton = {
            PrimaryButton(
                onClick = onConfirm, enabled = ok,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text("Удалить", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { PlainButton(onClick = onDismiss) { Text("Отмена", fontWeight = FontWeight.Bold) } },
    )
}

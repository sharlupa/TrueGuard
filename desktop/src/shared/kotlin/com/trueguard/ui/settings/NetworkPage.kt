package com.trueguard.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.trueguard.core.Settings
import com.trueguard.core.TunnelController
import com.trueguard.ui.components.*

@Composable
fun NetworkPage(back: (() -> Unit)?) {
    val s = Settings.instance
    SettingsPage {
        PageHeader("Сеть", back, "Порты, DNS и домен для рукопожатия", Icons.Filled.Language)
        HeroCard(
            Icons.Filled.TravelExplore, "Сетевой профиль",
            "Здесь меняются параметры, которые напрямую влияют на подключение.",
            listOf("Порт" to "${s.listenPort}", "SNI" to s.sni.ifBlank { "не задан" }),
        )

        SectionCard("Подключение", Icons.Filled.SettingsInputComponent) {
            var port by remember { mutableStateOf(s.listenPort.toString()) }
            val portOk = port.toIntOrNull() in 1..65535
            AppTextField(
                port, {
                    port = it.filter(Char::isDigit).take(5)
                    port.toIntOrNull()?.takeIf { p -> p in 1..65535 }?.let { p -> s.listenPort = p }
                },
                "Локальный порт VPN", placeholder = "9000", icon = Icons.Filled.Lan,
                supporting = "Порт, на котором приложение слушает локальный VPN-трафик.", isError = !portOk,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.height(12.dp))
            AppTextField(
                s.sni, { s.sni = it.trim() }, "SNI домен", placeholder = "google.com", icon = Icons.Filled.Public,
                supporting = "Опционально: домен для TLS ClientHello, если сеть требует SNI.",
                trailing = if (s.sni.isNotEmpty()) {
                    { IconButton(onClick = { s.sni = "" }) { Icon(Icons.Filled.Clear, "Очистить SNI") } }
                } else null,
            )
        }

        SectionCard("Пакеты", Icons.AutoMirrored.Filled.CompareArrows) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Размер пакета (MTU)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Hint("Авто обычно стабильнее, ручной MTU полезен для сложных сетей.")
                }
                Spacer(Modifier.width(12.dp))
                InfoChip("Сейчас", if (s.customMtu == 0) "Авто" else "${s.customMtu}")
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Авто", 1420 to "1420", 1400 to "1400", 1280 to "1280").forEach { (v, label) ->
                    FilterChip(
                        selected = s.customMtu == v, onClick = { s.customMtu = v }, label = { Text(label) },
                        shape = RoundedCornerShape(14.dp),
                        leadingIcon = if (s.customMtu == v) {
                            { Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                        } else null,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            var mtu by remember { mutableStateOf(if (s.customMtu == 0) "" else s.customMtu.toString()) }
            LaunchedEffect(s.customMtu) { if ((mtu.toIntOrNull() ?: 0).let { if (it < 1280) 0 else it } != s.customMtu) mtu = if (s.customMtu == 0) "" else s.customMtu.toString() }
            AppTextField(
                mtu, {
                    mtu = it.filter(Char::isDigit).take(4)
                    val v = mtu.toIntOrNull() ?: 0
                    s.customMtu = if (v < 1280) 0 else v.coerceAtMost(1500)
                },
                "Свой MTU", placeholder = "Авто", icon = Icons.Filled.Straighten,
                supporting = "Значение ниже 1280 возвращает автоматический режим.",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }

        SectionCard("DNS", Icons.Filled.Dns) {
            Text(
                "Выберите резолвер для туннеля.", Modifier.padding(bottom = 12.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ConnectedChoice(
                listOf("default" to "Авто", "adguard" to "AdGuard", "cloudflare" to "1.1.1.1", "custom" to "Свой"),
                s.customDns, { s.customDns = it },
            )
            AnimatedVisibility(s.customDns == "custom") {
                val ok = Regex("^(\\d{1,3}\\.){3}\\d{1,3}(,(\\d{1,3}\\.){3}\\d{1,3})*$").matches(s.customDnsIp.trim())
                AppTextField(
                    s.customDnsIp, { s.customDnsIp = it.trim() }, "IP DNS сервера", Modifier.padding(top = 12.dp),
                    placeholder = "1.1.1.1", icon = Icons.Filled.Dns, isError = !ok,
                    supporting = "IPv4-адрес DNS, который попадёт в конфиг туннеля.",
                )
            }
        }

        // в Android туннель всегда системный (VpnService), режимов и интерфейсов Linux нет
        if (!com.trueguard.core.Platform.isAndroid) TunnelModeCard()
    }
}

/** Только для Linux: TUN на всю систему или локальный прокси без root. */
@Composable
private fun TunnelModeCard() {
    val s = Settings.instance
    val busy = TunnelController.phase.active
    SectionCard("Режим туннеля", Icons.Filled.VpnLock) {
        ConnectedChoice(listOf("tun" to "TUN · вся система", "proxy" to "Прокси"), s.tunnelMode, { s.tunnelMode = it }, enabled = !busy)
        Spacer(Modifier.height(10.dp))
        Hint(
            if (s.tunnelMode == "tun" && com.trueguard.core.Os.isWindows) "Весь трафик системы идёт через WireGuard для Windows (интерфейс wdtt0). При подключении и отключении Windows спросит права администратора."
            else if (s.tunnelMode == "tun") "Весь трафик системы идёт через WireGuard-интерфейс wdtt0. Нужны права администратора (pkexec или sudo) и wireguard-tools."
            else "Только для программ, настроенных на прокси. Root не нужен: укажите SOCKS5/HTTP-адрес в браузере или системе."
        )
        AnimatedVisibility(s.tunnelMode == "proxy") {
            AppTextField(
                s.proxyListen, { s.proxyListen = it.trim() }, "Адрес прокси (SOCKS5 + HTTP)", Modifier.padding(top = 12.dp),
                placeholder = "127.0.0.1:1080", icon = Icons.Filled.Router,
            )
        }
        AnimatedVisibility(s.tunnelMode == "tun") {
            Column(Modifier.padding(top = 8.dp)) {
                SwitchTile(Icons.Filled.Dns, "DNS через туннель", if (com.trueguard.core.Os.isWindows) "DNS туннеля для всей системы" else "Системный резолвер на время подключения (resolvconf)", s.tunDns, { s.tunDns = it })
                SwitchTile(Icons.Filled.Block, "Блокировать IPv6", "Чтобы трафик не утекал мимо туннеля", s.blockIpv6, { s.blockIpv6 = it })
                Spacer(Modifier.height(8.dp))
                AppTextField(
                    s.excludeAddrs, { s.excludeAddrs = it }, "Не пускать в туннель", placeholder = "192.168.0.0/16, 10.0.0.0/8",
                    icon = Icons.Filled.CallSplit, singleLine = false, supporting = "Подсети через запятую — пойдут напрямую.",
                )
                Spacer(Modifier.height(8.dp))
                AppTextField(
                    s.includeAddrs, { s.includeAddrs = it }, "Только эти адреса", placeholder = "пусто — весь трафик",
                    icon = Icons.Filled.FilterAlt, singleLine = false, supporting = "Если заполнено, в туннель пойдут только указанные подсети.",
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        AppTextField(
            s.bindIface, { s.bindIface = it.trim() }, "Сетевой интерфейс", placeholder = "авто", icon = Icons.Filled.SettingsEthernet,
            supporting = "Привязать соединения ядра к интерфейсу (например, wlan0). Пусто — по маршруту по умолчанию.",
        )
    }
}

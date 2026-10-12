package com.trueguard.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trueguard.core.Settings
import com.trueguard.core.Platform
import com.trueguard.ui.components.*
import kotlin.math.roundToInt

@Composable
fun PerformancePage(back: (() -> Unit)?) {
    val s = Settings.instance
    val hashes = s.allHashes.filter { it.length >= 16 }.size.coerceAtLeast(1)
    val browserLabel = if (Platform.isAndroid) "WebView" else "Браузер"
    SettingsPage {
        PageHeader("Производительность", back, "Хеши, потоки и режим решения капчи", Icons.Filled.Speed)
        HeroCard(
            Icons.Filled.Bolt, "Баланс скорости",
            "Меньше лишних потоков — меньше расход батареи и ниже шанс словить капчу.",
            listOf("Потоки" to "${s.totalWorkers(hashes)}", "Капча" to if (s.captchaMode == "rjs") "RJS" else browserLabel),
        )

        SectionCard("VK Хэши (Ключи)", Icons.Filled.VpnKey) {
            Hint("Можно вставлять чистый хеш или ссылку vk.com/call/join — приложение оставит только нужную часть.")
            Spacer(Modifier.height(12.dp))
            listOf("Основной хеш", "Дополнительный 1", "Дополнительный 2", "Дополнительный 3").forEachIndexed { i, label ->
                HashField(s.vkHashes.getOrElse(i) { "" }, label, if (i == 0) Icons.Filled.Key else Icons.Filled.AddLink) { s.setHash(i, it) }
            }
            HashField(s.secondaryVkHash, "Запасной хеш", Icons.Filled.Restore, "Подключается автоматически, если основной перестал работать.") {
                s.secondaryVkHash = it
            }
        }

        SectionCard("Мощность", Icons.Filled.Memory) {
            val max = 27 * hashes
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Потоки обработки", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Hint("Шаг 12 потоков, лимит зависит от количества валидных хешей.")
                }
                Spacer(Modifier.width(12.dp))
                AnimatedContent(s.totalWorkers(hashes), transitionSpec = { fadeIn() togetherWith fadeOut() }) { n ->
                    InfoChip("Активно", "$n")
                }
            }
            Spacer(Modifier.height(8.dp))
            val value = s.workersPerHash.coerceIn(12, max.coerceAtLeast(12)).toFloat()
            Slider(
                value = value,
                onValueChange = { v ->
                    // шаг 12, но верхняя граница — ровно лимит хешей
                    val snapped = ((v / 12f).roundToInt() * 12).coerceIn(12, max.coerceAtLeast(12))
                    s.workersPerHash = if (max - v < 6) max.coerceAtLeast(12) else snapped
                },
                valueRange = 12f..max.coerceAtLeast(13).toFloat(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(12, 24, 36).forEach { n ->
                    val sel = s.workersPerHash == n
                    FilterChip(
                        selected = sel, onClick = { s.workersPerHash = n.coerceAtMost(max.coerceAtLeast(12)) }, label = { Text("$n") },
                        shape = RoundedCornerShape(14.dp), enabled = n <= max || n == 12,
                        leadingIcon = if (sel) {
                            { Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                        } else null,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Hint("Обычно 24-36 хватает для скорости без лишнего расхода CPU.")
        }

        SectionCard("Капча", Icons.Filled.SmartToy) {
            GroupLabel("Метод решения")
            Spacer(Modifier.height(10.dp))
            ConnectedChoice(listOf("wv" to browserLabel, "rjs" to "Reverse JS (Auto)"), if (s.captchaMode == "rjs") "rjs" else "wv", { s.captchaMode = it })
            if (s.captchaMode == "rjs") {
                Text(
                    "RJS временно отключен разработчиком проекта.", Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(16.dp))
            GroupLabel(if (Platform.isAndroid) "Режим WebView" else "Режим браузера")
            Spacer(Modifier.height(10.dp))
            ConnectedChoice(listOf("auto" to "Авто", "manual" to "Ручной"), s.captchaWbvSolveMethod, { s.captchaWbvSolveMethod = it })
            Spacer(Modifier.height(8.dp))
            Hint(
                when {
                    s.captchaWbvSolveMethod == "manual" -> "Окно ${if (Platform.isAndroid) "WebView" else "Chromium"} открывается сразу — галочку ставите вы."
                    Platform.isAndroid -> "Сначала пробует решить сам (Go-решатель), окно WebView откроется только если не вышло."
                    else -> "Сначала пробует решить сам (Go-решатель и скрытый браузер), окно откроется только если не вышло."
                }
            )
        }

        SectionCard("Авторизация VK", Icons.Filled.Person) {
            GroupLabel("Способ получения TURN-креды")
            Spacer(Modifier.height(10.dp))
            ConnectedChoice(listOf("vkcalls" to "Вызов (без капчи)", "legacy" to "Обычный (капча)"), s.vkAuthMode, { s.vkAuthMode = it })
            Spacer(Modifier.height(8.dp))
            Hint(
                if (s.vkAuthMode == "vkcalls") "Сначала пробуем анонимный вход через VK Calls API без капчи. Если VK всё равно потребует капчу — автоматически откатываемся на обычный способ."
                else "Стандартный вход с перебором client_id и решением капчи при необходимости."
            )
        }

        SectionCard("Маскировка трафика", Icons.Filled.Hub) {
            GroupLabel("Тип RTP-маскировки (WDTT 1.2.4 upstream)")
            Spacer(Modifier.height(10.dp))
            ConnectedChoice(listOf("audio" to "Аудио", "video" to "Видео"), s.obfsMode, { s.obfsMode = it })
            Spacer(Modifier.height(8.dp))
            Hint(
                if (s.obfsMode == "video") "Трафик маскируется под видеопоток WebRTC (PT 96, увеличенный паддинг). Полезно для разнообразия — не все сессии выглядят как аудиозвонок."
                else "Трафик маскируется под аудиопоток OPUS (PT 111) — стандартный режим."
            )
        }

        SectionCard("Фингерпринт браузера", Icons.Filled.Fingerprint) {
            GroupLabel("Под какой браузер маскироваться в VK-запросах")
            Spacer(Modifier.height(10.dp))
            ConnectedChoice(
                listOf("chrome" to "Chrome", "safari" to "Safari", "ios" to "iOS", "android" to "Android", "firefox" to "Firefox"),
                s.vkFingerprint, { s.vkFingerprint = it },
            )
            Spacer(Modifier.height(8.dp))
            Hint("Меняет User-Agent и Client Hints, которые Go-клиент шлёт при получении TURN-кредов VK. Полезно варьировать, если один и тот же фингерпринт долго используется с одного IP.")
        }
    }
}

@Composable
private fun HashField(value: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, supporting: String? = null, onChange: (String) -> Unit) {
    val norm = Settings.normalizeHash(value)
    val tooShort = norm.isNotEmpty() && norm.length < 16
    AppTextField(
        value, { onChange(Settings.normalizeHash(it).ifEmpty { it.trim() }) }, label, Modifier.padding(bottom = 8.dp),
        placeholder = "vk.com/call/join/...", icon = icon, isError = tooShort,
        supporting = if (tooShort) "Хеш слишком короткий, нужно минимум 16 символов." else supporting,
        trailing = if (value.isNotEmpty()) {
            { IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Clear, "Очистить") } }
        } else null,
    )
}

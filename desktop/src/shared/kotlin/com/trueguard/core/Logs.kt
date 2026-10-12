package com.trueguard.core

import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class LogEntry(var text: String, val key: String? = null, var error: Boolean = false) {
    var time: LocalTime = LocalTime.now()
    var count = 1

    fun format(): String = "${time.format(TIME)} ${if (count > 1) "(x$count) " else ""}$text"

    companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}

/**
 * Два журнала, как в Android: краткий (ключевые события, обновляются на месте)
 * и подробный (сырые строки ядра). Повторы схлопываются в одну строку со счётчиком.
 * Все изменения идут из главного потока; UI подписан на [version].
 */
object LogStore {
    private const val MAX_RAW = 4000
    private const val MAX_HUMAN = 600

    val raw = ArrayList<LogEntry>()
    val human = ArrayList<LogEntry>()
    private val byKey = HashMap<String, LogEntry>()

    /** Счётчик изменений для перерисовки; обновляется не чаще раза в 150 мс. */
    val version = mutableIntStateOf(0)
    private var scheduled = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val errorCount: Int get() = human.count { it.error }

    private fun changed() {
        if (scheduled) return
        scheduled = true
        // не перерисовываем на каждую строку — ядро может писать сотни строк в секунду
        scope.launch {
            delay(150)
            scheduled = false
            version.intValue++
        }
    }

    @Synchronized
    fun addRaw(line: String, error: Boolean = false) {
        val last = raw.lastOrNull()
        if (last != null && last.text == line) {
            last.count++
            last.time = LocalTime.now()
        } else {
            raw.add(LogEntry(line, error = error))
            if (raw.size > MAX_RAW) raw.subList(0, raw.size - MAX_RAW).clear()
        }
        changed()
    }

    /** Событие краткого журнала. С ключом — обновляет существующую запись. */
    @Synchronized
    fun event(text: String, key: String? = null, error: Boolean = false) {
        if (key != null) {
            val existing = byKey[key]
            if (existing != null) {
                if (existing.text == text) existing.count++ else {
                    existing.text = text
                    existing.count = 1
                }
                existing.time = LocalTime.now()
                existing.error = error
                human.remove(existing)
                human.add(existing)
                changed()
                return
            }
        } else {
            val last = human.lastOrNull()
            if (last != null && last.text == text && last.key == null) {
                last.count++
                last.time = LocalTime.now()
                changed()
                return
            }
        }
        val e = LogEntry(text, key, error)
        human.add(e)
        if (key != null) byKey[key] = e
        if (human.size > MAX_HUMAN) {
            val old = human.subList(0, human.size - MAX_HUMAN)
            old.forEach { o -> o.key?.let { if (byKey[it] === o) byKey.remove(it) } }
            old.clear()
        }
        // также в подробный журнал, чтобы там была полная картина
        raw.add(LogEntry("» $text", error = error))
        changed()
    }

    @Synchronized
    fun clear() {
        raw.clear()
        human.clear()
        byKey.clear()
        version.intValue++
    }

    @Synchronized
    fun snapshot(detailed: Boolean): List<Pair<String, Boolean>> =
        (if (detailed) raw else human).map { it.format() to it.error }

    @Synchronized
    fun dump(detailed: Boolean): String = (if (detailed) raw else human).joinToString("\n") { it.format() }
}

data class HumanLine(val key: String, val text: String, val error: Boolean = false)

private val tsPrefix = Regex("^\\d{4}/\\d{2}/\\d{2}\\s\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\s")
private val paren = Regex("\\s*\\([^)]+\\)\\s*")
private val streamPrefix = Regex("^\\[STREAM \\d+]\\s*")

fun stripTimestamp(line: String) = line.replaceFirst(tsPrefix, "")

private fun tail(s: String, marker: String): String {
    val i = s.indexOf(marker)
    return if (i < 0) s else s.substring(i + marker.length).trim()
}

private fun hashKey(prefix: String, s: String) = "$prefix${s.take(18).hashCode()}"

/** Перевод строки лога ядра в событие краткого журнала (порт разбора логов из Android). */
fun humanize(line: String): HumanLine? {
    val l = line.replaceFirst(streamPrefix, "")
    val lower = l.lowercase()

    if (l.contains("FATAL_AUTH") || l.contains("неверный пароль") || l.contains("другому устройству") || l.contains("истёк")) {
        var reason = "Ошибка авторизации"
        if (l.contains("неверный пароль")) reason = "Неверный пароль подключения"
        if (l.contains("истёк")) reason = "Срок действия пароля истёк"
        if (l.contains("другому устройству") || l.contains("device_mismatch")) reason = "Пароль привязан к другому устройству"
        return HumanLine("auth", "🔒 $reason", error = true)
    }
    if (lower.contains("flood control")) return HumanLine("flood", "Flood Control (ВК ограничил ваш IP). Попробуйте позже.", true)
    if (lower.contains("ip mismatch")) return HumanLine("ip_mismatch", "IP Mismatch (IP утерян). Попробуйте переподключиться.", true)
    if (lower.contains("lookup login.vk.ru")) return HumanLine("err_vk_dns", "[СЕТЬ] DNS до VK недоступен: login.vk.ru", true)
    if (listOf("[КЛИЕНТ] Режим:", "[КЛИЕНТ] Воркеров:", "[КЛИЕНТ] Слушаю:", "[КЛИЕНТ] Обход туннеля:").any { l.contains(it) }) {
        return HumanLine(hashKey("client_", tail(l, "[КЛИЕНТ]")), l.substring(l.indexOf("[КЛИЕНТ]")))
    }
    if (l.contains("[СТАТИСТИКА]")) return HumanLine("stats", "[СТАТИСТИКА] ${tail(l, "[СТАТИСТИКА]")}")
    if (l.contains("[КАПЧА] AUTO:")) {
        val t = tail(l, "[КАПЧА] AUTO:").replace(paren, " ").trim()
        var key = hashKey("captcha_auto_", t)
        if (t.contains("старт")) key = "captcha_auto_1"
        if (t.contains("Go v2") && t.contains("2 попыт")) key = "captcha_auto_2"
        if (t.contains("WBV Auto попытка")) key = "captcha_auto_3"
        if (t.contains("финальная")) key = "captcha_auto_4"
        if (t.contains("ручной WebView")) key = "captcha_auto_5"
        if (t.contains("решил") && !t.contains("не решил")) key = "captcha_auto_done"
        val err = t.lowercase().contains("ошибка") || t.contains("timeout") || t.contains("не решил")
        return HumanLine(key, "[КАПЧА AUTO] ${t.replace("WebView", "браузер")}", err)
    }
    if (l.contains("[КАПЧА] RJS:")) return HumanLine("captcha_rjs", "[КАПЧА RJS] ${tail(l, "[КАПЧА] RJS:").replace(paren, " ").trim()}")
    if (l.contains("[КАПЧА] WBV:")) return HumanLine("captcha_wv", "[КАПЧА] ${tail(l, "[КАПЧА] WBV:").replace(paren, " ").trim()}")
    if (l.contains("Открыто окно браузера")) return HumanLine("captcha_manual", "[КАПЧА] Открыто окно браузера — решите капчу вручную")
    if (l.contains("ставлю галочку автоматически")) return HumanLine("captcha_headless", "[КАПЧА] Браузер ставит галочку автоматически...")
    if (l.contains("Решаю VK Smart Captcha")) return HumanLine("captcha_start", "[КАПЧА] Решение капчи...")
    if (l.contains("Smart Captcha решена") || l.contains("v2 pow solved")) return HumanLine("captcha_done", "[КАПЧА] Капча решена ✓")
    if (l.contains("капча не решена") || l.contains("ошибка решения капчи") || l.contains("[Captcha] Solve failed")) {
        return HumanLine("captcha_failed", "[КАПЧА] Ошибка решения капчи", true)
    }
    if (l.contains("Запрос кредов")) return HumanLine("creds_start", "[ВК] Получение учетных данных...")
    if (l.contains("Креды OK") || l.contains("[VKCalls] SUCCESS")) return HumanLine("creds_ok", "[ВК] Учетные данные проверены ✓")
    if (l.contains("Ошибка кредов")) return HumanLine("err_creds", "[ВК] Ошибка кредов: ${tail(l, "Ошибка кредов:")}", true)
    if (l.contains("[WRAP]")) return HumanLine("wrap_status", "[WRAP] ${tail(l, "[WRAP]")}")
    if (l.contains("[TURN]")) {
        val t = tail(l, "[TURN]")
        val err = Regex("ошибка|не удалось|неполный ответ", RegexOption.IGNORE_CASE).containsMatchIn(t)
        return HumanLine(hashKey("turn_", t), "[TURN] $t", err)
    }
    if (l.contains("[DTLS] Рукопожатие")) return HumanLine("dtls_start", "[DTLS] Рукопожатие (Handshake)...")
    if (l.contains("[DTLS] Соединение установлено")) return HumanLine("dtls_ok", "[DTLS] Соединение установлено ✓")
    if (l.contains("[READY]")) return HumanLine("ready", "[READY] Туннель готов к работе ✓")
    if (l.contains("Конфиг получен")) return HumanLine("config", "[КОНФИГ] WireGuard-конфиг получен от сервера ✓")
    for (tag in listOf("[TUN]", "[ПРОКСИ]", "[ПРИЛОЖЕНИЯ]", "[ТУННЕЛЬ]", "[КОНФИГ]", "[СЕТЬ]")) {
        if (l.contains(tag)) {
            val t = l.substring(l.indexOf(tag))
            val err = Regex("ошибка|error|не удалось|fail", RegexOption.IGNORE_CASE).containsMatchIn(t)
            return HumanLine(hashKey("sys_", t), t, err)
        }
    }
    if (l.contains("Фатальная ошибка") || l.contains("Невосстановимая")) {
        return HumanLine(hashKey("fatal_", l), l.replaceFirst(Regex("^\\[ВОРКЕР #\\d+]\\s*"), ""), true)
    }
    if (Regex("ошибка|error|fail|timeout|refused", RegexOption.IGNORE_CASE).containsMatchIn(l)) {
        var key = hashKey("general_error_", l)
        if (lower.contains("connection refused")) key = "err_conn_refused"
        if (lower.contains("timeout")) key = "err_timeout"
        if (l.contains("DTLS")) key = "err_dtls"
        return HumanLine(key, l.replaceFirst(Regex("^\\[(ВОРКЕР|СЕССИЯ|ГРУППА) #\\d+]\\s*"), ""), true)
    }
    return null
}

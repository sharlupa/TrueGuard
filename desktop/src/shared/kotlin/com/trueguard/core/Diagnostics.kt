package com.trueguard.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

enum class CheckState { Pending, Ok, Warn, Fail }

data class CheckResult(val title: String, val state: CheckState, val detail: String)

/** Раздел «Диагностика»: те же проверки, что в Android, плюс системные для Linux. */
object Diagnostics {
    val titles = listOf(
        "Доступ к интернету",
        "Доступность сервера (VPS)",
        "Формат VK Хэшей",
        "DNS до VK",
        "Ядро туннеля (Core)",
        "Режим туннеля",
        "Браузер для капчи",
    )

    fun run(): Flow<CheckResult> = flow {
        val s = Settings.instance
        emit(internet())
        emit(vps(s))
        emit(hashes(s))
        emit(vkDns())
        emit(core())
        emit(mode(s))
        emit(browser(s))
    }.flowOn(Dispatchers.IO)

    private fun internet(): CheckResult {
        val t = titles[0]
        for (url in listOf("http://connectivitycheck.gstatic.com/generate_204", "https://ya.ru", "https://vk.com")) {
            try {
                val c = URI(url).toURL().openConnection() as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 6000
                c.instanceFollowRedirects = false
                val code = c.responseCode
                c.disconnect()
                return CheckResult(t, CheckState.Ok, "${URI(url).host}: HTTP $code")
            } catch (_: Exception) {
            }
        }
        return CheckResult(t, CheckState.Fail, "Нет ответа ни от одного узла проверки")
    }

    private suspend fun vps(s: Settings): CheckResult {
        val t = titles[1]
        val srv = s.activeServer ?: return CheckResult(t, CheckState.Warn, "Сервер не выбран")
        val host = srv.ip.split(':').first()
        val ip = try {
            InetAddress.getByName(host).hostAddress
        } catch (_: Exception) {
            return CheckResult(t, CheckState.Fail, "Не удалось разрешить $host")
        }
        if (Paths.which("ping") != null) {
            val r = if (Os.isWindows) run("ping", "-n", "2", "-w", "2000", ip, timeoutSec = 10) else run("ping", "-c", "2", "-W", "2", ip, timeoutSec = 10)
            if (r.code == 0) {
                // Linux: «= мин/сред/макс», Windows: «Среднее = 42 мс» / «Average = 42ms»
                val m = Regex("= [\\d.]+/([\\d.]+)/").find(r.out) ?: Regex("(?:Average|Среднее)\\s*=\\s*(\\d+)").find(r.out)
                return CheckResult(t, CheckState.Ok, "$ip отвечает на ping${m?.let { " (${it.groupValues[1]} мс)" } ?: ""}")
            }
        }
        try {
            Socket().use { it.connect(InetSocketAddress(ip, s.deploySshPort), 4000) }
            return CheckResult(t, CheckState.Ok, "$ip: SSH-порт ${s.deploySshPort} открыт")
        } catch (_: Exception) {
        }
        return CheckResult(t, CheckState.Warn, "$ip не отвечает на ping и SSH — UDP-порт ${srv.dtlsPort} при этом может работать")
    }

    private fun hashes(s: Settings): CheckResult {
        val t = titles[2]
        val hashes = s.allHashes
        if (hashes.isEmpty()) return CheckResult(t, CheckState.Fail, "Хеши не заданы (Настройки → Производительность)")
        val bad = hashes.filter { it.length < 16 }
        if (bad.isNotEmpty()) return CheckResult(t, CheckState.Fail, "Слишком короткие: ${bad.joinToString(", ")}")
        val sec = Settings.normalizeHash(s.secondaryVkHash)
        return CheckResult(t, CheckState.Ok, "${hashes.size} шт.${if (sec.isNotEmpty()) " + запасной" else ""}, формат верный")
    }

    private fun vkDns(): CheckResult {
        val t = titles[3]
        return try {
            CheckResult(t, CheckState.Ok, "login.vk.ru → ${InetAddress.getByName("login.vk.ru").hostAddress}")
        } catch (_: Exception) {
            CheckResult(t, CheckState.Fail, "DNS до VK недоступен: login.vk.ru")
        }
    }

    private fun core(): CheckResult {
        val t = titles[4]
        val core = Paths.findCore() ?: return CheckResult(t, CheckState.Fail, "wdtt-client не найден")
        val f = File(core)
        if (!f.canExecute()) return CheckResult(t, CheckState.Fail, "$core не исполняемый")
        return CheckResult(t, CheckState.Ok, "$core (${"%.1f".format(f.length() / 1048576.0)} МБ)")
    }

    private suspend fun mode(s: Settings): CheckResult {
        val t = titles[5]
        if (Platform.isAndroid) return CheckResult(t, CheckState.Ok, "VPN Android (VpnService): приложение исключено из туннеля")
        if (s.tunnelMode == "proxy") return CheckResult(t, CheckState.Ok, "Прокси ${s.proxyListen}: root не нужен")
        if (Os.isWindows) {
            val wg = WindowsTools.wireGuard() ?: return CheckResult(t, CheckState.Fail, "TUN: не установлен WireGuard для Windows (wireguard.com/install)")
            return CheckResult(t, CheckState.Ok, "TUN через WireGuard для Windows ($wg); при подключении Windows спросит права администратора")
        }
        val need = buildList {
            add("wg-quick"); add("ip")
            if (s.excludedApps.isNotEmpty()) add("nft")
            if (s.tunDns) add("resolvconf")
        }
        val missing = need.filter { Paths.which(it) == null }
        if (missing.isNotEmpty()) return CheckResult(t, CheckState.Fail, "TUN: не хватает ${missing.joinToString(", ")}")
        val core = Paths.findCore() ?: return CheckResult(t, CheckState.Fail, "TUN: ядро не найдено")
        val plan = RootAccess.plan(core, s.rootMethod)
        if (plan.method == null) return CheckResult(t, CheckState.Fail, "TUN: ${plan.detail}")
        return CheckResult(t, CheckState.Ok, "TUN через ${plan.method}: ${plan.detail}")
    }

    private fun browser(s: Settings): CheckResult {
        val t = titles[6]
        if (Platform.isAndroid) return CheckResult(t, CheckState.Ok, "WebView приложения")
        val path = s.browserPath.trim()
        if (path == "none") return CheckResult(t, CheckState.Warn, "Отключён — только Go-решатель")
        if (path.isNotEmpty()) {
            return if (File(path).exists()) CheckResult(t, CheckState.Ok, path) else CheckResult(t, CheckState.Fail, "$path не найден")
        }
        if (Os.isWindows) {
            return WindowsTools.browser()?.let { CheckResult(t, CheckState.Ok, it) }
                ?: CheckResult(t, CheckState.Warn, "Chrome/Edge не найден — ручная капча будет недоступна")
        }
        for (b in listOf("chromium", "chromium-browser", "google-chrome-stable", "google-chrome", "brave", "brave-browser", "microsoft-edge-stable", "vivaldi-stable")) {
            Paths.which(b)?.let { return CheckResult(t, CheckState.Ok, it) }
        }
        return CheckResult(t, CheckState.Warn, "Chromium/Chrome не найден — ручная капча будет недоступна")
    }
}

data class UpdateInfo(val version: String?, val url: String?, val message: String, val newTag: Boolean = false)

/** Проверка обновлений Android-клиента (upstream) на GitHub, как кнопка «Проверить обновления». */
object UpdateChecker {
    const val REPO = "amurcanov/proxy-turn-vk-android"

    private fun get(url: String): String {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "TrueGuard-$PLATFORM_NAME/$APP_VERSION")
        try {
            if (c.responseCode != 200) throw java.io.IOException("GitHub HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    suspend fun check(): UpdateInfo = kotlinx.coroutines.withContext(Dispatchers.IO) {
        var latestTag: String? = null
        var url: String? = null
        runCatching {
            val rel = Json.parseToJsonElement(get("https://api.github.com/repos/$REPO/releases/latest")).jsonObject
            latestTag = rel.str("tag_name")
            url = rel.str("html_url")
        }
        val tags = Json.parseToJsonElement(get("https://api.github.com/repos/$REPO/tags?per_page=100")).jsonArray
        var newest: String? = null
        for (t in tags) {
            val name = (t as? JsonObject)?.str("name") ?: continue
            if (newest == null || compareVersions(name, newest) > 0) newest = name
        }
        val n = newest
        val l = latestTag
        if (n != null && (l == null || compareVersions(n, l) > 0) && compareVersions(n, UPSTREAM_VERSION) > 0) {
            return@withContext UpdateInfo(
                n, "https://github.com/$REPO/tree/$n",
                "На GitHub обнаружен более новый tag $n. Похоже, опубликованный release ещё не догнал его.", newTag = true,
            )
        }
        if (l != null && compareVersions(l, UPSTREAM_VERSION) > 0) {
            return@withContext UpdateInfo(
                l, url ?: "https://github.com/$REPO/releases/latest",
                "Вышла новая версия приложения $l. Можно открыть страницу релиза и обновиться вручную.",
            )
        }
        UpdateInfo(l, null, "У вас последняя версия: $UPSTREAM_VERSION")
    }

    fun compareVersions(a: String, b: String): Int {
        fun parse(v: String) = Regex("\\d+").findAll(v).map { it.value.toInt() }.toList()
        val pa = parse(a)
        val pb = parse(b)
        for (i in 0 until 4) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}


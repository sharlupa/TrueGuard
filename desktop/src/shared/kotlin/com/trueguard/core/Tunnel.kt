package com.trueguard.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

enum class Phase(val title: String) {
    Idle("Нажмите для старта"),
    Checking("Проверка конфигурации..."),
    Connecting("Подключение..."),
    Handshake("Установка туннеля..."),
    WaitingWorkers("Ожидание воркеров..."),
    Connected("Подключено"),
    Reconnecting("Восстановление соединения..."),
    WaitingNetwork("Ожидание сети..."),
    Stopping("Остановка сервиса...");

    val active get() = this != Idle
}

data class SpeedSample(val up: Double, val down: Double)

/**
 * Запуск ядра wdtt-client и разбор его событий: аналог TunnelService из Android.
 * Вся логика выполняется в главном потоке (Dispatchers.Main), как цикл событий во Flutter.
 */
object TunnelController {
    private val settings get() = Settings.instance
    private val log = LogStore
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var phase by mutableStateOf(Phase.Idle); private set
    var lastError by mutableStateOf<String?>(null)
    var protectionLost by mutableStateOf(false) // баннер «Защита отключена!»
    var captchaPending by mutableStateOf(false); private set
    var tunnelUp by mutableStateOf(false); private set
    var tunnelInfo by mutableStateOf(""); private set
    var serverName by mutableStateOf<String?>(null); private set

    var activeWorkers by mutableIntStateOf(0); private set
    var totalWorkers by mutableIntStateOf(0); private set
    var bytesUp by mutableLongStateOf(0); private set
    var bytesDown by mutableLongStateOf(0); private set
    var connectedSince by mutableStateOf<Instant?>(null); private set
    var speed by mutableStateOf(listOf<SpeedSample>()); private set
    /** Тик раз в 3 с для таймера сессии. */
    var tick by mutableIntStateOf(0); private set
    const val SPEED_WINDOW = 120

    private var proc: Process? = null
    private var procStdin: OutputStreamWriter? = null
    var tunnelEverUp = false; private set // ядро хоть раз прислало STARTED в этом подключении
    private var userStop = false
    private var authFailed = false
    private var privFailed = false // права администратора не получены — не перезапускаем
    private var sudoPassword: String? = null // только в памяти, пока открыто приложение: для автопереподключения

    fun forgetRootPassword() {
        sudoPassword = null
    }

    /** Окно запроса пароля администратора (регистрирует GUI). null — отмена. */
    var askRootPassword: (suspend () -> String?)? = null

    private var hashDead = false
    private var usingSecondary = false
    private var restarts = 0
    private var lastRestart: Instant? = null
    private var zeroSince: Instant? = null
    private var routeSig: String? = null
    private var watchdog: Job? = null
    private var retryJob: Job? = null
    private var runConf: File? = null
    private var generation = 0

    val upSpeed get() = speed.lastOrNull()?.up ?: 0.0
    val downSpeed get() = speed.lastOrNull()?.down ?: 0.0
    val sessionSeconds: Long
        get() = connectedSince?.let { java.time.Duration.between(it, Instant.now()).seconds } ?: 0

    private fun enter(p: Phase) {
        if (phase == p) return
        phase = p
        // Android: служба VPN в переднем плане живёт, пока туннель активен
        if (p == Phase.Idle) Platform.hooks.onTunnelActive(false)
        if (p == Phase.Connected) {
            if (connectedSince == null) connectedSince = Instant.now()
            restarts = 0
        }
    }

    // ───────────── запуск ─────────────

    fun toggle() {
        if (phase.active) scope.launch { stop() } else connect()
    }

    fun connect() {
        scope.launch { connectNow() }
    }

    suspend fun connectNow() = withContext(Dispatchers.Main) {
        if (phase.active && phase != Phase.WaitingNetwork) return@withContext
        userStop = false
        authFailed = false
        privFailed = false
        hashDead = false
        usingSecondary = false
        restarts = 0
        lastError = null
        protectionLost = false
        connectedSince = null
        speed = emptyList()
        bytesUp = 0
        bytesDown = 0
        routeSig = defaultRouteSignature()
        tunnelEverUp = false
        startWatchdog()
        launchCore()
    }

    private suspend fun launchCore() {
        val gen = ++generation
        enter(Phase.Checking)
        val s = settings
        val server = s.activeServer
        val hashes = if (usingSecondary) listOf(Settings.normalizeHash(s.secondaryVkHash)) else s.allHashes
        if (server == null || hashes.isEmpty() || hashes.first().isEmpty()) {
            return fail("Сначала выберите сервер и добавьте VK хеши в настройках!")
        }
        val password = s.effectivePassword(server)
        if (password.isEmpty()) return fail("Пароль подключения не указан")
        if (hashes.any { it.length < 16 }) return fail("Хеш слишком короткий, нужно минимум 16 символов.")
        val core = Paths.findCore() ?: return fail("Ошибка: Бинарный файл не найден (wdtt-client)")
        // Android: туннель всегда системный, через VpnService (режим ядра tunfd)
        val android = Platform.isAndroid
        val tun = android || s.tunnelMode == "tun"
        var rootMethod: String? = null
        var password0: String? = null
        if (android) {
            if (!Platform.hooks.prepareVpn()) return fail("VPN-разрешение не выдано")
            if (gen != generation) return
            Platform.hooks.onTunnelActive(true)
        } else if (tun && Os.isWindows) {
            // в Windows туннель поднимает WireGuard для Windows; права ядро запросит само (UAC)
            if (WindowsTools.wireGuard() == null) {
                return fail("Для режима TUN установите WireGuard для Windows (wireguard.com/install) или выберите режим «Прокси» в настройках сети.")
            }
        } else if (tun) {
            if (Paths.which("wg-quick") == null) {
                return fail("Для режима TUN нужен wg-quick (пакет wireguard-tools). Либо выберите режим «Прокси» в настройках сети.")
            }
            if (s.excludedApps.isNotEmpty() && Paths.which("nft") == null) {
                return fail("Для исключений приложений нужен nft (пакет nftables).")
            }
            val plan = RootAccess.plan(core, s.rootMethod)
            if (gen != generation) return
            rootMethod = plan.method ?: return fail("Для режима TUN нужен sudo или pkexec (polkit).")
            if (rootMethod == "sudo" && plan.prompt == RootPrompt.App) {
                password0 = sudoPassword
                if (password0 == null) {
                    val ask = askRootPassword ?: return fail("Нужен пароль администратора для режима TUN")
                    password0 = ask()
                    if (gen != generation) return
                    if (password0 == null) return fail("Запрос прав администратора отменён")
                }
            }
        }

        serverName = server.name
        tunnelUp = false
        tunnelInfo = ""
        activeWorkers = 0
        zeroSince = null

        var peer = server.peer
        if (s.manualPortsEnabled) peer = "${server.ip.split(':').first()}:${s.serverDtlsPort}"
        val workers = s.totalWorkers(hashes.size)
        val conf = buildMap {
            put("mode", if (android) "tunfd" else s.tunnelMode)
            put("peer", peer)
            put("vk", hashes.joinToString(","))
            put("password", password)
            put("n", "$workers")
            put("listen", "127.0.0.1:${s.listenPort}")
            // «Ручной» режим браузера — сразу окно Chromium; «Авто» — сначала решатели ядра (RJS отключён в upstream)
            put("captcha-mode", if (s.captchaWbvSolveMethod == "manual") "wv" else "auto")
            put("vk-auth-mode", s.vkAuthMode)
            put("obfs", s.obfsMode)
            put("fingerprint", s.vkFingerprint)
            if (android) put("captcha-app", "true") // капчу показывает WebView приложения
            Platform.hooks.deviceId?.takeIf { it.isNotBlank() }?.let { put("device-id", it) }
            if (s.customMtu >= 1280) put("wg-mtu", "${s.customMtu}")
            s.effectiveDns?.let { put("wg-dns", it) }
            s.browserPath.trim().takeIf { it.isNotEmpty() }?.let { put("browser", it) }
            s.bindIface.trim().takeIf { it.isNotEmpty() }?.let { put("bind-iface", it) }
            if (!tun) put("proxy", s.proxyListen.trim())
            if (tun && !android) {
                put("tun-dns", "${s.tunDns}")
                put("block-ipv6", "${s.blockIpv6}")
                if (s.excludeAddrs.isNotBlank()) put("exclude", oneLine(s.excludeAddrs))
                if (s.includeAddrs.isNotBlank()) put("include", oneLine(s.includeAddrs))
                if (s.excludedApps.isNotEmpty() && !Os.isWindows) {
                    put("apps", s.excludedApps.joinToString(",") { it.key })
                    put("apps-mode", if (s.isWhitelist) "only" else "bypass")
                }
            }
        }
        // пароль не должен светиться в списке процессов — передаём файлом с правами 0600
        val confFile = withContext(Dispatchers.IO) {
            val dir = File(Paths.runtimeDir)
            Paths.makePrivateDir(dir)
            val f = File(dir, "run-$gen.conf")
            Paths.createPrivateFile(f)
            f.writeText(conf.entries.joinToString("\n") { "${it.key} = ${it.value}" })
            f
        }
        runConf = confFile

        val args = listOf(core, "-c", confFile.path, "-events", "-stdin-control")
        log.event(
            "Запуск ядра: режим ${if (android) "VPN" else if (tun) "TUN (вся система)" else "прокси ${s.proxyListen}"}, сервер ${server.name}, хешей ${hashes.size}, потоков $workers",
            key = "launch",
        )
        if (usingSecondary) log.event("Используется запасной хеш", key = "hash_switch")

        val p = try {
            withContext(Dispatchers.IO) {
                when {
                    !tun || android -> ProcessBuilder(args).start()
                    rootMethod == "pkexec" -> ProcessBuilder(listOf("pkexec") + args).start()
                    else -> RootAccess.startSudo((if (password0 != null) listOf("-S", "-p", "") else listOf("-n")) + "--" + args)
                }
            }
        } catch (e: Exception) {
            return fail("Ошибка запуска: ${e.message}")
        }
        val stdin = OutputStreamWriter(p.outputStream, Charsets.UTF_8)
        if (password0 != null) {
            sudoPassword = password0
            // sudo -S читает ровно одну строку, остальное stdin достаётся ядру
            withContext(Dispatchers.IO) { runCatching { stdin.write(password0 + "\n"); stdin.flush() } }
        }
        if (tun && !android) log.event("Права администратора: ${if (rootMethod == "pkexec") "pkexec (polkit)" else "sudo"}", key = "root_method")
        if (gen != generation) {
            p.destroy()
            return
        }
        proc = p
        procStdin = stdin
        enter(Phase.Connecting)
        readLines(p.inputStream) { onStdout(it) }
        readLines(p.errorStream) { onStderr(it) }
        scope.launch {
            val code = withContext(Dispatchers.IO) { p.waitFor() }
            delay(50) // дочитать хвост вывода
            onExit(p, gen, code)
        }
    }

    /** Строка управления в stdin ядра (STOP, ответ капчи). */
    private suspend fun sendControl(line: String) = withContext(Dispatchers.IO) {
        runCatching {
            procStdin?.write(line + "\n")
            procStdin?.flush()
        }
    }

    private fun readLines(stream: java.io.InputStream, handler: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (l in lines) withContext(Dispatchers.Main) { handler(l) }
                }
            }
        }
    }

    private fun oneLine(v: String) = v.split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }.joinToString(",")

    private fun fail(msg: String) {
        lastError = msg
        log.event(msg, key = "start_error", error = true)
        stopWatchdog()
        enter(Phase.Idle)
    }

    private fun deleteRunConf() {
        val f = runConf ?: return
        runConf = null
        f.delete()
    }

    // ───────────── вывод ядра ─────────────

    private fun onStdout(line: String) {
        if (!line.startsWith("__WDTT_EVENT__|")) {
            if (line.isNotBlank()) log.addRaw(line)
            return
        }
        val parts = line.split('|')
        if (parts.size < 2) return
        val payload = parts.drop(2).joinToString("|")
        val p = if (payload.isEmpty()) JsonObject(emptyMap()) else runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        onEvent(parts[1], p)
    }

    private fun onEvent(type: String, p: JsonObject) {
        when (type) {
            "STARTED" -> {
                tunnelEverUp = true
                deleteRunConf()
                totalWorkers = p.int("workers", totalWorkers)
            }
            "READY" -> if (!tunnelUp) enter(Phase.WaitingWorkers)
            "TUNNEL_UP" -> {
                tunnelUp = true
                val proxy = p.str("mode") == "proxy"
                val address = p.str("address") ?: ""
                tunnelInfo = when {
                    proxy -> "SOCKS5/HTTP ${p.str("proxy")}"
                    p.str("mode") == "tunfd" -> "VPN · $address"
                    else -> "TUN wdtt0 · $address"
                }
                log.event(
                    when {
                        proxy -> "[ПРОКСИ] Прокси готов: socks5h://${p.str("proxy")}"
                        p.str("mode") == "tunfd" -> "[VPN] Туннель поднят ($address)"
                        else -> "[TUN] Системный туннель поднят ($address)"
                    },
                    key = "tunnel_up",
                )
                enter(if (activeWorkers > 0) Phase.Connected else Phase.WaitingWorkers)
            }
            "STATS" -> {
                val up = p.long("bytes_up", 0)
                val down = p.long("bytes_down", 0)
                val dUp = if (up >= bytesUp) up - bytesUp else 0
                val dDown = if (down >= bytesDown) down - bytesDown else 0
                bytesUp = up
                bytesDown = down
                activeWorkers = p.int("active", 0)
                totalWorkers = p.int("workers", totalWorkers)
                speed = (speed + SpeedSample(dUp.toDouble(), dDown.toDouble())).takeLast(SPEED_WINDOW)
                if (activeWorkers > 0) {
                    zeroSince = null
                    if (tunnelUp) enter(Phase.Connected)
                } else if (zeroSince == null) zeroSince = Instant.now()
            }
            "ERROR" -> {
                val code = p.str("code") ?: ""
                val msg = p.str("message") ?: ""
                val fatal = p.bool("fatal", false)
                when {
                    code == "AUTH" -> {
                        authFailed = true
                        var reason = "Неверный пароль подключения или несовместимый WRAP"
                        if (msg.contains("device_mismatch") || msg.contains("другому устройству")) reason = "Пароль привязан к другому устройству"
                        if (msg.contains("expired") || msg.contains("истёк")) reason = "Срок действия пароля истёк"
                        lastError = "🔒 $reason. Воркеры остановлены."
                        log.event(lastError!!, key = "auth", error = true)
                    }
                    code == "HASH_DEAD" -> {
                        hashDead = true
                        log.event("Хеш VK недействителен: $msg", key = "hash_dead", error = true)
                    }
                    fatal -> {
                        lastError = "[$code] $msg"
                        log.event(lastError!!, key = "fatal_$code", error = true)
                    }
                    else -> log.event("[$code] $msg", key = "err_$code", error = true)
                }
            }
            "CAPTCHA_REQUEST" -> {
                captchaPending = true
                // Android: капча в WebView приложения, ответ — строкой в stdin ядра
                if (p.str("url") != null) scope.launch {
                    val id = p.int("id", 0)
                    val token = Platform.hooks.solveCaptcha(p)
                    sendControl(if (token.isNullOrBlank()) "CAPTCHA_CANCEL $id" else "CAPTCHA_RESULT $id $token")
                }
            }
            "TUN_REQUEST" -> scope.launch {
                // Android: поднять VpnService с адресами из конфига и передать ядру дескриптор
                if (!Platform.hooks.establishTun(p)) {
                    lastError = "Не удалось поднять VPN-интерфейс"
                    log.event(lastError!!, key = "tun_error", error = true)
                }
            }
            "CAPTCHA_DONE" -> {
                captchaPending = false
                val ok = p.bool("success", false)
                log.event(if (ok) "[КАПЧА] Капча решена ✓" else "[КАПЧА] Ошибка решения капчи", key = "captcha_done", error = !ok)
            }
        }
    }

    private fun onStderr(rawLine: String) {
        val line = stripTimestamp(rawLine)
        if (line.isBlank()) return
        val h = humanize(line)
        log.addRaw(line, error = h?.error ?: false)
        if (h != null) log.event(h.text, key = h.key, error = h.error)

        if (line.contains("Запрос кредов") && phase == Phase.Checking) enter(Phase.Connecting)
        if (line.contains("[DTLS] Рукопожатие") && (phase == Phase.Connecting || phase == Phase.Checking)) enter(Phase.Handshake)
        if (line.contains("Открыто окно браузера")) {
            captchaPending = true
            scope.launch {
                Desktop.notify(
                    "Требуется подтверждение капчи",
                    "ВК запросил проверку безопасности. Решите капчу в открывшемся окне браузера.",
                    urgency = "critical", replaceTag = "trueguard-captcha",
                )
            }
        }
        checkPrivileges(line)
    }

    // ───────────── права администратора ─────────────

    private fun checkPrivileges(line: String) {
        val err = when {
            line.contains("Sorry, try again") || line.contains("incorrect password") -> {
                sudoPassword = null
                runCatching { procStdin?.close() } // иначе sudo ждёт следующую попытку
                "Неверный пароль администратора"
            }
            line.contains("a password is required") || line.contains("no password was provided") -> {
                sudoPassword = null
                "sudo не получил пароль"
            }
            line.contains("is not in the sudoers") || line.contains("not allowed to execute") ->
                "Пользователю запрещён sudo — выберите pkexec в Настройки → Система"
            line.contains("No authentication agent") -> "Нет агента polkit — выберите «Авто» или sudo в Настройки → Система"
            line.contains("Request dismissed") -> "Запрос прав администратора отменён"
            line.contains("Not authorized") || line.contains("No session for cookie") -> "polkit не выдал права администратора"
            else -> null
        }
        if (err != null && !tunnelEverUp) {
            privFailed = true
            lastError = err
            log.event(err, key = "root_error", error = true)
        }
    }

    // ───────────── завершение и перезапуск ─────────────

    private fun onExit(p: Process, gen: Int, code: Int) {
        if (p !== proc) return
        proc = null
        procStdin = null
        deleteRunConf()
        tunnelUp = false
        activeWorkers = 0
        captchaPending = false
        if (gen != generation) return

        if (userStop) {
            log.event("Туннель остановлен", key = "stopped")
            stopWatchdog()
            enter(Phase.Idle)
            return
        }
        if (phase == Phase.WaitingNetwork) return // остановлен из-за потери сети

        val s = settings
        log.event("Процесс остановлен: код $code", key = "exit", error = code != 0)

        if (privFailed || (!tunnelEverUp && (code == 126 || code == 127))) {
            if (lastError == null) lastError = if (code == 126) "Запрос прав администратора отменён" else "Права администратора не выданы"
            return halt(notify = false)
        }
        if (authFailed) return halt()
        if (hashDead) {
            hashDead = false
            if (!usingSecondary && Settings.normalizeHash(s.secondaryVkHash).length >= 16) {
                usingSecondary = true
                log.event("Основной хеш мертв. Переключение на запасной...", key = "hash_switch", error = true)
                return relaunch(1000)
            }
            lastError = if (usingSecondary) "Запасной хеш тоже мертв. Отключение." else "Хеш умер, запасного нет. Отключение."
            log.event(lastError!!, key = "hash_switch", error = true)
            return halt()
        }
        if (!s.autoReconnect) {
            if (lastError == null) lastError = "Соединение с туннелем было разорвано."
            return halt()
        }
        // предохранитель: много перезапусков подряд без успешного подключения
        val now = Instant.now()
        if (lastRestart != null && java.time.Duration.between(lastRestart, now).toMinutes() >= 5) restarts = 0
        restarts++
        lastRestart = now
        if (restarts > 6) {
            lastError = "Слишком много перезапусков подряд — проверьте сервер, хеши и сеть."
            log.event("[СТОП] $lastError", key = "circuit_breaker", error = true)
            return halt()
        }
        val delaySec = (2 shl (restarts - 1)).coerceIn(2, 30)
        log.event("⚠ Процесс упал. Перезапуск через $delaySec с...", key = "crash_restart", error = true)
        relaunch(delaySec * 1000L)
    }

    private fun relaunch(delayMs: Long) {
        enter(Phase.Reconnecting)
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            if (userStop || phase != Phase.Reconnecting) return@launch
            launchCore()
        }
    }

    private fun halt(notify: Boolean = true) {
        stopWatchdog()
        protectionLost = connectedSince != null
        connectedSince = null
        enter(Phase.Idle)
        if (notify && settings.notifyDisconnects) {
            scope.launch {
                val a = Desktop.notify(
                    "Защита отключена!", lastError ?: "Соединение с туннелем было разорвано.",
                    actions = mapOf("reconnect" to "Переподключиться"), urgency = "critical", replaceTag = "trueguard-state",
                )
                if (a == "reconnect" && phase == Phase.Idle) connect()
            }
        }
    }

    private suspend fun restartCore(reason: String) {
        log.event(reason, key = "soft_restart", error = true)
        val p = proc
        generation++
        proc = null
        if (p != null) terminate(p, procStdin)
        procStdin = null
        deleteRunConf()
        tunnelUp = false
        activeWorkers = 0
        relaunch(1000)
    }

    suspend fun stop() = withContext(Dispatchers.Main) {
        userStop = true
        retryJob?.cancel()
        stopWatchdog()
        val p = proc
        if (p == null) {
            generation++
            enter(Phase.Idle)
            return@withContext
        }
        enter(Phase.Stopping)
        terminate(p, procStdin)
    }

    private suspend fun terminate(p: Process, stdin: OutputStreamWriter?) = withContext(Dispatchers.IO) {
        runCatching {
            stdin?.write("STOP\n")
            stdin?.flush()
            stdin?.close()
        }
        if (!p.waitFor(12, java.util.concurrent.TimeUnit.SECONDS)) {
            // процесс под pkexec принадлежит root — сигнал дойдёт только до своего
            p.destroy()
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }

    /** Остановка при выходе из приложения. */
    suspend fun shutdown() {
        if (proc != null) stop()
    }

    // ───────────── сторож: «зомби» и смена сети ─────────────

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (isActive) {
                delay(3000)
                watchdogTick()
            }
        }
    }

    private fun stopWatchdog() {
        watchdog?.cancel()
        watchdog = null
        retryJob?.cancel()
    }

    private suspend fun watchdogTick() {
        val sig = defaultRouteSignature()
        if (sig != routeSig) {
            val old = routeSig
            routeSig = sig
            if (sig == null) {
                if (proc != null || phase == Phase.Reconnecting) {
                    log.event("Сеть потеряна, приостанавливаем туннель", key = "network", error = true)
                    val p = proc
                    val stdin = procStdin
                    generation++
                    proc = null
                    retryJob?.cancel()
                    enter(Phase.WaitingNetwork)
                    if (p != null) scope.launch { terminate(p, stdin) }
                }
                return
            }
            if (phase == Phase.WaitingNetwork) {
                log.event("Сеть появилась, возобновляем туннель", key = "network")
                launchCore()
                return
            }
            if (old != null && proc != null) {
                restartCore("[СЕТЬ] Перезапуск транспорта из-за смены сети...")
                return
            }
        }
        val zs = zeroSince
        if (proc != null && tunnelUp && zs != null && java.time.Duration.between(zs, Instant.now()).seconds > 90) {
            zeroSince = null
            restartCore("⚠ Зомби-процесс (0 воркеров 90с). Перезапуск...")
        }
        tick++
    }

    /** Для скриншотов и тестов интерфейса: подставить состояние без запуска ядра. */
    internal fun simulate(phase: Phase, workers: Int = 0, total: Int = 0, up: Long = 0, down: Long = 0, samples: List<SpeedSample> = emptyList(), seconds: Long = 0) {
        this.phase = phase
        tunnelUp = phase == Phase.Connected || phase == Phase.WaitingWorkers
        activeWorkers = workers
        totalWorkers = total
        bytesUp = up
        bytesDown = down
        speed = samples
        connectedSince = if (tunnelUp) Instant.now().minusSeconds(seconds) else null
    }

    /** Подпись сети без /proc: работающие интерфейсы с адресами, кроме петли и самого туннеля. */
    private fun interfacesSignature(): String? = runCatching {
        (java.net.NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList())
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && it.name != "wdtt0" && !it.name.startsWith("tun") && !it.displayName.contains("WireGuard", true) }
            .mapNotNull { ni ->
                val addrs = ni.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>().map { it.hostAddress }.sorted()
                if (addrs.isEmpty()) null else "${ni.name}=${addrs.joinToString(",")}"
            }
            .sorted().joinToString(";").ifEmpty { null }
    }.getOrDefault("unknown")

    /** Интерфейс и шлюз маршрута по умолчанию из таблицы main (wg-quick её не меняет). */
    fun defaultRouteSignature(): String? = if (Os.isWindows || Platform.isAndroid) interfacesSignature() else try {
        var best: String? = null
        var bestMetric = Int.MAX_VALUE
        File("/proc/net/route").readLines().drop(1).forEach { l ->
            val f = l.trim().split(Regex("\\s+"))
            if (f.size < 8 || f[1] != "00000000" || f[7] != "00000000") return@forEach
            val flags = f[3].toIntOrNull(16) ?: 0
            if (flags and 1 == 0) return@forEach
            val metric = f[6].toIntOrNull() ?: 0
            if (metric < bestMetric) {
                bestMetric = metric
                best = "${f[0]}/${f[2]}"
            }
        }
        best
    } catch (_: Exception) {
        "unknown"
    }
}

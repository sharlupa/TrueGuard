package com.trueguard.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

const val UPSTREAM_VERSION = "1.2.4"

/** Linux, Windows или Android — в «О приложении», отчёте устройства и User-Agent. */
val PLATFORM_NAME: String get() = when {
    Platform.isAndroid -> "Android"
    Os.isWindows -> "Windows"
    else -> "Linux"
}
val APP_VERSION: String get() = "$UPSTREAM_VERSION-${PLATFORM_NAME.lowercase()}"

/** Платформа: Linux — основная, Windows — переносная сборка с WireGuard для Windows. */
object Os {
    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")
    val exe: String = if (isWindows) ".exe" else ""
}

object Paths {
    val home: String get() = System.getProperty("user.home") ?: System.getenv("HOME") ?: "/tmp"

    /** Для тестов: настройки пишутся сюда, а не в ~/.config. */
    @Volatile var configDirOverride: String? = null

    val configDir: String
        get() = configDirOverride ?: Platform.hooks.dataDir ?: if (Os.isWindows) {
            "${System.getenv("APPDATA")?.takeIf { it.isNotEmpty() } ?: "$home\\AppData\\Roaming"}\\TrueGuard"
        } else {
            "${System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotEmpty() } ?: "$home/.config"}/trueguard"
        }

    val runtimeDir: String
        get() = Platform.hooks.dataDir?.let { "$it/run" } ?: if (Os.isWindows) {
            "${System.getenv("LOCALAPPDATA")?.takeIf { it.isNotEmpty() } ?: System.getProperty("java.io.tmpdir")}\\TrueGuard\\run"
        } else {
            "${System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotEmpty() } ?: System.getProperty("java.io.tmpdir")}/trueguard"
        }

    /**
     * Каталог установленного приложения: в Linux jpackage кладёт сюда bin/ и lib/,
     * в Windows лаунчер TrueGuard.exe передаёт свою папку свойством trueguard.appdir.
     */
    val appDir: String?
        get() = System.getProperty("trueguard.appdir")
            ?: System.getProperty("compose.application.resources.dir")?.let { File(it).parentFile?.parentFile?.path }
            ?: System.getProperty("jpackage.app-path")?.let { File(it).parentFile?.parentFile?.path }

    /** Для тестов: подменное ядро. */
    @Volatile var coreOverride: String? = null

    /** Ядро wdtt-client: рядом с приложением, в /opt/trueguard, в исходниках или в PATH. */
    fun findCore(): String? {
        coreOverride?.let { return it }
        Platform.hooks.findCore()?.let { return it }
        val name = "wdtt-client${Os.exe}"
        val candidates = buildList {
            System.getenv("TRUEGUARD_CORE")?.takeIf { it.isNotEmpty() }?.let(::add)
            appDir?.let { add("$it/$name"); add("$it/lib/$name") }
            if (!Os.isWindows) {
                add("/opt/trueguard/wdtt-client")
                add("/usr/lib/trueguard/wdtt-client")
                add("/usr/local/lib/trueguard/wdtt-client")
            }
            // запуск из исходников: desktop/ → ../linux-client
            add("${System.getProperty("user.dir")}/../linux-client/$name")
        }
        for (c in candidates) {
            val f = File(c)
            if (f.isFile) return f.canonicalPath
        }
        return which(name)
    }

    /** Для тестов: подмена системных утилит (sudo, pkexec…). */
    val toolOverride = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun which(name: String): String? {
        toolOverride[name]?.let { return it }
        val exts = if (Os.isWindows && !name.contains('.')) (System.getenv("PATHEXT") ?: ".EXE;.CMD;.BAT").split(';') else listOf("")
        for (dir in (System.getenv("PATH") ?: "/usr/bin:/bin").split(File.pathSeparatorChar)) {
            if (dir.isEmpty()) continue
            for (ext in exts) {
                val f = File(dir, name + ext.lowercase())
                if (f.isFile && (Os.isWindows || f.canExecute())) return f.path
            }
        }
        return null
    }

    /** Файл с правами только для владельца (в Windows профиль пользователя и так закрыт). */
    fun createPrivateFile(f: File) {
        f.delete()
        if (Os.isWindows) {
            f.createNewFile()
        } else {
            java.nio.file.Files.createFile(
                f.toPath(),
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")),
            )
        }
    }

    fun makePrivateDir(dir: File) {
        dir.mkdirs()
        if (!Os.isWindows) {
            runCatching {
                java.nio.file.Files.setPosixFilePermissions(dir.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            }
        }
    }
}

/** Короткий запуск утилиты: код выхода и вывод. */
data class RunResult(val code: Int, val out: String, val err: String)

suspend fun run(vararg cmd: String, timeoutSec: Long = 30, env: Map<String, String> = emptyMap()): RunResult =
    withContext(Dispatchers.IO) {
        try {
            val pb = ProcessBuilder(*cmd)
            pb.environment().putAll(env)
            val p = pb.start()
            p.outputStream.close()
            // оба потока читаются параллельно, иначе процесс встанет на заполненном буфере
            val out = CompletableFuture.supplyAsync { p.inputStream.bufferedReader().readText() }
            val err = CompletableFuture.supplyAsync { p.errorStream.bufferedReader().readText() }
            val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) p.destroyForcibly()
            fun CompletableFuture<String>.text() = runCatching { get(5, TimeUnit.SECONDS) }.getOrDefault("")
            RunResult(if (finished) p.exitValue() else -1, out.text(), err.text())
        } catch (e: Exception) {
            RunResult(-1, "", e.message ?: e.toString())
        }
    }

object Desktop {
    fun openUrl(url: String) {
        if (Platform.hooks.openUrl(url)) return
        val cmd = if (Os.isWindows) listOf("rundll32", "url.dll,FileProtocolHandler", url) else listOf("xdg-open", url)
        runCatching { ProcessBuilder(cmd).redirectErrorStream(true).start() }
    }

    /** Строка для одинарных кавычек PowerShell. */
    private fun psQuote(s: String) = "'" + s.replace("'", "''") + "'"

    /** Всплывающее уведомление Windows (без кнопок) через PowerShell и WinRT. */
    private suspend fun windowsToast(title: String, body: String) {
        val script = """
            [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
            ${'$'}t = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02)
            ${'$'}x = ${'$'}t.GetElementsByTagName('text')
            ${'$'}x.Item(0).AppendChild(${'$'}t.CreateTextNode(${psQuote(title)})) | Out-Null
            ${'$'}x.Item(1).AppendChild(${'$'}t.CreateTextNode(${psQuote(body)})) | Out-Null
            ${'$'}id = '{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\WindowsPowerShell\v1.0\powershell.exe'
            [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier(${'$'}id).Show([Windows.UI.Notifications.ToastNotification]::new(${'$'}t))
        """.trimIndent()
        run("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script, timeoutSec = 20)
    }

    /** Уведомление через notify-send. Если заданы действия — ждёт клика и возвращает ключ действия. */
    suspend fun notify(
        title: String,
        body: String,
        actions: Map<String, String> = emptyMap(),
        urgency: String = "normal",
        replaceTag: String? = null,
    ): String? {
        if (Platform.hooks.hasOwnNotifications) return Platform.hooks.notify(title, body, actions, urgency == "critical")
        if (Os.isWindows) {
            windowsToast(title, body)
            return null
        }
        if (Paths.which("notify-send") == null) return null
        val args = buildList {
            add("notify-send"); add("--app-name=TrueGuard"); add("--icon=trueguard"); add("--urgency=$urgency")
            if (replaceTag != null) add("--hint=string:x-canonical-private-synchronous:$replaceTag")
            actions.forEach { (k, v) -> add("--action=$k=$v") }
            if (actions.isNotEmpty()) add("--wait")
            add(title); add(body)
        }
        val r = withTimeoutOrNull(10 * 60_000L) { run(*args.toTypedArray(), timeoutSec = 600) } ?: return null
        return r.out.trim().ifEmpty { null }
    }

    private val autostartFile: File
        get() = File("${System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotEmpty() } ?: "${Paths.home}/.config"}/autostart/trueguard.desktop")

    /** Команда запуска этого же приложения: обёртка из install.sh, бинарник jpackage или trueguard из PATH. */
    val launchCommand: String
        get() {
            System.getProperty("trueguard.launcher")?.let { return "\"$it\"" }
            Paths.appDir?.let { dir -> File(dir, "trueguard").takeIf { it.isFile && it.canExecute() }?.let { return "\"${it.path}\"" } }
            val cmd = System.getProperty("jpackage.app-path") ?: "java"
            if (!cmd.endsWith("/java")) return "\"$cmd\""
            return if (File("/usr/local/bin/trueguard").exists()) "/usr/local/bin/trueguard" else "trueguard"
        }

    fun setAutostart(enabled: Boolean) {
        if (Os.isWindows) {
            // HKCU\...\Run — автозапуск при входе в Windows
            val key = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
            val cmd = if (enabled) listOf("reg", "add", key, "/v", "TrueGuard", "/t", "REG_SZ", "/d", "$launchCommand --minimized", "/f")
            else listOf("reg", "delete", key, "/v", "TrueGuard", "/f")
            runCatching { ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor() }
            return
        }
        val f = autostartFile
        if (!enabled) {
            f.delete()
            return
        }
        f.parentFile.mkdirs()
        f.writeText(
            """
            |[Desktop Entry]
            |Type=Application
            |Name=TrueGuard
            |Comment=WDTT VPN через TURN VK
            |Exec=$launchCommand --minimized
            |Icon=trueguard
            |Terminal=false
            |X-GNOME-Autostart-enabled=true
            |""".trimMargin()
        )
    }

    suspend fun deviceReport(): String = withContext(Dispatchers.IO) {
        Platform.hooks.deviceReport()?.let { return@withContext it }
        if (Os.isWindows) {
            val wg = WindowsTools.wireGuard()
            return@withContext listOf(
                "Версия приложения: $APP_VERSION (Android upstream $UPSTREAM_VERSION)",
                "ОС: ${System.getProperty("os.name")} ${System.getProperty("os.version")}",
                "Архитектура: ${System.getProperty("os.arch")}",
                "Java: ${System.getProperty("java.runtime.version")}",
                "Ядро WDTT: ${Paths.findCore() ?: "не найдено"}",
                "WireGuard для Windows: ${wg ?: "не установлен"}",
                "Браузер для капчи: ${WindowsTools.browser() ?: "не найден"}",
            ).joinToString("\n")
        }
        fun read(path: String) = runCatching { File(path).readText().trim() }.getOrDefault("")
        val os = Regex("^PRETTY_NAME=\"?([^\"\\n]*)\"?", RegexOption.MULTILINE).find(read("/etc/os-release"))?.groupValues?.get(1) ?: ""
        val cpu = Regex("^model name\\s*:\\s*(.*)$", RegexOption.MULTILINE).find(read("/proc/cpuinfo"))?.groupValues?.get(1) ?: ""
        val session = listOfNotNull(System.getenv("XDG_CURRENT_DESKTOP"), System.getenv("XDG_SESSION_TYPE")).joinToString(" / ")
        val tools = listOf("wg-quick", "nft", "pkexec", "sudo", "resolvconf", "chromium", "google-chrome-stable", "notify-send")
            .joinToString(" ") { "$it${if (Paths.which(it) != null) "✓" else "✗"}" }
        listOf(
            "Версия приложения: $APP_VERSION (Android upstream $UPSTREAM_VERSION)",
            "ОС: $os",
            "Ядро Linux: ${read("/proc/sys/kernel/osrelease")}",
            "Архитектура: ${System.getProperty("os.arch")}",
            "Процессор: $cpu",
            "Сеанс: $session",
            "Java: ${System.getProperty("java.runtime.version")}",
            "Ядро WDTT: ${Paths.findCore() ?: "не найдено"}",
            "Утилиты: $tools",
        ).joinToString("\n")
    }
}

/** Пути к программам Windows, которыми пользуется TrueGuard. */
object WindowsTools {
    private fun programFiles(rel: String): String? =
        listOf("ProgramFiles", "ProgramW6432", "ProgramFiles(x86)", "LOCALAPPDATA")
            .mapNotNull { System.getenv(it)?.takeIf { v -> v.isNotEmpty() } }
            .map { File(it, rel) }.firstOrNull { it.isFile }?.path

    fun wireGuard(): String? = Paths.which("wireguard.exe") ?: programFiles("WireGuard\\wireguard.exe")

    fun browser(): String? = listOf(
        "Google\\Chrome\\Application\\chrome.exe", "Microsoft\\Edge\\Application\\msedge.exe",
        "BraveSoftware\\Brave-Browser\\Application\\brave.exe", "Chromium\\Application\\chrome.exe",
    ).firstNotNullOfOrNull { programFiles(it) }

    /** Значение из реестра Windows (reg query), например тема и акцентный цвет. */
    suspend fun regValue(key: String, name: String): String? {
        val r = run("reg", "query", key, "/v", name, timeoutSec = 5)
        if (r.code != 0) return null
        return r.out.lines().firstOrNull { it.trim().startsWith(name) }?.trim()?.split(Regex("\\s+"))?.lastOrNull()
    }
}

fun formatBytes(bytes: Number): String {
    val units = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    val digits = if (i == 0) 0 else if (v < 10) 2 else 1
    return "${"%.${digits}f".format(java.util.Locale.ROOT, v)} ${units[i]}"
}

fun formatSpeed(bytesPerSec: Number) = "${formatBytes(bytesPerSec)}/с"

fun formatClock(seconds: Long): String = "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)

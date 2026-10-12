package com.trueguard.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Кто спросит пароль при запуске ядра в режиме TUN. */
enum class RootPrompt { None, Agent, App }

data class RootPlan(val method: String?, val prompt: RootPrompt, val detail: String)

/**
 * Права администратора для режима TUN.
 *
 * Если в сессии работает агент polkit (он регистрируется в polkitd по D-Bus) или polkit уже разрешает
 * действие без пароля — ядро запускается через pkexec, и TrueGuard пароль не спрашивает.
 * Если агента нет — пароль спрашивает окно TrueGuard, а ядро запускается через sudo.
 */
object RootAccess {
    const val ACTION = "com.trueguard.wdtt-client"

    private val agentNames = setOf(
        "lxpolkit", "soteria", "xfce-polkit", "mate-polkit", "gnome-shell", "cosmic-osd", "cinnamon",
        "budgie-polkit-dialog", "ukui-polkit", "hyprpolkitagent", "lxqt-policykit-agent", "io.elementary.desktop.agent-polkit",
    )

    /** Юниты systemd --user с агентами, которые можно запустить кнопкой. */
    private val agentUnits = listOf(
        "hyprpolkitagent.service", "plasma-polkit-agent.service", "polkit-gnome-authentication-agent-1.service",
        "lxqt-policykit-agent.service", "polkit-mate-authentication-agent-1.service",
    )

    /** polkit уже разрешает запуск ядра без пароля: правило «без пароля» или ещё действующий auth_admin_keep. */
    fun polkitAuthorizedNow(): Boolean {
        val pkcheck = Paths.which("pkcheck") ?: return false
        return runCatching {
            val p = ProcessBuilder(pkcheck, "--action-id", ACTION, "--process", File("/proc/self").canonicalFile.name)
                .redirectErrorStream(true).start()
            p.outputStream.close()
            p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
    }

    /** Агент polkit в сессии: запущенный процесс или занятое (не просто «активируемое») имя на шине D-Bus. */
    fun agentRunning(): Boolean = agentProcessRunning() || agentOnDbus()

    fun agentProcessRunning(): Boolean {
        val uidRe = Regex("^Uid:\\s+(\\d+)", RegexOption.MULTILINE)
        val uid = runCatching { uidRe.find(File("/proc/self/status").readText())?.groupValues?.get(1) }.getOrNull() ?: ""
        val procs = File("/proc").listFiles() ?: return false
        for (e in procs) {
            if (e.name.toIntOrNull() == null) continue
            try {
                if (uidRe.find(File(e, "status").readText())?.groupValues?.get(1) != uid) continue
                val cmd = File(e, "cmdline").readText().split('\u0000').first().substringAfterLast('/')
                if (cmd.contains("polkit-agent-helper")) continue
                if (cmd in agentNames || (cmd.contains("polkit") && cmd.contains("agent"))) return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    /** `busctl --user list`: у работающего агента есть PID, у лишь установленного — «-» (activatable). */
    fun agentOnDbus(): Boolean {
        val busctl = Paths.which("busctl") ?: return false
        return runCatching {
            val p = ProcessBuilder(busctl, "--user", "list", "--no-pager", "--no-legend").redirectErrorStream(true).start()
            p.outputStream.close()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(3, TimeUnit.SECONDS)
            out.lines().any { line ->
                val f = line.trim().split(Regex("\\s+"))
                f.size >= 2 && f[0].contains("polkit", ignoreCase = true) && f[1] != "-"
            }
        }.getOrDefault(false)
    }

    /** Установленный, но выключенный агент, который можно запустить (systemctl --user start). */
    fun startableAgentUnit(): String? {
        val systemctl = Paths.which("systemctl") ?: return null
        return runCatching {
            val p = ProcessBuilder(listOf(systemctl, "--user", "list-unit-files", "--no-legend", "--no-pager") + agentUnits)
                .redirectErrorStream(true).start()
            p.outputStream.close()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(3, TimeUnit.SECONDS)
            agentUnits.firstOrNull { u -> out.lines().any { it.trim().startsWith(u) } }
        }.getOrNull()
    }

    /** Запуск агента polkit; true — агент появился в сессии. */
    suspend fun startAgent(unit: String): Boolean = withContext(Dispatchers.IO) {
        run("systemctl", "--user", "start", unit, timeoutSec = 10)
        repeat(20) {
            if (agentRunning()) return@withContext true
            delay(250)
        }
        false
    }

    fun sudoCached(): Boolean = runCatching {
        val p = startSudo(listOf("-n", "-v"))
        p.outputStream.close()
        p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    /** Без управляющего терминала sudo ведёт себя одинаково, запущено ли приложение из терминала или из меню. */
    fun startSudo(sudoArgs: List<String>): Process {
        val sudo = Paths.which("sudo") ?: "sudo"
        val setsid = Paths.which("setsid")
        return ProcessBuilder(if (setsid != null) listOf(setsid, sudo) + sudoArgs else listOf(sudo) + sudoArgs).start()
    }

    /** Как получать права: [mode] — auto, pkexec или sudo из настроек. */
    suspend fun plan(core: String, mode: String): RootPlan = withContext(Dispatchers.IO) {
        val hasPkexec = Paths.which("pkexec") != null
        val hasSudo = Paths.which("sudo") != null
        val nopass = File(File(core).parentFile, ".polkit-nopass").exists()
        fun pkexecPlan(): RootPlan = when {
            nopass || polkitAuthorizedNow() -> RootPlan("pkexec", RootPrompt.None, "polkit уже разрешает запуск ядра — пароль не нужен")
            agentRunning() -> RootPlan("pkexec", RootPrompt.Agent, "Агент polkit запущен — пароль спросит он, а не TrueGuard")
            else -> RootPlan("pkexec", RootPrompt.Agent, "Агент polkit не найден — pkexec может не получить пароль")
        }
        fun sudoPlan(): RootPlan =
            if (sudoCached()) RootPlan("sudo", RootPrompt.None, "sudo помнит недавно введённый пароль")
            else RootPlan("sudo", RootPrompt.App, "Агента polkit нет — пароль спросит окно TrueGuard и будет храниться только в памяти")
        when (mode) {
            "pkexec" -> if (hasPkexec) pkexecPlan() else RootPlan(null, RootPrompt.App, "pkexec не установлен")
            "sudo" -> if (hasSudo) sudoPlan() else RootPlan(null, RootPrompt.App, "sudo не установлен")
            else -> when {
                hasPkexec && (nopass || polkitAuthorizedNow() || agentRunning()) -> pkexecPlan()
                hasSudo -> sudoPlan()
                hasPkexec -> pkexecPlan()
                else -> RootPlan(null, RootPrompt.App, "Нет ни sudo, ни pkexec — режим TUN недоступен")
            }
        }
    }
}

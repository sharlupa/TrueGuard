package com.trueguard

import androidx.compose.runtime.snapshotFlow
import com.trueguard.core.LogStore
import com.trueguard.core.Paths
import com.trueguard.core.Phase
import com.trueguard.core.Settings
import com.trueguard.core.TunnelController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.File
import java.io.OutputStreamWriter

/**
 * Значок в трее — замена плитки быстрых настроек, виджета и уведомления Android.
 * AWT SystemTray в Wayland/SNI-панелях не работает, поэтому трей рисует маленький помощник
 * trueguard-tray (Go, StatusNotifierItem по D-Bus), а мы обмениваемся с ним строками.
 */
object Tray {
    @Volatile var available = false; private set
    private var proc: Process? = null
    private var out: OutputStreamWriter? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun findHelper(): String? {
        System.getenv("TRUEGUARD_TRAY")?.takeIf { File(it).canExecute() }?.let { return it }
        val dirs = listOfNotNull(
            Paths.appDir, Paths.appDir?.let { "$it/lib" }, Paths.findCore()?.let { File(it).parent },
            "/opt/trueguard", "${System.getProperty("user.dir")}/tray",
        )
        val name = "trueguard-tray${com.trueguard.core.Os.exe}"
        return dirs.map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }?.path ?: Paths.which(name)
    }

    suspend fun start(onShow: () -> Unit, onQuit: () -> Unit) {
        val helper = findHelper() ?: run {
            LogStore.addRaw("[ТРЕЙ] помощник trueguard-tray не найден — значка в трее не будет")
            return
        }
        val p = withContext(Dispatchers.IO) { runCatching { ProcessBuilder(helper).redirectError(ProcessBuilder.Redirect.DISCARD).start() }.getOrNull() } ?: return
        proc = p
        out = OutputStreamWriter(p.outputStream, Charsets.UTF_8)
        scope.launch(Dispatchers.IO) {
            p.inputStream.bufferedReader().useLines { lines ->
                for (l in lines) withContext(Dispatchers.Main) {
                    when (l.trim()) {
                        "ready" -> available = true
                        "unavailable" -> available = false
                        "activate", "click show" -> onShow()
                        "click toggle" -> TunnelController.toggle()
                        "click quit" -> onQuit()
                    }
                }
            }
            withContext(Dispatchers.Main) { available = false }
        }
        // состояние туннеля → значок и меню
        scope.launch {
            snapshotFlow { Triple(TunnelController.phase, Settings.instance.activeServer?.name, TunnelController.activeWorkers > 0) }
                .collectLatest { (phase, server, _) ->
                    send("icon " + when (phase) {
                        Phase.Connected -> "on"
                        Phase.Idle -> "off"
                        else -> "wait"
                    })
                    send("status TrueGuard: " + if (phase.active) phase.title else "Отключено")
                    send("server " + (server ?: "сервер не выбран"))
                    send("toggle " + if (phase.active) "Отключить" else "Подключить")
                }
        }
    }

    private suspend fun send(line: String) = withContext(Dispatchers.IO) {
        runCatching {
            out?.write(line.replace('\n', ' ') + "\n")
            out?.flush()
        }
    }

    suspend fun stop() {
        send("quit")
        withContext(Dispatchers.IO) {
            proc?.let { if (!it.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) it.destroy() }
        }
        available = false
    }
}

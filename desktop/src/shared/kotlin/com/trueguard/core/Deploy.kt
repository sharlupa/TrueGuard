package com.trueguard.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Вкладка «Деплой»: установка и удаление сервера WDTT по SSH (wdtt-client deploy). */
object DeployController {
    var running by mutableStateOf(false); private set
    var action by mutableStateOf(""); private set
    var progress by mutableFloatStateOf(0f); private set
    var stage by mutableStateOf(""); private set
    var success by mutableStateOf<Boolean?>(null); private set
    var resultMessage by mutableStateOf(""); private set
    var lines by mutableStateOf(listOf<String>()); private set
    private var proc: Process? = null
    private var cancelled = false

    private fun line(s: String) {
        lines = (lines + s).takeLast(1500)
        LogStore.addRaw("[ДЕПЛОЙ] $s")
    }

    fun validate(act: String): String? {
        val s = Settings.instance
        if (s.deployIp.isBlank()) return "Укажите IP сервера"
        if (s.deployPassword.isEmpty()) return "Укажите пароль SSH"
        if (act == "install") {
            if (s.deployMainPassword.isBlank()) return "Обязательно задайте секреты перед первой установкой сервера."
            if (!Regex("^[A-Za-z0-9._@:+=,!#%^*~-]{4,128}$").matches(s.deployMainPassword)) {
                return "Пароль туннеля: 4–128 символов, латиница, цифры и ._@:+=,!#%^*~-"
            }
            if (s.deployAdminId.isNotEmpty() && !Regex("^\\d{1,20}$").matches(s.deployAdminId)) return "ID админа — только цифры"
            if (s.deployBotToken.isNotEmpty() && !Regex("^\\d{3,20}:[A-Za-z0-9_-]{20,100}$").matches(s.deployBotToken)) {
                return "Неверный формат токена бота"
            }
        }
        return null
    }

    fun run(act: String) {
        TunnelController.scope.launch { runNow(act) }
    }

    suspend fun runNow(act: String) = withContext(Dispatchers.Main) {
        if (running) return@withContext
        val s = Settings.instance
        validate(act)?.let {
            success = false
            resultMessage = it
            return@withContext
        }
        val core = Paths.findCore()
        if (core == null) {
            success = false
            resultMessage = "Файлы не найдены: нет wdtt-client"
            return@withContext
        }
        running = true
        cancelled = false
        action = act
        progress = 0f
        stage = "Запуск..."
        success = null
        resultMessage = ""
        lines = emptyList()

        val dtls = if (s.manualPortsEnabled) s.serverDtlsPort else 56000
        val wg = if (s.manualPortsEnabled) s.serverWgPort else 56001
        val host = s.deployIp.trim()
        val p = try {
            withContext(Dispatchers.IO) {
                val pb = ProcessBuilder(
                    core, "deploy", act,
                    "-host", host,
                    "-ssh-port", "${s.deploySshPort}",
                    "-user", s.deployLogin.trim().ifEmpty { "root" },
                    "-dtls-port", "$dtls",
                    "-wg-port", "$wg",
                )
                pb.environment().putAll(
                    mapOf(
                        "WDTT_SSH_PASSWORD" to s.deployPassword,
                        "WDTT_TUNNEL_PASSWORD" to s.deployMainPassword,
                        "WDTT_ADMIN_ID" to s.deployAdminId.trim(),
                        "WDTT_BOT_TOKEN" to s.deployBotToken.trim(),
                    )
                )
                pb.start().also { it.outputStream.close() }
            }
        } catch (e: Exception) {
            running = false
            success = false
            resultMessage = "Критическая ошибка: ${e.message}"
            return@withContext
        }
        proc = p
        var doneOk: Boolean? = null
        var doneMsg = ""
        val out = launch(Dispatchers.IO) {
            p.inputStream.bufferedReader().useLines { seq ->
                for (l in seq) withContext(Dispatchers.Main) {
                    if (!l.startsWith("__WDTT_EVENT__|")) {
                        if (l.isNotBlank()) line(l)
                        return@withContext
                    }
                    val parts = l.split('|')
                    val payload = parts.drop(2).joinToString("|")
                    val ev = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrDefault(JsonObject(emptyMap()))
                    when (parts.getOrNull(1)) {
                        "DEPLOY_PROGRESS" -> {
                            progress = (ev.str("progress")?.toFloatOrNull() ?: progress).coerceIn(0f, 1f)
                            stage = ev.str("text") ?: stage
                        }
                        "DEPLOY_DONE" -> {
                            doneOk = ev.bool("ok", false)
                            doneMsg = ev.str("message") ?: ""
                        }
                    }
                }
            }
        }
        val err = launch(Dispatchers.IO) {
            p.errorStream.bufferedReader().useLines { seq ->
                for (l in seq) {
                    val t = stripTimestamp(l).replaceFirst(Regex("^\\d{2}:\\d{2}:\\d{2}\\s"), "")
                    if (t.isNotBlank()) withContext(Dispatchers.Main) { line(t) }
                }
            }
        }
        val code = withContext(Dispatchers.IO) { p.waitFor() }
        out.join()
        err.join()
        proc = null
        running = false
        if (cancelled) {
            success = false
            resultMessage = "Установка отменена пользователем"
            line("[!] Установка отменена пользователем")
        } else {
            val ok = doneOk ?: (code == 0)
            success = ok
            resultMessage = doneMsg.ifEmpty { if (ok) "Готово" else "Ошибка деплоя! Проверьте Логи." }
            if (ok && act == "install") rememberServer(host, s.deployMainPassword, dtls, wg)
            if (ok && act == "uninstall") progress = 1f
        }
        LogStore.event("[ДЕПЛОЙ] $resultMessage", key = "deploy_result", error = success != true)
    }

    /** После установки сервер сразу появляется в списке и становится активным. */
    private fun rememberServer(host: String, password: String, dtls: Int, wg: Int) {
        val s = Settings.instance
        val existing = s.servers.firstOrNull { it.ip == host }
        val srv = (existing ?: ServerProfile(name = host, ip = host)).copy(password = password, dtlsPort = dtls, wgPort = wg)
        s.upsertServer(srv)
        s.activeServerId = srv.id
        resultMessage = "Деплой успешно завершен ($host:$dtls). Сервер добавлен в список и выбран."
    }

    fun cancel() {
        val p = proc ?: return
        cancelled = true
        p.destroy()
    }
}

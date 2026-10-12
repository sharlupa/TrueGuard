package com.trueguard.core

import kotlinx.serialization.json.*
import java.util.Base64

/** Разделы экспорта — те же, что в Android (trueguard.config.v2). */
enum class ExportKind(val id: String, val title: String, val subtitle: String, val sections: List<String>) {
    FULL("all", "Всё сразу", "Серверы, сеть, производительность, интерфейс и исключения",
        listOf("servers", "network", "performance", "interface", "exceptions")),
    SERVERS("servers", "Все серверы", "Список серверов, активный сервер и пароль подключения", listOf("servers")),
    ACTIVE_SERVER("active_server", "Активный сервер", "Только выбранный сервер для быстрого переноса", listOf("active_server")),
    NETWORK("network", "Сеть", "Протокол, порты, SNI, MTU и DNS", listOf("network")),
    PERFORMANCE("performance", "Производительность", "VK-хеши, потоки и режим капчи", listOf("performance")),
    INTERFACE("interface", "Интерфейс", "Тема, динамические цвета и палитра", listOf("interface")),
}

class ImportException(message: String) : Exception(message)

object ConfigLink {
    private const val PREFIX = "trueguard://config?data="

    private val sectionNames = mapOf(
        "servers" to "серверы", "active_server" to "серверы", "network" to "сеть",
        "performance" to "производительность", "interface" to "интерфейс", "exceptions" to "исключения",
    )

    fun buildExport(s: Settings, kind: ExportKind): JsonObject {
        val sections = kind.sections
        val payload = buildJsonObject {
            if ("servers" in sections || "active_server" in sections) {
                val items = if ("active_server" in sections) listOfNotNull(s.activeServer) else s.servers
                // Android читает серверы только из payload.servers — и для «Активного сервера» тоже
                put("servers", buildJsonObject {
                    put("items", JsonArray(items.map { it.toJson() }))
                    put("activePeer", s.activeServer?.peer ?: "")
                    put("connectionPassword", s.effectivePassword(s.activeServer))
                })
            }
            if ("network" in sections) put("network", buildJsonObject {
                put("protocol", s.protocol)
                put("listenPort", s.listenPort)
                put("sni", s.sni)
                put("manualPortsEnabled", s.manualPortsEnabled)
                put("serverDtlsPort", s.serverDtlsPort)
                put("serverWgPort", s.serverWgPort)
                put("customMtu", s.customMtu)
                put("customDns", s.customDns)
                put("customDnsIp", s.customDnsIp)
            })
            if ("performance" in sections) put("performance", buildJsonObject {
                put("vkHashes", s.vkHashes.map(Settings::normalizeHash).filter { it.isNotEmpty() }.joinToString(","))
                put("secondaryVkHash", Settings.normalizeHash(s.secondaryVkHash))
                put("workersPerHash", s.workersPerHash)
                put("captchaMode", s.captchaMode)
                put("captchaSolveMethod", s.captchaSolveMethod)
                put("captchaWbvSolveMethod", s.captchaWbvSolveMethod)
                put("vkAuthMode", s.vkAuthMode)
                put("obfsMode", s.obfsMode)
                put("vkFingerprint", s.vkFingerprint)
            })
            if ("interface" in sections) put("interface", buildJsonObject {
                put("themeMode", s.themeMode)
                put("dynamicColor", s.dynamicColor)
                put("themePalette", s.themePalette)
                put("themeSeedColor", s.themeSeedColor) // знаковое 32-битное, как Color.toArgb() в Android
            })
            if ("exceptions" in sections) put("exceptions", buildJsonObject {
                put("excludedApps", s.excludedApps.joinToString(",") { it.key })
                put("isWhitelist", s.isWhitelist)
            })
        }
        return buildJsonObject {
            put("schema", "trueguard.config.v2")
            put("version", 2)
            put("app", "TrueGuard")
            put("appVersion", UPSTREAM_VERSION)
            // оригинальный Android-клиент поле не пишет: его отсутствие и значит Android
            if (!Platform.isAndroid) put("platform", PLATFORM_NAME.lowercase())
            put("kind", kind.id)
            put("createdAt", System.currentTimeMillis())
            put("sections", JsonArray(sections.map(::JsonPrimitive)))
            put("payload", payload)
        }
    }

    fun buildLink(s: Settings, kind: ExportKind): String =
        PREFIX + Base64.getUrlEncoder().encodeToString(buildExport(s, kind).toString().toByteArray())

    /** «Умный импорт»: ссылка trueguard://config, JSON v2, старый JSON v1, сервер или ссылки на VK-звонки. */
    fun import(s: Settings, input: String): String {
        val text = input.trim()
        if (text.isEmpty()) throw ImportException("Буфер обмена пуст")
        var json: JsonObject? = null
        val linkAt = text.indexOf(PREFIX)
        try {
            if (linkAt >= 0) {
                var data = text.substring(linkAt + PREFIX.length)
                val end = data.indexOfFirst { it.isWhitespace() || it in "\"'<>&" }
                if (end >= 0) data = data.substring(0, end)
                data = data.replace('+', '-').replace('/', '_').trimEnd('=')
                json = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(data))).jsonObject
            } else {
                val a = text.indexOf('{')
                val b = text.lastIndexOf('}')
                if (a >= 0 && b > a) json = Json.parseToJsonElement(text.substring(a, b + 1)).jsonObject
            }
        } catch (e: Exception) {
            throw ImportException("Конфиг не найден или повреждён")
        }
        if (json == null) {
            val hashes = Regex("call/join/([A-Za-z0-9_\\-]{16,})").findAll(text).map { it.groupValues[1] }.toList()
            if (hashes.isEmpty()) throw ImportException("Конфиг не найден или повреждён")
            val cur = s.allHashes.toMutableList()
            hashes.forEach { if (it !in cur) cur.add(it) }
            s.vkHashes = List(4) { cur.getOrElse(it) { "" } }
            return "Импортировано: VK-хеши (${hashes.size})"
        }
        return if (json.str("schema") == "trueguard.config.v2" || json.containsKey("payload")) importV2(s, json) else importLegacy(s, json)
    }

    private fun hashesOf(raw: JsonElement?): List<String> {
        val list = when (raw) {
            is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.content }
            is JsonPrimitive -> raw.content.split(Regex("[,\\s;]+"))
            else -> emptyList()
        }
        return list.map(Settings::normalizeHash).filter { it.isNotEmpty() }
    }

    private fun importV2(s: Settings, root: JsonObject): String {
        val payload = root["payload"] as? JsonObject ?: JsonObject(emptyMap())
        val samePlatform = (root.str("platform") ?: "android") == PLATFORM_NAME.lowercase()
        val done = linkedSetOf<String>()
        var serverCount = 0
        for (key in listOf("servers", "active_server")) {
            val sec = payload[key] as? JsonObject ?: continue
            (sec["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach {
                serverCount++
                mergeServer(s, ServerProfile.fromJson(it))
            }
            sec.str("connectionPassword")?.takeIf { it.isNotEmpty() }?.let { s.connectionPassword = it }
            selectPeer(s, sec.str("activePeer") ?: "")
            done.add(sectionNames.getValue(key))
        }
        (payload["network"] as? JsonObject)?.let { net ->
            net.str("protocol")?.let { s.protocol = it }
            s.listenPort = net.int("listenPort", s.listenPort)
            net.str("sni")?.let { s.sni = it }
            s.manualPortsEnabled = net.bool("manualPortsEnabled", false)
            s.serverDtlsPort = net.int("serverDtlsPort", s.serverDtlsPort)
            s.serverWgPort = net.int("serverWgPort", s.serverWgPort)
            s.customMtu = net.int("customMtu", s.customMtu)
            net.str("customDns")?.let { s.customDns = it }
            net.str("customDnsIp")?.let { s.customDnsIp = it }
            done.add("сеть")
        }
        (payload["performance"] as? JsonObject)?.let { perf ->
            val hashes = hashesOf(perf["vkHashes"])
            if (hashes.isNotEmpty()) s.vkHashes = List(4) { hashes.getOrElse(it) { "" } }
            perf.str("secondaryVkHash")?.let { s.secondaryVkHash = it }
            s.workersPerHash = perf.int("workersPerHash", s.workersPerHash)
            perf.str("captchaMode")?.let { s.captchaMode = it }
            perf.str("captchaSolveMethod")?.let { s.captchaSolveMethod = it }
            perf.str("captchaWbvSolveMethod")?.let { s.captchaWbvSolveMethod = it }
            perf.str("vkAuthMode")?.let { s.vkAuthMode = it }
            perf.str("obfsMode")?.let { s.obfsMode = it }
            perf.str("vkFingerprint")?.let { s.vkFingerprint = it }
            done.add("производительность")
        }
        (payload["interface"] as? JsonObject)?.let { ui ->
            ui.str("themeMode")?.let { s.themeMode = it }
            s.dynamicColor = ui.bool("dynamicColor", false)
            ui.str("themePalette")?.let { s.themePalette = it }
            s.themeSeedColor = ui.long("themeSeedColor", s.themeSeedColor.toLong()).toInt()
            done.add("интерфейс")
        }
        (payload["exceptions"] as? JsonObject)?.let { exc ->
            s.isWhitelist = exc.bool("isWhitelist", false)
            // пакеты Android, программы Linux и Windows друг другу ничего не значат — список только с той же платформы
            if (samePlatform) {
                s.excludedApps = (exc.str("excludedApps") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    .map { ExcludedApp(it, it) }
            }
            done.add(if (samePlatform) "исключения" else "режим исключений")
        }
        if (done.isEmpty()) return "Импортировано: ничего"
        return "Импортировано: ${done.joinToString(", ")}${if (serverCount > 0) " ($serverCount серверов)" else ""}"
    }

    private fun importLegacy(s: Settings, j: JsonObject): String {
        val done = mutableListOf<String>()
        // одиночный сервер: {"ip": ..., "password": ...}
        if (j.containsKey("ip") && !j.containsKey("hashes")) {
            mergeServer(s, ServerProfile.fromJson(j), select = true)
            return "Импортировано: сервер"
        }
        if (j.containsKey("peer")) {
            val peer = j.str("peer") ?: ""
            mergeServer(s, ServerProfile.fromJson(buildJsonObject {
                put("ip", peer); put("name", j.str("name") ?: peer); put("password", j.str("password") ?: "")
            }), select = true)
            done.add("сервер")
        }
        j.str("password")?.takeIf { it.isNotEmpty() }?.let { s.connectionPassword = it }
        if (j.containsKey("hashes")) {
            val hashes = hashesOf(j["hashes"])
            if (hashes.isNotEmpty()) s.vkHashes = List(4) { hashes.getOrElse(it) { "" } }
            val sec = when (val raw = j["secondaryHashes"]) {
                is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.content }
                is JsonPrimitive -> raw.content.split(',')
                else -> emptyList()
            }
            sec.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { s.secondaryVkHash = it }
            done.add("VK-хеши")
        }
        if (j.containsKey("workers")) s.workersPerHash = j.int("workers", s.workersPerHash)
        if (j.containsKey("listenPort")) s.listenPort = j.int("listenPort", s.listenPort)
        j.str("sni")?.let { s.sni = it }
        if (j.containsKey("tcp")) s.protocol = if (j.bool("tcp", false)) "tcp" else "udp"
        j.str("captchaSolveMethod")?.let { s.captchaSolveMethod = it }
        (j["servers"] as? JsonArray)?.let { arr ->
            arr.mapNotNull { it as? JsonObject }.forEach { mergeServer(s, ServerProfile.fromJson(it)) }
            done.add("серверы")
        }
        if (done.isEmpty()) throw ImportException("Конфиг не найден или повреждён")
        return "Импортировано: ${done.joinToString(", ")}"
    }

    private fun mergeServer(s: Settings, srv: ServerProfile, select: Boolean = false) {
        if (srv.ip.isEmpty()) return
        val existing = s.servers.lastOrNull { it.ip == srv.ip && it.dtlsPort == srv.dtlsPort }
        val merged = if (existing != null) {
            existing.copy(name = srv.name, password = srv.password.ifEmpty { existing.password }, wgPort = srv.wgPort)
        } else if (s.servers.any { it.id == srv.id }) srv.copy(id = ServerProfile.newId()) else srv
        s.upsertServer(merged)
        if (select || s.activeServerId.isEmpty()) s.activeServerId = merged.id
    }

    private fun selectPeer(s: Settings, peer: String) {
        if (peer.isEmpty()) return
        s.servers.firstOrNull { it.peer == peer || it.ip == peer }?.let { s.activeServerId = it.id }
    }
}

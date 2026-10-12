package com.trueguard.core

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.properties.ReadWriteProperty
import kotlin.random.Random
import kotlin.reflect.KProperty

/** Сервер из списка «Серверы» (как saved_servers_json в Android). */
data class ServerProfile(
    val id: String = newId(),
    val name: String,
    val ip: String, // IP или домен без порта
    val password: String = "",
    val dtlsPort: Int = 56000,
    val wgPort: Int = 56001,
) {
    val peer: String
        get() {
            val host = ip.trim()
            if (host.contains(':') && !host.startsWith('[') && host.split(':').size == 2) return host // уже host:port
            return "$host:$dtlsPort"
        }

    fun toJson() = buildJsonObject {
        put("id", id); put("name", name); put("ip", ip); put("password", password)
        put("dtlsPort", dtlsPort); put("wgPort", wgPort)
    }

    companion object {
        fun newId(): String = System.nanoTime().toString(36) + Random.nextInt(1 shl 20).toString(36)

        fun fromJson(j: JsonObject): ServerProfile {
            var ip = (j.str("ip") ?: j.str("peer") ?: "").trim()
            var dtls = j.int("dtlsPort", 56000)
            // «1.2.3.4:56000» → IP и порт отдельно
            Regex("^([^:\\[\\]]+):(\\d{1,5})$").find(ip)?.let {
                ip = it.groupValues[1]
                dtls = it.groupValues[2].toInt()
            }
            return ServerProfile(
                id = j.str("id")?.takeIf { it.isNotEmpty() } ?: newId(),
                name = j.str("name")?.takeIf { it.isNotEmpty() } ?: ip,
                ip = ip,
                password = j.str("password") ?: "",
                dtlsPort = dtls,
                wgPort = j.int("wgPort", 56001),
            )
        }
    }
}

/** Приложение в «Исключениях»: ключ передаётся ядру в -apps. */
data class ExcludedApp(val key: String, val name: String) // имя программы, путь, flatpak:ID или snap:имя

/** Виджеты дашборда, как в Android: порядок и скрытые хранятся в настройках. */
enum class DashWidget(val title: String, val wide: Boolean = false) {
    SESSION("Сессия"), WORKERS("Воркеры"), TRAFFIC("Трафик"), HEALTH("Статус"), GRAPH("График сети", wide = true);
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.let { if (it is JsonNull) null else it.content }
internal fun JsonObject.int(key: String, def: Int): Int {
    val p = this[key] as? JsonPrimitive ?: return def
    return p.intOrNull ?: p.longOrNull?.toInt() ?: p.doubleOrNull?.toInt() ?: p.content.trim().toIntOrNull() ?: def
}
internal fun JsonObject.long(key: String, def: Long): Long {
    val p = this[key] as? JsonPrimitive ?: return def
    return p.longOrNull ?: p.doubleOrNull?.toLong() ?: p.content.trim().toLongOrNull() ?: def
}
internal fun JsonObject.bool(key: String, def: Boolean): Boolean {
    val p = this[key] as? JsonPrimitive ?: return def
    return p.booleanOrNull ?: when (p.content) {
        "true", "1" -> true
        "false", "0" -> false
        else -> p.intOrNull?.let { it != 0 } ?: def
    }
}

class Settings private constructor() {
    companion object {
        val instance = Settings()

        /** Хеш из ссылки vk.com/call/join/... или «чистый» хеш. */
        fun normalizeHash(raw: String): String {
            var s = raw.trim()
            if (s.isEmpty()) return ""
            Regex("call/join/([A-Za-z0-9_\\-]+)").find(s)?.let { return it.groupValues[1] }
            s = s.split(Regex("[?#\\s]")).first()
            if (s.contains('/')) s = s.split('/').last { it.isNotEmpty() }
            return s.replace(Regex("[^A-Za-z0-9_\\-]"), "")
        }

        private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }
    }

    private var loading = false

    /** Свойство-настройка: читается Compose как состояние, запись сохраняет файл. */
    private fun <T> pref(initial: T) = object : ReadWriteProperty<Settings, T> {
        val state = mutableStateOf(initial)
        override fun getValue(thisRef: Settings, property: KProperty<*>): T = state.value
        override fun setValue(thisRef: Settings, property: KProperty<*>, value: T) {
            if (state.value == value) return
            state.value = value
            if (!loading) save()
        }
    }

    // Серверы
    var servers by pref(listOf<ServerProfile>())
    var activeServerId by pref("")
    var connectionPassword by pref("")

    // Сеть
    var listenPort by pref(9000)
    var protocol by pref("udp")
    var sni by pref("")
    var manualPortsEnabled by pref(false)
    var serverDtlsPort by pref(56000)
    var serverWgPort by pref(56001)
    var customMtu by pref(0) // 0 — авто
    var customDns by pref("default") // default, adguard, cloudflare, custom
    var customDnsIp by pref("1.1.1.1")

    // Производительность
    var vkHashes by pref(listOf("", "", "", "")) // основной + 3 дополнительных
    var secondaryVkHash by pref("") // запасной
    /** Потоки обработки всего (в APK ключ называется workers_per_hash): 12…27×хешей. */
    var workersPerHash by pref(24)
    var captchaMode by pref("auto") // auto, wv
    var captchaSolveMethod by pref("auto")
    var captchaWbvSolveMethod by pref("auto")
    var vkAuthMode by pref("legacy") // vkcalls, legacy
    var obfsMode by pref("audio") // audio, video
    var vkFingerprint by pref("firefox")

    // Интерфейс
    var themeMode by pref("system") // system, light, dark, amoled
    var dynamicColor by pref(false)
    var themePalette by pref("tyrn")
    var themeSeedColor by pref(0xFF176B74.toInt())

    /** Масштаб интерфейса в процентах (только Linux: окно на мониторе, а не телефон). */
    var uiScale by pref(100)

    // Дашборд
    var dashWidgets by pref(DashWidget.entries.map { it.name })
    var hiddenWidgets by pref(listOf<String>())
    var onboardingDone by pref(false)

    // Исключения
    var excludedApps by pref(listOf<ExcludedApp>())
    var isWhitelist by pref(false)

    // Linux
    var tunnelMode by pref("tun") // tun — вся система (root), proxy — SOCKS5/HTTP
    var proxyListen by pref("127.0.0.1:1080")
    var bindIface by pref("")
    var tunDns by pref(true)
    var blockIpv6 by pref(true)
    var excludeAddrs by pref("")
    var includeAddrs by pref("")
    var browserPath by pref("")
    var autostart by pref(false)
    var connectOnLaunch by pref(false)
    var closeToTray by pref(true)
    var notifyDisconnects by pref(true)
    var detailedLogs by pref(false)
    var autoReconnect by pref(true)
    var rootMethod by pref("auto") // auto, pkexec, sudo — как получать root для режима TUN

    // Деплой (секреты в файле с правами 0600, как Android — в EncryptedPrefs)
    var deployIp by pref("")
    var deployLogin by pref("root")
    var deployPassword by pref("")
    var deploySshPort by pref(22)
    var deployMainPassword by pref("")
    var deployAdminId by pref("")
    var deployBotToken by pref("")

    // Обновления
    var updateLastCheckAt by pref(0L)
    var updateLatestVersion by pref("")
    var updatePostponeUntil by pref(0L)

    val activeServer: ServerProfile?
        get() = servers.firstOrNull { it.id == activeServerId } ?: servers.firstOrNull()

    val allHashes: List<String>
        get() = vkHashes.map(::normalizeHash).filter { it.isNotEmpty() }

    /** Лимит потоков: 27 на каждый валидный хеш (3 группы по 9), не больше 108. */
    fun maxWorkers(hashCount: Int) = 27 * hashCount.coerceIn(1, 4)

    /** Сколько воркеров получит ядро: значение ползунка в пределах лимита, вниз до кратного 9 (ядро делит на группы по 9). */
    fun totalWorkers(hashCount: Int): Int = (workersPerHash.coerceIn(9, maxWorkers(hashCount)) / 9) * 9

    fun effectivePassword(s: ServerProfile?): String {
        val p = s?.password?.trim().orEmpty()
        return p.ifEmpty { connectionPassword.trim() }
    }

    val effectiveDns: String?
        get() = when (customDns) {
            "adguard" -> "94.140.14.14,94.140.15.15"
            "cloudflare" -> "1.1.1.1,1.0.0.1"
            "custom" -> customDnsIp.trim().ifEmpty { null }
            else -> null
        }

    fun setHash(index: Int, value: String) {
        vkHashes = vkHashes.toMutableList().also { while (it.size < 4) it.add(""); it[index] = value }
    }

    fun upsertServer(s: ServerProfile) {
        servers = if (servers.any { it.id == s.id }) servers.map { if (it.id == s.id) s else it } else servers + s
    }

    fun removeServer(id: String) {
        servers = servers.filterNot { it.id == id }
        if (activeServerId == id) activeServerId = servers.firstOrNull()?.id ?: ""
    }

    // ───────────── сериализация ─────────────

    fun toJson(): JsonObject = buildJsonObject {
        put("servers", JsonArray(servers.map { it.toJson() }))
        put("activeServerId", activeServerId)
        put("connectionPassword", connectionPassword)
        put("listenPort", listenPort)
        put("protocol", protocol)
        put("sni", sni)
        put("manualPortsEnabled", manualPortsEnabled)
        put("serverDtlsPort", serverDtlsPort)
        put("serverWgPort", serverWgPort)
        put("customMtu", customMtu)
        put("customDns", customDns)
        put("customDnsIp", customDnsIp)
        put("vkHashes", JsonArray(vkHashes.map(::JsonPrimitive)))
        put("secondaryVkHash", secondaryVkHash)
        put("workersPerHash", workersPerHash)
        put("captchaMode", captchaMode)
        put("captchaSolveMethod", captchaSolveMethod)
        put("captchaWbvSolveMethod", captchaWbvSolveMethod)
        put("vkAuthMode", vkAuthMode)
        put("obfsMode", obfsMode)
        put("vkFingerprint", vkFingerprint)
        put("themeMode", themeMode)
        put("dynamicColor", dynamicColor)
        put("themePalette", themePalette)
        put("themeSeedColor", themeSeedColor.toLong() and 0xFFFFFFFFL)
        put("uiScale", uiScale)
        put("dashWidgets", JsonArray(dashWidgets.map(::JsonPrimitive)))
        put("hiddenWidgets", JsonArray(hiddenWidgets.map(::JsonPrimitive)))
        put("onboardingDone", onboardingDone)
        put("excludedApps", JsonArray(excludedApps.map { buildJsonObject { put("key", it.key); put("name", it.name) } }))
        put("isWhitelist", isWhitelist)
        put("tunnelMode", tunnelMode)
        put("proxyListen", proxyListen)
        put("bindIface", bindIface)
        put("tunDns", tunDns)
        put("blockIpv6", blockIpv6)
        put("excludeAddrs", excludeAddrs)
        put("includeAddrs", includeAddrs)
        put("browserPath", browserPath)
        put("autostart", autostart)
        put("connectOnLaunch", connectOnLaunch)
        put("closeToTray", closeToTray)
        put("notifyDisconnects", notifyDisconnects)
        put("detailedLogs", detailedLogs)
        put("autoReconnect", autoReconnect)
        put("rootMethod", rootMethod)
        put("deployIp", deployIp)
        put("deployLogin", deployLogin)
        put("deployPassword", deployPassword)
        put("deploySshPort", deploySshPort)
        put("deployMainPassword", deployMainPassword)
        put("deployAdminId", deployAdminId)
        put("deployBotToken", deployBotToken)
        put("updateLastCheckAt", updateLastCheckAt)
        put("updateLatestVersion", updateLatestVersion)
        put("updatePostponeUntil", updatePostponeUntil)
    }

    private fun fromJson(j: JsonObject) {
        servers = (j["servers"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(ServerProfile::fromJson) }
        activeServerId = j.str("activeServerId") ?: ""
        connectionPassword = j.str("connectionPassword") ?: ""
        listenPort = j.int("listenPort", 9000)
        protocol = j.str("protocol") ?: "udp"
        sni = j.str("sni") ?: ""
        manualPortsEnabled = j.bool("manualPortsEnabled", false)
        serverDtlsPort = j.int("serverDtlsPort", 56000)
        serverWgPort = j.int("serverWgPort", 56001)
        customMtu = j.int("customMtu", 0)
        customDns = j.str("customDns") ?: "default"
        customDnsIp = j.str("customDnsIp") ?: "1.1.1.1"
        (j["vkHashes"] as? JsonArray)?.let { arr ->
            vkHashes = List(4) { i -> (arr.getOrNull(i) as? JsonPrimitive)?.content ?: "" }
        }
        secondaryVkHash = j.str("secondaryVkHash") ?: ""
        workersPerHash = j.int("workersPerHash", 24)
        captchaMode = j.str("captchaMode") ?: "auto"
        captchaSolveMethod = j.str("captchaSolveMethod") ?: "auto"
        captchaWbvSolveMethod = j.str("captchaWbvSolveMethod") ?: "auto"
        vkAuthMode = j.str("vkAuthMode") ?: "legacy"
        obfsMode = j.str("obfsMode") ?: "audio"
        vkFingerprint = j.str("vkFingerprint") ?: "firefox"
        themeMode = j.str("themeMode") ?: "system"
        dynamicColor = j.bool("dynamicColor", false)
        themePalette = j.str("themePalette") ?: "tyrn"
        themeSeedColor = j.long("themeSeedColor", 0xFF176B74).toInt()
        uiScale = j.int("uiScale", 100).coerceIn(70, 200)
        val known = DashWidget.entries.map { it.name }.toSet()
        (j["dashWidgets"] as? JsonArray)?.let { arr ->
            dashWidgets = arr.mapNotNull { (it as? JsonPrimitive)?.content }.filter { it in known }.distinct()
        }
        (j["hiddenWidgets"] as? JsonArray)?.let { arr ->
            hiddenWidgets = arr.mapNotNull { (it as? JsonPrimitive)?.content }.filter { it in known && it !in dashWidgets }.distinct()
        }
        // виджет, которого нет ни в одном списке (новая версия), показываем
        val missing = known - dashWidgets.toSet() - hiddenWidgets.toSet()
        if (missing.isNotEmpty()) dashWidgets = dashWidgets + DashWidget.entries.map { it.name }.filter { it in missing }
        onboardingDone = j.bool("onboardingDone", j.containsKey("servers"))
        // файл от Flutter-версии: там это было «потоков на хеш», теперь — всего потоков, как в APK
        if (j.containsKey("workersPerHash") && !j.containsKey("dashWidgets")) {
            val hashes = allHashes.size.coerceAtLeast(1)
            workersPerHash = (workersPerHash * hashes).coerceAtMost(maxWorkers(hashes))
        }
        excludedApps = (j["excludedApps"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val key = o.str("key") ?: ""
            if (key.isEmpty()) null else ExcludedApp(key, o.str("name") ?: key)
        }
        isWhitelist = j.bool("isWhitelist", false)
        tunnelMode = j.str("tunnelMode") ?: "tun"
        proxyListen = j.str("proxyListen") ?: "127.0.0.1:1080"
        bindIface = j.str("bindIface") ?: ""
        tunDns = j.bool("tunDns", true)
        blockIpv6 = j.bool("blockIpv6", true)
        excludeAddrs = j.str("excludeAddrs") ?: ""
        includeAddrs = j.str("includeAddrs") ?: ""
        browserPath = j.str("browserPath") ?: ""
        autostart = j.bool("autostart", false)
        connectOnLaunch = j.bool("connectOnLaunch", false)
        closeToTray = j.bool("closeToTray", true)
        notifyDisconnects = j.bool("notifyDisconnects", true)
        detailedLogs = j.bool("detailedLogs", false)
        autoReconnect = j.bool("autoReconnect", true)
        rootMethod = j.str("rootMethod") ?: "auto"
        deployIp = j.str("deployIp") ?: ""
        deployLogin = j.str("deployLogin") ?: "root"
        deployPassword = j.str("deployPassword") ?: ""
        deploySshPort = j.int("deploySshPort", 22)
        deployMainPassword = j.str("deployMainPassword") ?: ""
        deployAdminId = j.str("deployAdminId") ?: ""
        deployBotToken = j.str("deployBotToken") ?: ""
        updateLastCheckAt = j.long("updateLastCheckAt", 0)
        updateLatestVersion = j.str("updateLatestVersion") ?: ""
        updatePostponeUntil = j.long("updatePostponeUntil", 0)
    }

    private val file get() = File(Paths.configDir, "settings.json")

    fun load() {
        loading = true
        try {
            val f = file
            if (f.isFile) fromJson(Json.parseToJsonElement(f.readText()).jsonObject)
        } catch (e: Exception) {
            System.err.println("settings: $e")
        } finally {
            loading = false
        }
    }

    // Сохранения идут строго по очереди: несколько изменений подряд сливаются в одну запись.
    private val saves = Channel<Unit>(Channel.CONFLATED)
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        for (unit in saves) runCatching { writeNow() }.onFailure { System.err.println("settings save: $it") }
    }

    fun save() {
        saves.trySend(Unit)
    }

    @Synchronized
    fun writeNow() {
        val f = file
        f.parentFile.mkdirs()
        val tmp = File(f.path + ".tmp")
        // файл с паролями: создаём сразу с правами 0600
        Paths.createPrivateFile(tmp)
        tmp.writeText(json.encodeToString(JsonObject.serializer(), toJson()))
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

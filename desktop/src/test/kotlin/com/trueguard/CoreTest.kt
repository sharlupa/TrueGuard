package com.trueguard

import com.trueguard.core.*
import androidx.compose.ui.graphics.Color
import com.trueguard.ui.theme.customScheme
import com.trueguard.ui.theme.hsl
import com.trueguard.ui.theme.near
import com.trueguard.ui.theme.toHsl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.*

class CoreTest {
    @BeforeTest
    fun setup() {
        // настройки не должны попасть в настоящий ~/.config
        Paths.configDirOverride = Files.createTempDirectory("tg-core").toString()
        Settings.instance.servers = emptyList()
    }

    @Test
    fun normalizeHash() {
        assertEquals("AbCdEf0123456789_-xyz", Settings.normalizeHash("https://vk.com/call/join/AbCdEf0123456789_-xyz?x=1"))
        assertEquals("AbCdEf0123456789", Settings.normalizeHash("  AbCdEf0123456789  "))
    }

    @Test
    fun workersLikeAndroid() {
        val s = Settings.instance
        s.workersPerHash = 24
        assertEquals(18, s.totalWorkers(1)) // ядро делит на группы по 9
        s.workersPerHash = 36
        assertEquals(27, s.totalWorkers(1)) // лимит 27 на хеш
        assertEquals(36, s.totalWorkers(2))
        s.workersPerHash = 500
        assertEquals(108, s.totalWorkers(4))
    }

    @Test
    fun exportImportV2() {
        val s = Settings.instance
        s.servers = listOf(ServerProfile(id = "a", name = "Германия", ip = "203.0.113.10", password = "pass1"))
        s.activeServerId = "a"
        s.vkHashes = listOf("AAAAAAAAAAAAAAAAAAAA", "BBBBBBBBBBBBBBBBBBBB", "", "")
        s.workersPerHash = 36
        s.obfsMode = "video"
        val link = ConfigLink.buildLink(s, ExportKind.FULL)
        assertTrue(link.startsWith("trueguard://config?data="))
        val root = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(link.substring(24)))).jsonObject
        assertEquals("trueguard.config.v2", root["schema"]!!.jsonPrimitive.content)
        assertEquals("all", root["kind"]!!.jsonPrimitive.content)
        val payload = root["payload"]!!.jsonObject
        assertEquals("203.0.113.10:56000", payload["servers"]!!.jsonObject["activePeer"]!!.jsonPrimitive.content)
        assertEquals("AAAAAAAAAAAAAAAAAAAA,BBBBBBBBBBBBBBBBBBBB", payload["performance"]!!.jsonObject["vkHashes"]!!.jsonPrimitive.content)

        s.servers = emptyList()
        s.activeServerId = ""
        s.vkHashes = listOf("", "", "", "")
        s.workersPerHash = 12
        s.obfsMode = "audio"
        val msg = ConfigLink.import(s, "вот ссылка: $link")
        assertContains(msg, "серверы")
        assertEquals("Германия", s.servers.single().name)
        assertEquals("pass1", s.activeServer?.password)
        assertEquals(listOf("AAAAAAAAAAAAAAAAAAAA", "BBBBBBBBBBBBBBBBBBBB"), s.allHashes)
        assertEquals(36, s.workersPerHash)
        assertEquals("video", s.obfsMode)
    }

    @Test
    fun importLegacyAndCallLinks() {
        val s = Settings.instance
        s.vkHashes = listOf("", "", "", "")
        ConfigLink.import(s, """{"peer":"198.51.100.7:56010","hashes":"CCCCCCCCCCCCCCCCCCCC","password":"pw","workers":12}""")
        assertEquals("198.51.100.7", s.activeServer?.ip)
        assertEquals(56010, s.activeServer?.dtlsPort)
        assertEquals(listOf("CCCCCCCCCCCCCCCCCCCC"), s.allHashes)
        assertEquals("pw", s.connectionPassword)
        ConfigLink.import(s, "https://vk.com/call/join/DDDDDDDDDDDDDDDDDDDD")
        assertEquals(listOf("CCCCCCCCCCCCCCCCCCCC", "DDDDDDDDDDDDDDDDDDDD"), s.allHashes)
        assertFailsWith<ImportException> { ConfigLink.import(s, "мусор") }
    }

    @Test
    fun androidPackagesAreNotImportedAsLinuxApps() {
        val s = Settings.instance
        s.excludedApps = emptyList()
        ConfigLink.import(s, """{"schema":"trueguard.config.v2","payload":{"exceptions":{"excludedApps":"org.telegram.messenger","isWhitelist":true}}}""")
        assertTrue(s.isWhitelist)
        assertTrue(s.excludedApps.isEmpty())
    }

    @Test
    fun settingsFileIsPrivateAndRoundTrips() {
        val s = Settings.instance
        s.servers = listOf(ServerProfile(id = "x", name = "Тест", ip = "192.0.2.1", password = "p"))
        s.activeServerId = "x"
        s.themeSeedColor = 0xFFAF3F5F.toInt()
        s.dashWidgets = listOf("GRAPH", "SESSION")
        s.hiddenWidgets = listOf("WORKERS")
        s.writeNow()
        val f = File(Paths.configDir, "settings.json")
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath())))
        s.servers = emptyList()
        s.themeSeedColor = 0
        s.load()
        assertEquals("Тест", s.servers.single().name)
        assertEquals(0xFFAF3F5F.toInt(), s.themeSeedColor)
        // виджеты, которых нет ни в одном списке, возвращаются в конец
        assertEquals(listOf("GRAPH", "SESSION", "TRAFFIC", "HEALTH"), s.dashWidgets)
        assertEquals(listOf("WORKERS"), s.hiddenWidgets)
    }

    @Test
    fun readsFlutterSettings() {
        // файл от предыдущей (Flutter) версии Linux-порта
        File(Paths.configDir).mkdirs()
        File(Paths.configDir, "settings.json").writeText(
            """{"servers":[{"id":"q","name":"Old","ip":"203.0.113.5:56002","password":"z"}],"activeServerId":"q",
               "vkHashes":["EEEEEEEEEEEEEEEEEEEE","FFFFFFFFFFFFFFFFFFFF"],"themeSeedColor":4279724916,"tunnelMode":"proxy","workersPerHash":"16"}"""
        )
        val s = Settings.instance
        s.load()
        assertEquals(56002, s.activeServer?.dtlsPort)
        assertEquals("203.0.113.5", s.activeServer?.ip)
        assertEquals(0xFF176B74.toInt(), s.themeSeedColor)
        assertEquals(32, s.workersPerHash) // было 16 на хеш × 2 хеша
        assertEquals("proxy", s.tunnelMode)
        assertTrue(s.onboardingDone)
    }

    @Test
    fun humanize() {
        assertEquals("creds_start", humanize("[ГРУППА #1] Запрос кредов (хеш: abc...)")?.key)
        assertEquals("dtls_ok", humanize("[ВОРКЕР #3] [DTLS] Соединение установлено ✓")?.key)
        assertEquals("captcha_auto_1", humanize("[STREAM 2] [КАПЧА] AUTO: старт цепочки (captcha attempt 1)")?.key)
        val auth = humanize("[ВОРКЕР #1] Фатальная ошибка: FATAL_AUTH: DENIED:device_mismatch другому устройству")
        assertEquals(true, auth?.error)
        assertContains(auth!!.text, "другому устройству")
        assertNull(humanize("[ДИСП] Воркер #4 зарегистрирован (всего: 4)"))
        assertEquals("[КЛИЕНТ] Режим: tun", stripTimestamp("2026/10/11 17:40:00.123456 [КЛИЕНТ] Режим: tun"))
    }

    @Test
    fun logCollapsesRepeats() {
        LogStore.clear()
        LogStore.addRaw("строка")
        LogStore.addRaw("строка")
        LogStore.event("событие", key = "k")
        LogStore.event("событие", key = "k")
        assertEquals(2, LogStore.raw.first().count)
        assertEquals(2, LogStore.human.single().count)
        assertContains(LogStore.human.single().format(), "(x2)")
    }

    @Test
    fun desktopExec() {
        assertEquals(
            listOf("env", "GDK_BACKEND=x11", "/opt/My App/app", "--flag"),
            AppScanner.splitExec("""env GDK_BACKEND=x11 "/opt/My App/app" --flag %U"""),
        )
        assertContains(
            AppScanner.splitExec("/usr/bin/flatpak run --branch=stable --command=telegram-desktop org.telegram.desktop -- @@u %u @@"),
            "org.telegram.desktop",
        )
    }

    @Test
    fun customPaletteMatchesApk() {
        // «Свой» цвет даёт те же тона, что алгоритм APK: primary = hsl(h, s, .38) в светлой теме
        val seed = Color(0xFF176B74)
        val hs = seed.toHsl()
        assertTrue(customScheme(seed, dark = false).primary.near(hsl(hs[0], hs[1].coerceIn(.35f, .85f), .38f)))
        assertTrue(customScheme(seed, dark = true).primary.near(hsl(hs[0], hs[1].coerceIn(.35f, .85f), .8f)))
    }
}

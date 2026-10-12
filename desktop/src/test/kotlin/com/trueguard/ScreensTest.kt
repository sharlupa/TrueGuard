package com.trueguard

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.trueguard.core.*
import com.trueguard.ui.AppShell
import com.trueguard.ui.Onboarding
import com.trueguard.ui.settings.SettingsHost
import com.trueguard.ui.settings.SettingsRoute
import com.trueguard.ui.theme.TrueGuardTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Рендер всех экранов в PNG без окна (build/screens) — для сверки со скриншотами телефона.
 * Запуск: gradle test --tests com.trueguard.ScreensTest
 */
class ScreensTest {
    private val out = File("build/screens").apply { mkdirs() }

    @BeforeTest
    fun setup() {
        Platform.hooks = com.trueguard.platform.DesktopPlatform
        Paths.configDirOverride = Files.createTempDirectory("tg-screens").toString()
        Paths.coreOverride = "/bin/true"
        val s = Settings.instance
        s.servers = listOf(
            ServerProfile(id = "a", name = "Германия", ip = "203.0.113.10", password = "secret"),
            ServerProfile(id = "b", name = "Нидерланды", ip = "198.51.100.7", password = "secret", dtlsPort = 56010, wgPort = 56011),
        )
        s.activeServerId = "a"
        s.vkHashes = listOf("AbCdEfGhIjKlMnOpQrSt", "ZyXwVuTsRqPoNmLkJiHg", "", "")
        s.onboardingDone = true
        s.themeMode = "light"
        s.tunnelMode = "tun"
        s.themePalette = "tyrn"
        s.dashWidgets = DashWidget.entries.map { it.name }
        s.hiddenWidgets = emptyList()
        TunnelController.simulate(Phase.Idle)
    }

    /** Сцена живёт в потоке Swing — там же, где приложение меняет своё состояние (иначе гонки внутри Compose). */
    private fun <T> edt(block: () -> T): T {
        var r: Result<T>? = null
        javax.swing.SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r!!.getOrThrow()
    }

    private fun shot(name: String, w: Int = 430, h: Int = 932, settle: Long = 1500, content: @Composable () -> Unit) {
        val density = 2f
        val scene = edt {
            ImageComposeScene((w * density).toInt(), (h * density).toInt(), Density(density)) {
                TrueGuardTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
        try {
            var t = 0L
            // несколько кадров, чтобы отработали LaunchedEffect и пружины
            edt { repeat(30) { scene.render(t); t += 16_000_000L } }
            Thread.sleep(settle)
            val img = edt {
                repeat(60) { scene.render(t); t += 16_000_000L }
                scene.render(t)
            }
            File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            edt { scene.close() }
        }
    }

    @Test
    fun dashboardIdle() = shot("01-dashboard-idle") { AppShell(0) }

    @Test
    fun dashboardConnected() {
        val samples = List(40) { i -> SpeedSample(20_000.0 + i * 900, 120_000.0 + (i % 7) * 40_000) }
        TunnelController.simulate(Phase.Connected, workers = 18, total = 18, up = 12_400_000, down = 286_000_000, samples = samples, seconds = 3725)
        shot("02-dashboard-connected") { AppShell(0) }
    }

    @Test
    fun dashboardConnectingDark() {
        Settings.instance.themeMode = "dark"
        TunnelController.simulate(Phase.Handshake)
        shot("03-dashboard-connecting-dark") { AppShell(0) }
    }

    @Test
    fun deploy() = shot("04-deploy") { AppShell(1) }

    @Test
    fun exceptions() = shot("05-exceptions", settle = 4000) { AppShell(2) }

    @Test
    fun logs() {
        LogStore.event("TrueGuard $APP_VERSION запущен", key = "app_start")
        LogStore.event("[ВК] Учетные данные проверены ✓", key = "creds_ok")
        LogStore.event("[DTLS] Соединение установлено ✓", key = "dtls_ok")
        LogStore.event("Flood Control (ВК ограничил ваш IP). Попробуйте позже.", key = "flood", error = true)
        shot("06-logs") { AppShell(3) }
    }

    @Test
    fun settingsMain() = shot("07-settings") { AppShell(4) }

    @Test
    fun network() = shot("08-network", h = 1600) { SettingsHost(SettingsRoute.Network) }

    @Test
    fun performance() = shot("09-performance", h = 1900) { SettingsHost(SettingsRoute.Performance) }

    @Test
    fun interfacePage() = shot("10-interface") { SettingsHost(SettingsRoute.Interface) }

    @Test
    fun interfaceDarkOcean() {
        Settings.instance.themeMode = "dark"
        Settings.instance.themePalette = "ocean"
        shot("11-interface-dark-ocean") { SettingsHost(SettingsRoute.Interface) }
    }

    @Test
    fun system() = shot("12-system", h = 1300) { SettingsHost(SettingsRoute.System) }

    @Test
    fun about() = shot("13-about", h = 1100) { SettingsHost(SettingsRoute.About) }

    @Test
    fun onboarding() = shot("14-onboarding") { Onboarding {} }

    @Test
    fun wideSettings() = shot("16-wide-settings", w = 1280, h = 860) { AppShell(4) }

    @Test
    fun wideDeploy() = shot("17-wide-deploy", w = 1280, h = 860) { AppShell(1) }

    @Test
    fun wideExceptions() = shot("18-wide-exceptions", w = 1280, h = 860, settle = 4000) { AppShell(2) }

    @Test
    fun wide() {
        TunnelController.simulate(Phase.Connected, workers = 9, total = 18, seconds = 42)
        shot("15-wide-connected", w = 1280, h = 860) { AppShell(0) }
    }
}

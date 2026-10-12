package com.trueguard.core

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontFamily
import kotlinx.serialization.json.JsonObject

/**
 * То, что общий код (core и ui) не может сделать одинаково на всех платформах.
 * Linux и Windows подставляют DesktopPlatform (src/main), Android — AndroidPlatform (android/).
 */
interface PlatformHooks {
    val isAndroid: Boolean get() = false

    /** Папка данных, если её задаёт сама платформа (Android: filesDir). */
    val dataDir: String? get() = null

    /**
     * ID устройства для сервера (по нему выдаётся пир WireGuard). На ПК ядро выводит его из machine-id,
     * на Android hostname у всех «localhost» — там нужен android_id, как в оригинальном клиенте.
     */
    val deviceId: String? get() = null

    /** Ядро wdtt-client, если платформа знает, где оно (Android: nativeLibraryDir/libwdtt.so). */
    fun findCore(): String? = null

    fun copyText(text: String) {}
    fun pasteText(): String? = null
    fun openUrl(url: String): Boolean = false

    /** Шрифт интерфейса: на компьютере — встроенный Roboto, на Android — системный. */
    val fontFamily: FontFamily get() = FontFamily.Default

    @Composable
    fun appIcon(): Painter

    /** «Динамические цвета»: цвета обоев (Android 12+) или акцент рабочего стола; null — нет. */
    @Composable
    fun dynamicColorScheme(dark: Boolean): ColorScheme? = null

    /** Список программ для «Исключений» и загрузка их значков. */
    suspend fun installedApps(refresh: Boolean = false): List<DesktopApp> = emptyList()
    suspend fun loadAppIcon(app: DesktopApp): ImageBitmap? = null

    /** Android: разрешение VPN (системный диалог); false — пользователь отказал. */
    suspend fun prepareVpn(): Boolean = true

    /** Android: поднять VpnService по запросу ядра (TUN_REQUEST) и передать ему дескриптор. */
    suspend fun establishTun(request: JsonObject): Boolean = false

    /** Android: туннель запускается/остановлен — служба в переднем плане и уведомление. */
    fun onTunnelActive(active: Boolean) {}

    /** Android: показать капчу ядра в WebView; токен или null при отмене. */
    suspend fun solveCaptcha(request: JsonObject): String? = null

    /** Отчёт об устройстве для «Собрать отчёт»; null — общий (Linux/Windows). */
    suspend fun deviceReport(): String? = null

    /** Системное уведомление; null — на этой платформе нет своего способа (используется Desktop.notify). */
    suspend fun notify(title: String, body: String, actions: Map<String, String>, urgent: Boolean): String? = null
    val hasOwnNotifications: Boolean get() = false
}

object Platform {
    @Volatile var hooks: PlatformHooks = object : PlatformHooks {
        @Composable
        override fun appIcon(): Painter = androidx.compose.ui.graphics.painter.ColorPainter(androidx.compose.ui.graphics.Color.Transparent)
    }

    val isAndroid: Boolean get() = hooks.isAndroid
}

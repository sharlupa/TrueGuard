package com.trueguard.client

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.net.VpnService
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import com.trueguard.core.APP_VERSION
import com.trueguard.core.DesktopApp
import com.trueguard.core.PlatformHooks
import com.trueguard.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Android: VpnService, WebView для капчи, ClipboardManager, программы из PackageManager. */
class AndroidPlatform(private val app: Context) : PlatformHooks {
    override val isAndroid = true
    override val dataDir: String = app.filesDir.path

    // 16 hex-символов, постоянный для приложения и пользователя устройства
    @SuppressLint("HardwareIds")
    override val deviceId: String? = android.provider.Settings.Secure.getString(app.contentResolver, android.provider.Settings.Secure.ANDROID_ID)

    override fun findCore(): String = "${app.applicationInfo.nativeLibraryDir}/libwdtt.so"

    override fun copyText(text: String) {
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("TrueGuard", text))
    }

    override fun pasteText(): String? =
        app.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString()

    override fun openUrl(url: String): Boolean {
        runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return true
    }

    @Composable
    override fun appIcon(): Painter = painterResource(R.drawable.app_logo)

    @Composable
    override fun dynamicColorScheme(dark: Boolean): ColorScheme? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val ctx = LocalContext.current
        return if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    }

    override suspend fun installedApps(refresh: Boolean): List<DesktopApp> = withContext(Dispatchers.IO) {
        val pm = app.packageManager
        pm.getInstalledApplications(0)
            .filter { it.packageName != app.packageName }
            .map { ai ->
                DesktopApp(
                    id = ai.packageName,
                    name = pm.getApplicationLabel(ai).toString(),
                    key = ai.packageName,
                    iconPath = null,
                    system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 && ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0,
                    comment = ai.packageName,
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    override suspend fun loadAppIcon(app: DesktopApp): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching { this@AndroidPlatform.app.packageManager.getApplicationIcon(app.key).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }

    override suspend fun prepareVpn(): Boolean {
        val intent = VpnService.prepare(app) ?: return true
        return VpnPermission.request(app, intent)
    }

    override fun onTunnelActive(active: Boolean) {
        if (active) {
            app.startForegroundService(Intent(app, TunnelService::class.java).setAction(TunnelService.ACTION_FOREGROUND))
        } else {
            TunnelService.instance?.stopVpn()
        }
    }

    override suspend fun establishTun(request: JsonObject): Boolean {
        val service = TunnelService.awaitInstance() ?: return false
        val addresses = (request["address"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        val dns = (request["dns"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        val mtu = (request["mtu"] as? JsonPrimitive)?.intOrNull ?: 1280
        val socket = (request["socket"] as? JsonPrimitive)?.content ?: return false
        return withContext(Dispatchers.IO) {
            val pfd = service.establish(addresses, dns, mtu, Settings.instance) ?: return@withContext false
            runCatching {
                // дескриптор TUN уходит ядру через абстрактный unix-сокет (SCM_RIGHTS)
                LocalSocket().use { s ->
                    s.connect(LocalSocketAddress(socket, LocalSocketAddress.Namespace.ABSTRACT))
                    s.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                    s.outputStream.write(1)
                    s.outputStream.flush()
                }
                true
            }.getOrDefault(false)
        }
    }

    override suspend fun solveCaptcha(request: JsonObject): String? = CaptchaBridge.solve(app, request)

    override val hasOwnNotifications = true

    override suspend fun notify(title: String, body: String, actions: Map<String, String>, urgent: Boolean): String? {
        val nm = app.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(app, if (urgent) TrueGuardApp.CHANNEL_ALERTS else TrueGuardApp.CHANNEL_TUNNEL)
            .setSmallIcon(R.drawable.ic_stat_connected)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
        if ("reconnect" in actions) {
            val reconnect = PendingIntent.getService(
                app, 1, Intent(app, TunnelService::class.java).setAction(TunnelService.ACTION_CONNECT), PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(0, actions.getValue("reconnect"), reconnect)
        }
        runCatching { nm.notify(NOTIFY_ALERT, b.build()) }
        return null
    }

    override suspend fun deviceReport(): String = listOf(
        "Версия приложения: $APP_VERSION",
        "Андроид: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "Устройство: ${Build.MANUFACTURER} ${Build.MODEL}",
        "Код устройства: ${Build.DEVICE}",
        "Продукт: ${Build.PRODUCT}",
        "Все ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}",
    ).joinToString("\n")

    companion object {
        const val NOTIFY_ALERT = 2
    }
}

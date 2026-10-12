package com.trueguard.client

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.trueguard.core.Phase
import com.trueguard.core.Settings
import com.trueguard.core.TunnelController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * VpnService TrueGuard: держит VPN-интерфейс, пока работает ядро, и уведомление в переднем плане.
 * Сама логика подключения — общая (TunnelController), служба лишь даёт ей системный VPN.
 */
class TunnelService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        // уведомление следует за состоянием туннеля
        scope.launch {
            snapshotFlow { TunnelController.phase to Settings.instance.activeServer?.name }.collectLatest { (phase, server) ->
                if (foreground) notifyState(phase, server)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> scope.launch { TunnelController.stop() }
            ACTION_CONNECT -> {
                goForeground()
                if (!TunnelController.phase.active) TunnelController.connect()
            }
            ACTION_TOGGLE -> {
                if (TunnelController.phase.active) scope.launch { TunnelController.stop() } else {
                    goForeground()
                    TunnelController.connect()
                }
            }
            else -> goForeground()
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        if (foreground) return
        foreground = true
        ServiceCompat.startForeground(
            this, NOTIFY_TUNNEL, buildNotification(TunnelController.phase, Settings.instance.activeServer?.name),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    /** Поднять VPN-интерфейс с адресами из конфига WireGuard. */
    fun establish(addresses: List<String>, dns: List<String>, mtu: Int, s: Settings): ParcelFileDescriptor? {
        closeTun()
        val b = Builder().setSession("TrueGuard").setMtu(mtu).setMetered(false)
        var hasV6 = false
        for (a in addresses) {
            val (ip, prefix) = a.split('/').let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: if (it[0].contains(':')) 128 else 32) }
            runCatching { b.addAddress(ip, prefix) }
            if (ip.contains(':')) hasV6 = true
        }
        val customDns = s.effectiveDns?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        (customDns ?: dns).forEach { runCatching { b.addDnsServer(it) } }
        b.addRoute("0.0.0.0", 0)
        if (s.blockIpv6) {
            // весь IPv6 в туннель, чтобы он не шёл мимо VPN
            if (!hasV6) runCatching { b.addAddress("fd66:6666::2", 128) }
            b.addRoute("::", 0)
        }
        // исключения: чёрный список — мимо VPN; белый — через VPN только они
        val apps = s.excludedApps.map { it.key }
        if (s.isWhitelist && apps.isNotEmpty()) {
            apps.forEach { runCatching { b.addAllowedApplication(it) } }
        } else {
            runCatching { b.addDisallowedApplication(packageName) } // сокеты ядра — мимо VPN
            apps.forEach { runCatching { b.addDisallowedApplication(it) } }
        }
        tun = b.establish()
        return tun
    }

    fun stopVpn() {
        closeTun()
        foreground = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeTun() {
        runCatching { tun?.close() }
        tun = null
    }

    override fun onRevoke() {
        // VPN выключили в настройках системы или включили другой VPN
        scope.launch { TunnelController.stop() }
    }

    override fun onDestroy() {
        closeTun()
        scope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun notifyState(phase: Phase, server: String?) {
        runCatching {
            getSystemService(android.app.NotificationManager::class.java).notify(NOTIFY_TUNNEL, buildNotification(phase, server))
        }
    }

    private fun buildNotification(phase: Phase, server: String?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, TunnelService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val title = if (phase == Phase.Connected) "Подключено" else phase.title
        return NotificationCompat.Builder(this, TrueGuardApp.CHANNEL_TUNNEL)
            .setSmallIcon(R.drawable.ic_stat_connected)
            .setContentTitle(title)
            .setContentText(server ?: "TrueGuard")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_stop, "Отключить", stop)
            .build()
    }

    companion object {
        const val ACTION_FOREGROUND = "FOREGROUND"
        const val ACTION_CONNECT = "CONNECT"
        const val ACTION_STOP = "STOP"
        const val ACTION_TOGGLE = "TOGGLE_VPN"
        const val NOTIFY_TUNNEL = 1

        @Volatile var instance: TunnelService? = null
            private set

        /** Служба запускается по onTunnelActive(true); ядро может успеть раньше — ждём. */
        suspend fun awaitInstance(): TunnelService? = withTimeoutOrNull(5_000) {
            while (instance == null) delay(50)
            instance
        }
    }
}

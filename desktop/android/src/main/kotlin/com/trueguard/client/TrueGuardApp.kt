package com.trueguard.client

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.trueguard.core.APP_VERSION
import com.trueguard.core.LogStore
import com.trueguard.core.Platform
import com.trueguard.core.Settings

class TrueGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Platform.hooks = AndroidPlatform(this)
        Settings.instance.load()
        createChannels()
        LogStore.event("TrueGuard $APP_VERSION запущен", key = "app_start")
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TUNNEL, "Состояние VPN", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Уведомления защиты (Капча)", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        const val CHANNEL_TUNNEL = "tunnel"
        const val CHANNEL_ALERTS = "alerts"
        lateinit var instance: TrueGuardApp
            private set
    }
}

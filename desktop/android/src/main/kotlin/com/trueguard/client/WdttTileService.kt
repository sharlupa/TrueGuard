package com.trueguard.client

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.trueguard.core.Phase
import com.trueguard.core.TunnelController

/** Плитка «TrueGuard» в шторке: включить/выключить VPN без открытия приложения. */
class WdttTileService : TileService() {
    override fun onStartListening() {
        val t = qsTile ?: return
        val phase = TunnelController.phase
        t.state = when {
            phase == Phase.Connected -> Tile.STATE_ACTIVE
            phase.active -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        t.subtitle = if (phase.active) phase.title else "Отключено"
        t.updateTile()
    }

    override fun onClick() {
        startForegroundService(Intent(this, TunnelService::class.java).setAction(TunnelService.ACTION_TOGGLE))
        qsTile?.apply {
            state = if (TunnelController.phase.active) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            updateTile()
        }
    }
}

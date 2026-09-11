package com.marcos.bassenhancer.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.marcos.bassenhancer.MainActivity
import com.marcos.bassenhancer.R
import com.marcos.bassenhancer.core.CaptureMode
import com.marcos.bassenhancer.core.PrefsRepository

/**
 * Quick Settings toggle.
 *
 * Turning it off is always possible from here. Turning it on only works without
 * opening the app in Visualizer mode -- playback capture needs the system consent
 * dialog, so the tile falls back to launching the app for that.
 */
class BassTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val prefs = PrefsRepository.get(this)
        val running = ServiceState.state.value == RunState.RUNNING

        if (running) {
            prefs.update { it.copy(enabled = false) }
            BassService.stop(this)
            refresh()
            return
        }

        if (prefs.current.captureMode == CaptureMode.VISUALIZER) {
            prefs.update { it.copy(enabled = true) }
            BassService.startVisualizerMode(this)
            refresh()
        } else {
            val intent = Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_AUTO_START, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    android.app.PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val running = ServiceState.state.value == RunState.RUNNING
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (running) R.string.tile_on else R.string.tile_off)
        }
        tile.updateTile()
    }

    companion object {
        fun requestUpdate(context: Context) {
            runCatching {
                TileService.requestListeningState(
                    context,
                    android.content.ComponentName(context, BassTileService::class.java),
                )
            }
        }
    }
}

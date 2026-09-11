package com.marcos.bassenhancer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.marcos.bassenhancer.core.CaptureMode
import com.marcos.bassenhancer.core.PrefsRepository

/**
 * Restarts the service after a reboot when the user asked for it.
 *
 * Only possible in Visualizer mode: a MediaProjection grant cannot survive a
 * reboot, so playback capture always needs one tap in the app after boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        val prefs = PrefsRepository.get(context).current
        if (!prefs.startOnBoot || !prefs.enabled) return
        if (prefs.captureMode != CaptureMode.VISUALIZER) return
        BassService.startVisualizerMode(context)
    }
}

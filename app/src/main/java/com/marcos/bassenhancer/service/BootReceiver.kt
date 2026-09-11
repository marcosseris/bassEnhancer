package com.marcos.bassenhancer.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.marcos.bassenhancer.MainActivity
import com.marcos.bassenhancer.R
import com.marcos.bassenhancer.core.PrefsRepository

private const val CHANNEL_ID = "bass_enhancer_boot"
private const val NOTIFICATION_ID = 42

/**
 * Offers to resume after a reboot.
 *
 * Deliberately a notification rather than an automatic start. Android 11+ denies
 * microphone access to any foreground service launched from the background, and a
 * MediaProjection grant cannot survive a reboot either -- so a service started here
 * would come up alive but permanently deaf. One tap is the honest alternative.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = PrefsRepository.get(context).current
        if (!prefs.startOnBoot) return

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.channel_boot_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.channel_boot_desc)
                    setShowBadge(false)
                },
            )
        }

        val start = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_AUTO_START, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        nm.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.boot_title))
                .setContentText(context.getString(R.string.boot_text))
                .setSmallIcon(R.drawable.ic_tile)
                .setContentIntent(start)
                .setAutoCancel(true)
                .build(),
        )
    }
}

package com.kcliang.autostream

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Foreground service (type mediaPlayback) kept alive while the car session is active. */
class PlaybackForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_autostream)
            .setContentTitle(getString(R.string.fgs_title))
            .setContentText(getString(R.string.fgs_text))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed", t)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, getString(R.string.fgs_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }

    companion object {
        private const val TAG = "PlaybackFGS"
        private const val CHANNEL_ID = "autostream_playback"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, PlaybackForegroundService::class.java)
                )
            } catch (t: Throwable) {
                // e.g. ForegroundServiceStartNotAllowedException on Android 12+ when in background.
                Log.w(TAG, "Unable to start foreground service", t)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, PlaybackForegroundService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "stopService failed", t)
            }
        }
    }
}

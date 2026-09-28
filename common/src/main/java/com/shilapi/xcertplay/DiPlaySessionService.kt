package com.shilapi.xcertplay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.shilapi.xcertplay.host.R

/** Keeps an explicitly started connection alive when another car app is in the foreground. */
class DiPlaySessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            CarPlayBackgroundSession.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.misc_channel_carplay_connection), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, CarPlayHostActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DiPlaySessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle("DiPlay")
            .setContentText(getString(R.string.misc_notification_connection_running))
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.misc_disconnect), stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (Build.VERSION.SDK_INT >= 30 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            // Android 14+ 校验 connectedDevice 的先决条件（蓝牙权限/USB 授权），首次有线连接时
            // 两者可能都未满足而抛 ForegroundServiceTypeNotAllowedException；退回无类型声明避免进程崩溃。
            runCatching { startForeground(1, notification, types) }
                .recoverCatching { startForeground(1, notification) }
                .onFailure { android.util.Log.w("DiPlaySession", "startForeground rejected", it) }
        } else startForeground(1, notification)
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        // BYD's recents force-stops the package ~10 ms after removing the task: end guidance first.
        com.shilapi.xcertplay.hud.BydNavigationOutputs.endNow()
        CarPlayBackgroundSession.stop()
        stopSelf()
    }
    companion object {
        const val ACTION_STOP = "com.shihab.diplay.DISCONNECT"
        private const val CHANNEL = "diplay_connection"
    }
}

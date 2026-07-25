package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "flashshare_transfer_channel"
        const val NOTIFICATION_ID_PROGRESS = 1001
        const val NOTIFICATION_ID_SUCCESS = 1002
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FlashShare نقل الملفات",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "إشعارات تقدم واكتمال نقل الملفات"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showTransferSuccessNotification(fileName: String, peerName: String, totalBytesFormatted: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("✅ تم اكتمال النقل بنجاح!")
            .setContentText("تم استلام/إرسال: $fileName ($totalBytesFormatted) مع $peerName")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        notificationManager.notify(NOTIFICATION_ID_SUCCESS, notification)
    }

    fun showTransferProgressNotification(fileName: String, progressPercent: Int, speedMBps: Double) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("⚡ جاري نقل الملفات عبر الواي فاي")
            .setContentText("$fileName ($progressPercent% - ${String.format("%.1f", speedMBps)} MB/s)")
            .setProgress(100, progressPercent, false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(NOTIFICATION_ID_PROGRESS, notification)
    }

    fun cancelProgressNotification() {
        notificationManager.cancel(NOTIFICATION_ID_PROGRESS)
    }
}

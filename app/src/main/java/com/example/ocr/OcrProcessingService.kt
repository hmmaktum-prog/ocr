package com.example.ocr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Minimal Foreground Service — keeps the process alive while OCR is running in background.
 * Started when processing begins, stopped when processing ends (success/fail/cancel).
 *
 * This prevents Android from killing the app process when the user switches to another app,
 * while the heavy OCR/PDF work is in progress.
 */
class OcrProcessingService : Service() {

    companion object {
        private const val TAG = "OcrProcessingService"
        const val CHANNEL_ID = "ocr_processing_channel"
        const val NOTIFICATION_ID = 2001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "UPDATE_PROGRESS") {
            val current = intent.getIntExtra("CURRENT_PAGE", 0)
            val total = intent.getIntExtra("TOTAL_PAGES", 0)
            updateProgress(current, total)
        } else {
            Log.i(TAG, "Processing foreground service started")
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        // START_NOT_STICKY: do not restart if killed — MainActivity manages the lifecycle
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Processing foreground service stopped")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_processing_title))
            .setContentText(getString(R.string.notif_processing_text))
            .setSmallIcon(R.drawable.ic_document)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun updateProgress(currentPage: Int, totalPages: Int) {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_processing_title))
            .setContentText("Processing page $currentPage of $totalPages")
            .setSmallIcon(R.drawable.ic_document)
            .setContentIntent(openAppIntent)
            .setProgress(totalPages, currentPage, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
            
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}

package com.tuntori.trackdrop

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class TrackDropMessagingService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID = "trackdrop_routes"
        const val NOTIFICATION_ID = 1001
        const val PENDING_URL_KEY = "pending_notification_url"
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val url = remoteMessage.data["url"] ?: return

        val provider = ProviderRegistry.getProviderForUrl(url) ?: run {
            if (ForegroundManager.listener != null) {
                ForegroundManager.listener?.invoke(null)
            } else {
                showNotification("Error", "Unsupported URL format.", null)
            }
            return
        }

        // Check if the app is in the foreground!
        if (ForegroundManager.listener != null) {
            // App is open. Fetch and send data directly to the UI.
            try {
                val result = provider.fetchTour(url)
                ForegroundManager.listener?.invoke(result)
            } catch (e: Exception) {
                ForegroundManager.listener?.invoke(null)
            }
        } else {
            // App is in background. Don't fetch here — just show a notification
            // carrying the URL. The route will be fetched when the user taps it
            // or when the app returns to the foreground (e.g. screen was off).
            // This avoids stale cached data and battery waste.
            // Store the URL so MainActivity can auto-consume it if the app
            // comes to the foreground without a notification tap.
            getSharedPreferences("trackdrop_prefs", MODE_PRIVATE)
                .edit()
                .putString(PENDING_URL_KEY, url)
                .apply()
            showNotification("New track received from PC", "Tap to open.", url)
        }
    }

    private fun showNotification(title: String, message: String, url: String?) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Received Tracks", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("FROM_NOTIFICATION", true)
            if (url != null) {
                putExtra("NOTIFICATION_URL", url)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        getSharedPreferences("trackdrop_prefs", MODE_PRIVATE).edit().putString("fcm_token", token).apply()
    }
}

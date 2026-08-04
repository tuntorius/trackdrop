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
import java.io.File

// This object acts as a bridge between the Service and the UI
object ForegroundManager {
    var listener: ((FetchResult?) -> Unit)? = null
}

class TrackDropMessagingService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID = "trackdrop_routes"
        const val CACHE_FILE = "last_received_track.json"
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val url = remoteMessage.data["url"] ?: return
        
        val provider = ProviderRegistry.getProviderForUrl(url) ?: run {
            if (ForegroundManager.listener != null) {
                ForegroundManager.listener?.invoke(null)
            } else {
                showNotification("Error", "Unsupported URL format.")
            }
            return
        }

        try {
            val result = provider.fetchTour(url)
            
            // Check if the app is in the foreground!
            if (ForegroundManager.listener != null) {
                // App is open. Send data directly to the UI.
                ForegroundManager.listener?.invoke(result)
            } else {
                // App is in background. Serialize the whole result to a JSON file.
                val json = org.json.JSONObject().apply {
                    put("gpx", result.gpx)
                    put("url", url)
                    put("filename", result.filename)
                    put("name", result.tour.name)
                    put("distance", result.tour.distance)
                    put("up", result.tour.up)
                    put("down", result.tour.down)
                    val ptsArray = org.json.JSONArray()
                    result.points.forEach { p ->
                        val pt = org.json.JSONArray()
                        pt.put(p.first)
                        pt.put(p.second)
                        ptsArray.put(pt)
                    }
                    put("points", ptsArray)
                }
                File(cacheDir, CACHE_FILE).writeText(json.toString())
                
                showNotification("Track received: ${result.tour.name}", "Tap to open.")
            }
        } catch (e: Exception) {
            if (ForegroundManager.listener != null) {
                ForegroundManager.listener?.invoke(null) // Tell UI it failed
            } else {
                showNotification("Failed to fetch track", e.message ?: "Unknown error")
            }
        }
    }

    private fun showNotification(title: String, message: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Received Tracks", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("FROM_NOTIFICATION", true)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        getSharedPreferences("trackdrop_prefs", MODE_PRIVATE).edit().putString("fcm_token", token).apply()
    }
}
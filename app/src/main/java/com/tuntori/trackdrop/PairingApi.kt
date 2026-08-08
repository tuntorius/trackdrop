package com.tuntori.trackdrop

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Registers a pairing code with the TrackDrop Cloud Function so a browser extension
 * can push tracks to this device via FCM.
 */
object PairingApi {
    private const val TAG = "PairingApi"
    private const val REGISTER_URL = "https://us-central1-trackdrop-ea99a.cloudfunctions.net/registerPairingCode"

    /**
     * Registers the code + FCM token with the Cloud Function.
     * Returns null on success, or an error message on failure.
     * Must be called off the main thread.
     */
    fun registerPairingCode(code: String, token: String): String? {
        return try {
            val url = URL(REGISTER_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }

            val jsonBody = JSONObject().apply {
                put("code", code)
                put("token", token)
            }.toString()

            conn.outputStream.use { it.write(jsonBody.toByteArray()) }
            val responseCode = conn.responseCode
            conn.disconnect()

            if (responseCode == 200) {
                Log.d(TAG, "Pairing code registered!")
                null
            } else {
                Log.e(TAG, "Failed to register code: $responseCode")
                "Failed to register pairing code."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering code", e)
            "Network error registering code."
        }
    }
}
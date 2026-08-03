package com.tuntori.trackdrop

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// --- Shared Models ---
data class Tour(val name: String, val distance: Double, val up: Double, val down: Double)
data class FetchResult(val tour: Tour, val gpx: String, val filename: String)
class ApiException(message: String) : Exception(message)

// --- Provider Interface ---
interface RouteProvider {
    fun isValidUrl(url: String): Boolean
    fun fetchTour(url: String): FetchResult
}

// --- Registry ---
object ProviderRegistry {
    private val providers = listOf(KomootService, RideWithGpsService)

    fun getProviderForUrl(url: String): RouteProvider? {
        return providers.find { it.isValidUrl(url) }
    }
}

// --- Shared Network Utilities ---
object NetworkUtils {
    fun fetchJson(urlString: String): JSONObject {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "TrackDrop/1.0 (Android)")
        }
        try {
            if (conn.responseCode == 403) throw ApiException("Access denied — is the share token still valid?")
            if (conn.responseCode == 404) throw ApiException("Route not found. Check the URL.")
            if (conn.responseCode != 200) throw ApiException("API error (${conn.responseCode}).")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[<>:\"/\\\\|?*]+"), "").replace(Regex("\\s+"), " ").trim()
    }

    fun escapeXml(input: String): String = input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
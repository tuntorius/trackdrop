package com.tuntori.trackdrop

import androidx.annotation.StringRes
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// --- Shared Models ---
data class Tour(val name: String, val distance: Double, val up: Double, val down: Double)
data class FetchResult(val tour: Tour, val gpx: String, val filename: String, val points: List<Pair<Double, Double>>, val url: String)

/**
 * A failure that should be shown to the user. Carries a string resource instead
 * of a ready-made message so the UI layer can resolve it in the current locale.
 */
class ApiException(
    @StringRes val messageRes: Int,
    vararg val formatArgs: Any
) : Exception()

// --- Provider Interface ---
interface RouteProvider {
    fun isValidUrl(url: String): Boolean
    fun fetchTour(url: String): FetchResult

    /**
     * Package name of this provider's Android app, or null if it has no mobile app.
     * Used to filter the source provider's own app out of the "Drop to…" grid,
     * since dropping a route back into the same app would just duplicate it.
     */
    val sourceAppPackage: String?
}

// --- Registry ---
object ProviderRegistry {
    private val providers = listOf(KomootService, RideWithGpsService, OpenRunnerService)

    fun getProviderForUrl(url: String): RouteProvider? {
        return providers.find { it.isValidUrl(url) }
    }
}

// --- Shared Network Utilities ---
object NetworkUtils {
    fun fetchJson(
        urlString: String,
        @StringRes forbiddenMessageRes: Int = R.string.error_access_denied_share_token
    ): JSONObject {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "TrackDrop/1.0 (Android)")
        }
        try {
            if (conn.responseCode == 403) throw ApiException(forbiddenMessageRes)
            if (conn.responseCode == 404) throw ApiException(R.string.error_route_not_found)
            if (conn.responseCode != 200) throw ApiException(R.string.error_api, conn.responseCode)
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
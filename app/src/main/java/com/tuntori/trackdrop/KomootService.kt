package com.tuntori.trackdrop

import android.net.Uri

object KomootService : RouteProvider {
    override fun isValidUrl(url: String): Boolean {
        return url.contains("komoot.") && url.contains("/tour/")
    }

    override fun fetchTour(url: String): FetchResult {
        val uri = Uri.parse(url)
        val tourMatch = Regex("""/tour/(\d+)""").find(uri.path ?: "")
            ?: throw ApiException("No tour ID found in URL.")
        val tourId = tourMatch.groupValues[1]
        val shareToken = uri.getQueryParameter("share_token")
            ?: throw ApiException("No share_token found. The route must be publicly shared.")

        val tourUrl = "https://api.komoot.de/v007/tours/$tourId?share_token=$shareToken"
        val tourJson = NetworkUtils.fetchJson(tourUrl)
        val tourName = tourJson.optString("name", "Komoot Tour")
        val distance = tourJson.optDouble("distance", 0.0)
        val up = tourJson.optDouble("elevation_up", 0.0)
        val down = tourJson.optDouble("elevation_down", 0.0)

        val coordsUrl = tourJson.optJSONObject("_links")
            ?.optJSONObject("coordinates")
            ?.optString("href")
            ?: "https://api.komoot.de/v007/tours/$tourId/coordinates?share_token=$shareToken"

        val coordsJson = NetworkUtils.fetchJson(coordsUrl)
        val items = coordsJson.optJSONArray("items")
            ?: throw ApiException("No coordinate data in this route.")

        val gpx = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\" ?>\n")
            append("<gpx version=\"1.1\" creator=\"TrackDrop\"\n")
            append("  xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            append("  <metadata>\n    <name>${NetworkUtils.escapeXml(tourName)}</name>\n  </metadata>\n")
            append("  <rte>\n")
            for (i in 0 until items.length()) {
                val c = items.getJSONObject(i)
                val lat = c.opt("lat") ?: c.opt("lat_rounded") ?: 0
                val lng = c.opt("lng") ?: c.opt("lng_rounded") ?: 0
                val alt = c.opt("alt") ?: 0
                append("    <rtept lat=\"$lat\" lon=\"$lng\"><ele>$alt</ele></rtept>\n")
            }
            append("  </rte>\n</gpx>")
        }

        return FetchResult(Tour(tourName, distance, up, down), gpx, "${NetworkUtils.sanitizeFilename(tourName)}.gpx")
    }
}
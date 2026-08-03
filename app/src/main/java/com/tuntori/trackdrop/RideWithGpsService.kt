package com.tuntori.trackdrop

import android.net.Uri

object RideWithGpsService : RouteProvider {
    override fun isValidUrl(url: String): Boolean {
        return url.contains("ridewithgps.com/routes/")
    }

    override fun fetchTour(url: String): FetchResult {
        val uri = Uri.parse(url)
        val routeMatch = Regex("""/routes/(\d+)""").find(uri.path ?: "")
            ?: throw ApiException("No RideWithGPS route ID found in URL.")
        val routeId = routeMatch.groupValues[1]

        // Extract privacy_code if it exists
        val privacyCode = uri.getQueryParameter("privacy_code")
        
        // Build the .json URL correctly, appending the privacy_code if present
        val jsonUrl = if (privacyCode != null) {
            "https://ridewithgps.com/routes/$routeId.json?privacy_code=$privacyCode"
        } else {
            "https://ridewithgps.com/routes/$routeId.json"
        }

        val json = NetworkUtils.fetchJson(jsonUrl)

        val routeName = json.optString("name", "RideWithGPS Route $routeId")
        val distance = json.optDouble("distance", 0.0)
        val up = json.optDouble("elevation_gain", 0.0)
        val down = json.optDouble("elevation_loss", 0.0)

        val items = json.optJSONArray("track_points")
            ?: throw ApiException("No coordinate data in this route.")

        val gpx = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\" ?>\n")
            append("<gpx version=\"1.1\" creator=\"TrackDrop\"\n")
            append("  xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            append("  <metadata>\n    <name>${NetworkUtils.escapeXml(routeName)}</name>\n  </metadata>\n")
            append("  <rte>\n")
            for (i in 0 until items.length()) {
                val c = items.getJSONObject(i)
                // RWGPS uses x for longitude, y for latitude, e for elevation
                val lat = c.opt("y") ?: 0
                val lng = c.opt("x") ?: 0
                val alt = c.opt("e") ?: 0
                append("    <rtept lat=\"$lat\" lon=\"$lng\"><ele>$alt</ele></rtept>\n")
            }
            append("  </rte>\n</gpx>")
        }

        return FetchResult(Tour(routeName, distance, up, down), gpx, "${NetworkUtils.sanitizeFilename(routeName)}.gpx")
    }
}
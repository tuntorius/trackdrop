package com.tuntori.trackdrop

import android.net.Uri

object RideWithGpsService : RouteProvider {
    override fun isValidUrl(url: String): Boolean {
        return url.contains("ridewithgps.com/routes/") || url.contains("ridewithgps.com/trips/")
    }

    override fun fetchTour(url: String): FetchResult {
        val uri = Uri.parse(url)
        // Match either /routes/ or /trips/ and capture the ID
        val routeMatch = Regex("""/(routes|trips)/(\d+)""").find(uri.path ?: "")
            ?: throw ApiException("No RideWithGPS route/trip ID found in URL.")

        // The ID is now in group 2 because (routes|trips) is group 1
        val routeId = routeMatch.groupValues[2]

        // Extract privacy_code if it exists
        val privacyCode = uri.getQueryParameter("privacy_code")
        
        // Build the .json URL correctly. We can just use "routes" for the API endpoint,
        // or we can use the actual path. RWGPS API usually normalizes this, but let's be safe.
        val pathType = routeMatch.groupValues[1] 
        val jsonUrl = if (privacyCode != null) {
            "https://ridewithgps.com/$pathType/$routeId.json?privacy_code=$privacyCode"
        } else {
            "https://ridewithgps.com/$pathType/$routeId.json"
        }

        val json = NetworkUtils.fetchJson(jsonUrl)

        val routeName = json.optString("name", "RideWithGPS Route $routeId")
        val distance = json.optDouble("distance", 0.0)
        val up = json.optDouble("elevation_gain", 0.0)
        val down = json.optDouble("elevation_loss", 0.0)

        val items = json.optJSONArray("track_points")
            ?: throw ApiException("No coordinate data in this route.")

        val points = mutableListOf<Pair<Double, Double>>()
        val gpx = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\" ?>\n")
            append("<gpx version=\"1.1\" creator=\"TrackDrop\"\n")
            append("  xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            append("  <metadata>\n    <name>${NetworkUtils.escapeXml(routeName)}</name>\n  </metadata>\n")
            append("  <rte>\n")
            for (i in 0 until items.length()) {
                val c = items.getJSONObject(i)

                // Skip points that don't have GPS coordinates
                if (!c.has("x") || !c.has("y")) continue
                
                // RWGPS uses x for longitude, y for latitude, e for elevation
                val lat = c.optDouble("y", 0.0)
                val lng = c.optDouble("x", 0.0)
                val alt = c.optDouble("e", 0.0)

                points.add(Pair(lat, lng)) // Save for the UI

                append("    <rtept lat=\"$lat\" lon=\"$lng\"><ele>$alt</ele></rtept>\n")
            }
            append("  </rte>\n</gpx>")
        }

        return FetchResult(Tour(routeName, distance, up, down), gpx, "${NetworkUtils.sanitizeFilename(routeName)}.gpx", points, url)
    }
}
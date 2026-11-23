package com.tuntori.trackdrop

import android.net.Uri
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OpenRunner (openrunner.com) route provider.
 *
 * Public routes only. OpenRunner does not expose share tokens or any other way
 * to grant access to a private route, so the route-details URL is the only URL
 * scheme we support. Language prefixes such as /en/ are ignored.
 *
 * Data comes from OpenRunner's public API, which returns:
 *  - shape.full_encoded: a Google-encoded polyline (precision 5)
 *  - elevation.full_encoded: a polyline-encoded sequence of elevation deltas,
 *    one sample every elevation.interval metres along the route
 */
object OpenRunnerService : RouteProvider {
    override val sourceAppPackage: String? = "com.openrunner.android"

    private const val API_BASE = "https://api.openrunner.com/api/v2/routes"
    private val ROUTE_PATH = Regex("""/route-details/(\d+)""")

    override fun isValidUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: return false
        if (host != "openrunner.com" && !host.endsWith(".openrunner.com")) return false
        return ROUTE_PATH.containsMatchIn(uri.path ?: "")
    }

    override fun fetchTour(url: String): FetchResult {
        val uri = Uri.parse(url)
        val routeId = ROUTE_PATH.find(uri.path ?: "")
            ?.groupValues
            ?.get(1)
            ?: throw ApiException(R.string.error_openrunner_no_route_id)

        val json = NetworkUtils.fetchJson(
            "$API_BASE/$routeId",
            forbiddenMessageRes = R.string.error_openrunner_access_denied
        )

        val route = json.optJSONObject("data")
            ?: throw ApiException(R.string.error_openrunner_unexpected_response)

        if (route.optBoolean("is_private", false)) {
            throw ApiException(R.string.error_openrunner_private_route)
        }

        val routeName = route.optString("name", "OpenRunner Route $routeId")
        val distance = route.optDouble("length", 0.0)

        val elevation = route.optJSONObject("elevation")
        val up = elevation?.optDouble("ascent", 0.0) ?: 0.0
        val down = elevation?.optDouble("descent", 0.0) ?: 0.0

        val shape = route.optJSONObject("shape")
            ?: throw ApiException(R.string.error_no_coordinates)
        val encodedShape = shape.optString("full_encoded", "")
        if (encodedShape.isEmpty()) throw ApiException(R.string.error_no_coordinates)

        val points = decodePolyline(encodedShape)

        val elevationSamples = elevation?.optString("full_encoded", "")
            ?.takeIf { it.isNotEmpty() }
            ?.let { decodeElevation(it) }
        val elevationInterval = elevation?.optInt("interval", 0) ?: 0

        val gpxPoints = mutableListOf<Pair<Double, Double>>()
        val gpx = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\" ?>\n")
            append("<gpx version=\"1.1\" creator=\"TrackDrop\"\n")
            append("  xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            append("  <metadata>\n    <name>${NetworkUtils.escapeXml(routeName)}</name>\n  </metadata>\n")
            append("  <rte>\n")

            var distanceAlongRoute = 0.0
            for ((index, point) in points.withIndex()) {
                val (lat, lng) = point

                if (index > 0) {
                    val previous = points[index - 1]
                    distanceAlongRoute += distanceMeters(previous.first, previous.second, lat, lng)
                }

                val elevationMeters = elevationSamples
                    ?.takeIf { elevationInterval > 0 }
                    ?.let { samples ->
                        val sampleIndex = (distanceAlongRoute / elevationInterval)
                            .toInt()
                            .coerceIn(0, samples.lastIndex)
                        samples[sampleIndex]
                    }

                gpxPoints.add(Pair(lat, lng)) // Save for the UI

                append("    <rtept lat=\"$lat\" lon=\"$lng\">")
                if (elevationMeters != null) append("<ele>$elevationMeters</ele>")
                append("</rtept>\n")
            }
            append("  </rte>\n</gpx>")
        }

        return FetchResult(
            Tour(routeName, distance, up, down),
            gpx,
            "${NetworkUtils.sanitizeFilename(routeName)}.gpx",
            gpxPoints,
            url
        )
    }

    /** Decodes a Google-encoded polyline (precision 5) into lat/lng pairs. */
    private fun decodePolyline(encoded: String): List<Pair<Double, Double>> {
        val points = mutableListOf<Pair<Double, Double>>()
        var index = 0
        var lat = 0
        var lng = 0

        while (index < encoded.length) {
            val latValue = nextEncodedValue(encoded, index)
            lat += latValue.first
            index = latValue.second

            val lngValue = nextEncodedValue(encoded, index)
            lng += lngValue.first
            index = lngValue.second

            points.add(Pair(lat / 1e5, lng / 1e5))
        }

        return points
    }

    /** Decodes OpenRunner's elevation sequence into cumulative metre values. */
    private fun decodeElevation(encoded: String): IntArray {
        val values = mutableListOf<Int>()
        var index = 0
        var current = 0

        while (index < encoded.length) {
            val (delta, nextIndex) = nextEncodedValue(encoded, index)
            current += delta
            values.add(current)
            index = nextIndex
        }

        return values.toIntArray()
    }

    /** Reads one signed value of the polyline encoding starting at [start]. */
    private fun nextEncodedValue(encoded: String, start: Int): Pair<Int, Int> {
        var index = start
        var result = 0
        var shift = 0
        var byte: Int

        do {
            byte = encoded[index++].code - 63
            result = result or ((byte and 0x1f) shl shift)
            shift += 5
        } while (byte >= 0x20)

        val value = if (result and 1 == 1) (result shr 1).inv() else result shr 1
        return Pair(value, index)
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * earthRadius * atan2(sqrt(a), sqrt(1 - a))
    }
}

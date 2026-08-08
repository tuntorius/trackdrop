package com.tuntori.trackdrop

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Serializes/deserializes a received track to/from a JSON cache file.
 * Used by TrackDropMessagingService (app in background) and MainActivity (notification tap).
 */
object TrackCache {
    const val CACHE_FILE = "last_received_track.json"
    private const val TAG = "TrackCache"

    fun save(context: Context, result: FetchResult, url: String) {
        val json = JSONObject().apply {
            put("gpx", result.gpx)
            put("url", url)
            put("filename", result.filename)
            put("name", result.tour.name)
            put("distance", result.tour.distance)
            put("up", result.tour.up)
            put("down", result.tour.down)
            val ptsArray = JSONArray()
            result.points.forEach { p ->
                val pt = JSONArray()
                pt.put(p.first)
                pt.put(p.second)
                ptsArray.put(pt)
            }
            put("points", ptsArray)
        }
        File(context.cacheDir, CACHE_FILE).writeText(json.toString())
    }

    /**
     * Loads and deletes the cached track. Returns null if no cache exists or it is unreadable.
     */
    fun load(context: Context): FetchResult? {
        val cacheFile = File(context.cacheDir, CACHE_FILE)
        if (!cacheFile.exists()) return null

        return try {
            val json = JSONObject(cacheFile.readText())
            val tour = Tour(
                name = json.getString("name"),
                distance = json.getDouble("distance"),
                up = json.getDouble("up"),
                down = json.getDouble("down")
            )

            val ptsArray = json.getJSONArray("points")
            val points = mutableListOf<Pair<Double, Double>>()
            for (i in 0 until ptsArray.length()) {
                val pt = ptsArray.getJSONArray(i)
                points.add(Pair(pt.getDouble(0), pt.getDouble(1)))
            }

            FetchResult(
                tour = tour,
                gpx = json.getString("gpx"),
                filename = json.getString("filename"),
                points = points,
                url = json.optString("url", "Received via PC")
            ).also {
                cacheFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cached track", e)
            null
        }
    }
}
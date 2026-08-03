package com.tuntori.trackdrop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class RoutePreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var points: List<Pair<Double, Double>> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5A3C") // Komoot Coral Red
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()

    fun setPoints(pts: List<Pair<Double, Double>>) {
        points = pts
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.size < 2) return

        // Find bounding box of the route
        var minLat = points[0].first
        var maxLat = points[0].first
        var minLng = points[0].second
        var maxLng = points[0].second

        for (p in points) {
            minLat = minOf(minLat, p.first)
            maxLat = maxOf(maxLat, p.first)
            minLng = minOf(minLng, p.second)
            maxLng = maxOf(maxLng, p.second)
        }

        // Convert ranges to Float immediately to avoid type mismatches
        val latRange = (maxLat - minLat).coerceAtLeast(0.0001).toFloat()
        val lngRange = (maxLng - minLng).coerceAtLeast(0.0001).toFloat()

        val w = width.toFloat()
        val h = height.toFloat()
        val padding = 40f 

        // Calculate scale to fit the route inside the view, preserving aspect ratio
        val scaleX = (w - padding * 2) / lngRange
        val scaleY = (h - padding * 2) / latRange
        val scale = minOf(scaleX, scaleY)

        // Center the route in the view
        val offsetX = (w - (lngRange * scale)) / 2f
        val offsetY = (h - (latRange * scale)) / 2f

        path.reset()
        points.forEachIndexed { index, p ->
            // Convert coordinates to Float before multiplying
            val x = offsetX + ((p.second - minLng).toFloat() * scale)
            // Invert Y because Canvas Y goes down, but Latitude goes up
            val y = h - (offsetY + ((p.first - minLat).toFloat() * scale))
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        canvas.drawPath(path, paint)
    }
}
package com.tuntori.trackdrop

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import android.content.pm.ResolveInfo
import java.io.File

object ShareService {
    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun getGpxApps(context: Context): List<ResolveInfo> {
        val mimeTypes = listOf("application/gpx+xml", "application/xml", "text/xml")
        val appMap = mutableMapOf<String, ResolveInfo>()
        
        // Filter out system sharing apps
        val excludedPackages = listOf(
            context.packageName,
            "com.android.bluetooth",
            "com.google.android.gms", // Quick Share
            "com.samsung.android.app.sharelive" // Samsung Quick Share
        )

        for (mimeType in mimeTypes) {
            val viewIntent = Intent(Intent.ACTION_VIEW).apply { type = mimeType }
            context.packageManager.queryIntentActivities(viewIntent, 0).forEach { info ->
                if (info.activityInfo.packageName !in excludedPackages) {
                    appMap[info.activityInfo.packageName] = info
                }
            }

            val sendIntent = Intent(Intent.ACTION_SEND).apply { type = mimeType }
            context.packageManager.queryIntentActivities(sendIntent, 0).forEach { info ->
                if (info.activityInfo.packageName !in excludedPackages) {
                    appMap[info.activityInfo.packageName] = info
                }
            }
        }

        return appMap.values.toList()
    }

    fun openGpxInPackage(context: Context, gpx: String, filename: String, packageName: String) {
        val cacheDir = context.cacheDir
        val file = File(cacheDir, filename)
        file.writeText(gpx)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/gpx+xml")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            clipData = ClipData.newRawUri("gpx", uri)
            setPackage(packageName) // Force it to open the specific favorite app
        }
        context.startActivity(intent)
    }

    fun openGpx(context: Context, gpx: String, filename: String, focused: Boolean) {
        val cacheDir = context.cacheDir
        val file = File(cacheDir, filename)
        file.writeText(gpx)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        val baseIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/gpx+xml")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            clipData = ClipData.newRawUri("gpx", uri)
            if (focused) setPackage("app.organicmaps")
        }

        val launchIntent = if (focused) baseIntent else Intent.createChooser(baseIntent, "Open GPX with…").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(launchIntent)
    }
}
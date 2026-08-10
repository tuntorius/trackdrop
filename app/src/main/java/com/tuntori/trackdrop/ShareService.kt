package com.tuntori.trackdrop

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import android.content.pm.ResolveInfo
import java.io.File

data class GpxApp(
    val packageName: String,
    val name: CharSequence,
    val icon: Drawable,
    val resolveInfo: ResolveInfo
)

object ShareService {
    fun getGpxApps(context: Context, gpx: String, filename: String): List<GpxApp> {
        val cacheDir = context.cacheDir
        val file = File(cacheDir, filename)
        file.writeText(gpx)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        val pm = context.packageManager

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/gpx+xml")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("gpx", uri)
        }

        return pm.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map {
                val appInfo = it.activityInfo.applicationInfo

                GpxApp(
                    packageName = appInfo.packageName,
                    name = appInfo.loadLabel(pm),
                    icon = appInfo.loadIcon(pm),
                    resolveInfo = it
                )
            }
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
            setPackage(packageName)
        }
        context.startActivity(intent)
    }
}
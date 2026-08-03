package com.tuntori.trackdrop

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
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
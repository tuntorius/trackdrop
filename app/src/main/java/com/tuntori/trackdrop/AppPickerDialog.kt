package com.tuntori.trackdrop

import android.app.AlertDialog
import android.content.Context
import android.content.pm.ResolveInfo
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView

/**
 * Shows a dialog listing all GPX-capable apps and returns the selected package name.
 */
object AppPickerDialog {

    /**
     * @param onAppSelected Callback with the selected package name and app label.
     * @param onNoApps Called if no GPX-capable apps are installed.
     */
    fun show(
        context: Context,
        onAppSelected: (packageName: String, appLabel: String) -> Unit,
        onNoApps: (() -> Unit)? = null
    ) {
        val apps = ShareService.getGpxApps(context)
        if (apps.isEmpty()) {
            onNoApps?.invoke()
            return
        }

        val adapter = object : ArrayAdapter<ResolveInfo>(
            context, android.R.layout.activity_list_item, android.R.id.text1, apps
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val item = getItem(position)!!
                view.findViewById<android.widget.TextView>(android.R.id.text1).text =
                    item.loadLabel(context.packageManager)
                view.findViewById<android.widget.ImageView>(android.R.id.icon)
                    .setImageDrawable(item.loadIcon(context.packageManager))
                view.setPadding(32, 24, 32, 24)
                return view
            }
        }

        val dialogView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        val listView = ListView(context).apply {
            this.adapter = adapter
        }
        dialogView.addView(listView)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Choose Favorite App")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val selectedApp = apps[position]
            val packageName = selectedApp.activityInfo.packageName
            val appLabel = selectedApp.loadLabel(context.packageManager).toString()
            onAppSelected(packageName, appLabel)
            dialog.dismiss()
        }
        dialog.show()
    }
}
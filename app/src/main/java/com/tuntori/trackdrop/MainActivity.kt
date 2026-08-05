package com.tuntori.trackdrop

import android.Manifest
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging
import com.tuntori.trackdrop.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isFetching = false
    private var currentResult: FetchResult? = null
    private var favAppPackageName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Tell Android to use dark icons in the status bar because our app background is light
        WindowCompat.getInsetsController(window, binding.root).isAppearanceLightStatusBars = true

        // Apply SafeArea (Window Insets) padding to the root view
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = systemBars.top, bottom = systemBars.bottom)
            insets
        }

        // Load saved favorite app
        val prefs = getSharedPreferences("trackdrop_prefs", MODE_PRIVATE)
        favAppPackageName = prefs.getString("fav_app_package", null)

        setupButtons()
        setupPairingButton()
        handleShareIntent(intent)
    }

    private fun setupButtons() {
        // Split Button setup
        binding.btnFavApp.setOnClickListener {
            if (favAppPackageName != null && currentResult != null) {
                ShareService.openGpxInPackage(this, currentResult!!.gpx, currentResult!!.filename, favAppPackageName!!)
            } else {
                showAppPickerDialog()
            }
        }

        binding.btnFavAppConfig.setOnClickListener {
            showAppPickerDialog()
        }

        binding.btnShare.setOnClickListener {
            currentResult?.let {
                ShareService.openGpx(this, it.gpx, it.filename, false)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrEmpty()) {
                val url = sharedText.trim()
                val provider = ProviderRegistry.getProviderForUrl(url)
                if (provider != null) {
                    binding.urlLabel.text = url
                    scheduleFetch(url, provider)
                } else {
                    showError("Shared text is not a supported track URL.")
                }
                intent.action = null 
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Restore favorite app name if it was already selected
        if (favAppPackageName != null) {
            try {
                val pm = packageManager
                val label = pm.getApplicationLabel(pm.getApplicationInfo(favAppPackageName!!, 0))
                updateFavAppButton(label.toString())
            } catch (e: Exception) {
                favAppPackageName = null
                updateFavAppButton("")
            }
        }
        
        ForegroundManager.listener = { result ->
            if (result != null) {
                handleForegroundPush(result)
            } else {
                showError("Failed to fetch pushed track.")
            }
        }

        if (intent.getBooleanExtra("FROM_NOTIFICATION", false)) {
            loadCachedTrack()
            intent.removeExtra("FROM_NOTIFICATION")
        }
    }

    override fun onPause() {
        super.onPause()
        ForegroundManager.listener = null
    }

    private fun loadCachedTrack() {
        val cacheFile = File(cacheDir, TrackDropMessagingService.CACHE_FILE)
        if (!cacheFile.exists()) return
        
        try {
            val json = org.json.JSONObject(cacheFile.readText())
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
            
            val result = FetchResult(
                tour = tour,
                gpx = json.getString("gpx"),
                filename = json.getString("filename"),
                points = points,
                url = json.optString("url", "Received via PC")
            )
            
            showSuccessUI(result)
            cacheFile.delete()
        } catch (e: Exception) {
            Log.e("TrackDrop", "Error loading cached track", e)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            Log.d("TrackDrop", "Window gained focus. Checking clipboard...")
            checkClipboard()
        }
    }

    private fun handleForegroundPush(result: FetchResult) {
        runOnUiThread {
            showSuccessUI(result)
        }
    }
    
    private fun checkClipboard() {
        if (currentResult != null) return // Don't overwrite if a track is already loaded
        
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (!clipboard.hasPrimaryClip()) return
            
            val description = clipboard.primaryClipDescription ?: return
            val isUrl = description.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_URILIST)
            val isText = description.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN)
            if (!isUrl && !isText) return
            
            val clipItem = clipboard.primaryClip?.getItemAt(0) ?: return
            val clipText = clipItem.text?.toString()?.trim() ?: ""
            
            val provider = ProviderRegistry.getProviderForUrl(clipText)
            if (provider != null) {
                Log.d("TrackDrop", "Valid provider URL found! Fetching.")
                binding.urlLabel.text = clipText
                scheduleFetch(clipText, provider)
            }
        } catch (e: Exception) {
            Log.e("TrackDrop", "Error reading clipboard", e)
        }
    }

    private var fetchJob: kotlinx.coroutines.Job? = null

    private fun scheduleFetch(url: String, provider: RouteProvider) {
        fetchJob?.cancel()
        fetchJob = lifecycleScope.launch {
            delay(300) // Debounce
            fetchTourAsync(url, provider)
        }
    }

    private suspend fun fetchTourAsync(url: String, provider: RouteProvider) {
        if (isFetching) return
        isFetching = true
        
        showLoadingUI()

        try {
            val result = withContext(Dispatchers.IO) { provider.fetchTour(url) }
            showSuccessUI(result)
        } catch (e: SocketException) {
            showError("No internet connection. Check your network and try again.")
        } catch (e: Exception) {
            showError(e.message ?: "Unknown error")
        } finally {
            isFetching = false
        }
    }

    // --- UI Helpers ---

    private fun showLoadingUI() {
        runOnUiThread {
            binding.urlLabel.text = "Fetching route..."
            binding.loadingBar.visibility = View.VISIBLE
            binding.errorText.visibility = View.GONE
            binding.tourCard.visibility = View.GONE
            binding.btnShare.visibility = View.GONE
            binding.favAppContainer.visibility = View.GONE
        }
    }

    private fun showSuccessUI(result: FetchResult) {
        runOnUiThread {
            currentResult = result
            binding.urlLabel.text = result.url
            binding.tourName.text = result.tour.name
            binding.tourStats.text = "%.1f km  ·  %d m up  ·  %d m down".format(
                result.tour.distance / 1000, result.tour.up.toInt(), result.tour.down.toInt()
            )
            binding.loadingBar.visibility = View.GONE
            binding.errorText.visibility = View.GONE
            binding.tourCard.visibility = View.VISIBLE
            binding.btnShare.visibility = View.VISIBLE
            binding.favAppContainer.visibility = View.VISIBLE
            
            binding.routePreview.setPoints(result.points)
        }
    }

    private fun showError(msg: String) {
        runOnUiThread {
            binding.loadingBar.visibility = View.GONE
            binding.tourCard.visibility = View.GONE
            currentResult = null
            binding.errorText.text = msg
            binding.errorText.visibility = View.VISIBLE
            
            binding.btnShare.visibility = View.GONE
            binding.favAppContainer.visibility = View.GONE
        }
    }

    private fun resetUI() {
        binding.loadingBar.visibility = View.GONE
        binding.errorText.visibility = View.GONE
        binding.tourCard.visibility = View.GONE
        currentResult = null
        binding.urlLabel.text = ""
        binding.btnShare.visibility = View.GONE
        binding.favAppContainer.visibility = View.GONE
    }

    // --- Pairing logic ---

    private val requestNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            generatePairingCode()
        } else {
            showError("Notifications must be enabled to receive tracks from your PC.")
            binding.btnPairBrowser.isEnabled = true
        }
    }

    private fun setupPairingButton() {
        binding.btnPairBrowser.setOnClickListener {
            binding.btnPairBrowser.isEnabled = false // Disable immediately to prevent spam
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    AlertDialog.Builder(this)
                        .setTitle("Pair with PC")
                        .setMessage("To receive tracks from your PC, TrackDrop needs permission to show notifications when a link is sent.\n\nClick OK to continue to the permission request.")
                        .setPositiveButton("OK") { _, _ ->
                            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        .setNegativeButton("Cancel") { _, _ ->
                            binding.btnPairBrowser.isEnabled = true // Re-enable on cancel
                        }
                        .setOnCancelListener {
                            binding.btnPairBrowser.isEnabled = true // Re-enable if tapped outside
                        }
                        .show()
                } else {
                    generatePairingCode()
                }
            } else {
                generatePairingCode()
            }
        }
    }

    private fun generatePairingCode() {
        val code = (100000..999999).random().toString()
        Log.d("TrackDrop", "Generated pairing code: $code")
        
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                Log.d("TrackDrop", "Got FCM token, sending to Cloud Function...")
                
                Thread {
                    try {
                        val url = URL("https://us-central1-trackdrop-ea99a.cloudfunctions.net/registerPairingCode")
                        val conn = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            connectTimeout = 10000
                            readTimeout = 10000
                            setRequestProperty("Content-Type", "application/json")
                            doOutput = true
                        }
                        
                        val jsonBody = """{"code":"$code","token":"$token"}"""
                        conn.outputStream.use { it.write(jsonBody.toByteArray()) }
                        
                        val responseCode = conn.responseCode
                        if (responseCode == 200) {
                            runOnUiThread { showPairingCodeDialog(code) }
                            Log.d("TrackDrop", "Pairing code registered!")
                        } else {
                            Log.e("TrackDrop", "Failed to register code: $responseCode")
                            runOnUiThread { 
                                showError("Failed to register pairing code.") 
                                binding.btnPairBrowser.isEnabled = true // Re-enable on failure
                            }
                        }
                        conn.disconnect()
                    } catch (e: Exception) {
                        Log.e("TrackDrop", "Error registering code", e)
                        runOnUiThread { 
                            showError("Network error registering code.") 
                            binding.btnPairBrowser.isEnabled = true // Re-enable on error
                        }
                    }
                }.start()
                
            } else {
                Log.e("TrackDrop", "Failed to get FCM token", task.exception)
                showError("Failed to get FCM token.")
                binding.btnPairBrowser.isEnabled = true // Re-enable on failure
            }
        }
    }

    private fun showPairingCodeDialog(code: String) {
        val formattedCode = "${code.substring(0,3)}-${code.substring(3,6)}"
        AlertDialog.Builder(this)
            .setTitle("Pair with PC")
            .setMessage("Open the TrackDrop extension in your browser and enter this code:\n\n$formattedCode")
            .setPositiveButton("Done") { _, _ ->
                binding.btnPairBrowser.isEnabled = true // Re-enable when user clicks Done
            }
            .setOnCancelListener {
                binding.btnPairBrowser.isEnabled = true // Re-enable if tapped outside
            }
            .show()
    }

    // --- Favorite App Picker ---

    private fun showAppPickerDialog() {
        val apps = ShareService.getGpxApps(this)
        if (apps.isEmpty()) {
            showError("No GPX-capable apps installed.")
            return
        }

        val adapter = object : ArrayAdapter<ResolveInfo>(this, android.R.layout.activity_list_item, android.R.id.text1, apps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val item = getItem(position)!!
                view.findViewById<android.widget.TextView>(android.R.id.text1).text = item.loadLabel(packageManager)
                view.findViewById<android.widget.ImageView>(android.R.id.icon).setImageDrawable(item.loadIcon(packageManager))
                view.setPadding(32, 24, 32, 24)
                return view
            }
        }

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        val listView = ListView(this).apply {
            this.adapter = adapter
        }
        dialogView.addView(listView)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Choose Favorite App")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val selectedApp = apps[position]
            favAppPackageName = selectedApp.activityInfo.packageName
            getSharedPreferences("trackdrop_prefs", MODE_PRIVATE).edit().putString("fav_app_package", favAppPackageName).apply()
            
            updateFavAppButton(selectedApp.loadLabel(packageManager).toString())
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun updateFavAppButton(appName: String) {
        if (favAppPackageName != null) {
            binding.btnFavApp.text = "Open in $appName"
            binding.btnFavAppConfig.visibility = View.VISIBLE
            binding.dividerFavApp.visibility = View.VISIBLE // Show divider
        } else {
            binding.btnFavApp.text = "Pick Favorite App"
            binding.btnFavAppConfig.visibility = View.GONE
            binding.dividerFavApp.visibility = View.GONE // Hide divider
        }
    }
}
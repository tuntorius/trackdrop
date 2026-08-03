package com.tuntori.trackdrop

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.tuntori.trackdrop.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.SocketException
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isFetching = false
    private var currentProvider: RouteProvider? = null
    private lateinit var textWatcher: TextWatcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.statusBarColor = Color.parseColor("#4F6814")

        setupUrlField()
        setupButtons()
        setupPairingButton()
        handleShareIntent(intent)
    }

    private fun setupUrlField() {
        binding.urlField.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val drawable = binding.urlField.compoundDrawablesRelative[2]
                if (drawable != null) {
                    val rightBound = binding.urlField.right - binding.urlField.paddingRight - drawable.intrinsicWidth
                    if (event.rawX.toInt() >= rightBound) {
                        binding.urlField.setText("")
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }

        textWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val url = s?.toString()?.trim() ?: ""
                toggleClearIcon(url.isNotEmpty())
                
                if (url.isEmpty()) {
                    resetUI()
                    return
                }
                
                currentProvider = ProviderRegistry.getProviderForUrl(url)
                if (currentProvider == null) {
                    showError("That doesn't look like a supported track URL.")
                    return
                }
                scheduleFetch(url, currentProvider!!)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        binding.urlField.addTextChangedListener(textWatcher)
    }

    private fun setupButtons() {
        binding.btnOrganicMaps.setOnClickListener {
            (binding.urlField.tag as? FetchResult)?.let {
                ShareService.openGpx(this, it.gpx, it.filename, true)
            }
        }

        binding.btnShare.setOnClickListener {
            (binding.urlField.tag as? FetchResult)?.let {
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
                if (ProviderRegistry.getProviderForUrl(url) != null) {
                    binding.urlField.setText(url)
                } else {
                    showError("Shared text is not a supported track URL.")
                }
                intent.action = null 
            }
        }
    }

    private fun toggleClearIcon(show: Boolean) {
        val leftDrawable = ResourcesCompat.getDrawable(resources, android.R.drawable.ic_menu_directions, null)
        leftDrawable?.setTint(Color.parseColor("#4F6814"))
        
        val rightDrawable = if (show) {
            ResourcesCompat.getDrawable(resources, android.R.drawable.ic_menu_close_clear_cancel, null)
        } else {
            null
        }
        
        binding.urlField.setCompoundDrawablesRelativeWithIntrinsicBounds(
            leftDrawable, null, rightDrawable, null
        )
    }

    override fun onResume() {
        super.onResume()
        val isInstalled = ShareService.isPackageInstalled(this, "app.organicmaps")
        binding.btnOrganicMaps.isEnabled = isInstalled
        binding.btnOrganicMaps.text = if (isInstalled) "Open in Organic Maps" else "Organic Maps not installed"

        // Register the foreground listener
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
        // Unregister to save battery and prevent leaks
        ForegroundManager.listener = null
    }

    private fun loadCachedTrack() {
        val gpxFile = File(cacheDir, TrackDropMessagingService.CACHE_FILE)
        val nameFile = File(cacheDir, TrackDropMessagingService.CACHE_NAME)
        if (gpxFile.exists() && nameFile.exists()) {
            val gpx = gpxFile.readText()
            val name = nameFile.readText()
            // We don't have the exact distance/up/down without re-parsing, but we have the GPX!
            // Let's just show the name and enable the buttons.
            val result = FetchResult(Tour(name, 0.0, 0.0, 0.0), gpx, "$name.gpx")
            binding.urlField.tag = result
            binding.tourName.text = name
            binding.tourStats.text = "Received from PC"
            binding.loadingBar.visibility = View.GONE
            binding.errorText.visibility = View.GONE
            binding.tourCard.visibility = View.VISIBLE
            binding.btnOrganicMaps.visibility = View.VISIBLE
            binding.btnShare.visibility = View.VISIBLE
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
            // Temporarily remove listener so setting the text doesn't trigger a duplicate fetch
            binding.urlField.removeTextChangedListener(textWatcher)
            binding.urlField.setText("Received via PC")
            binding.urlField.addTextChangedListener(textWatcher)
            
            // Reuse our existing success UI function!
            showSuccessUI(result)
        }
    }
    
    private fun checkClipboard() {
        if (binding.urlField.text?.toString()?.isNotEmpty() == true) return
        
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (!clipboard.hasPrimaryClip()) return
            
            // Check MIME type first. If it's a URI, reading it won't trigger the Android 12 toast!
            val description = clipboard.primaryClipDescription ?: return
            val isUrl = description.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_URILIST)
            val isText = description.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN)
            if (!isUrl && !isText) return
            
            val clipItem = clipboard.primaryClip?.getItemAt(0) ?: return
            val clipText = clipItem.text?.toString()?.trim() ?: ""
            
            if (ProviderRegistry.getProviderForUrl(clipText) != null) {
                Log.d("TrackDrop", "Valid provider URL found! Setting text.")
                binding.urlField.setText(clipText)
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
            binding.loadingBar.visibility = View.VISIBLE
            binding.errorText.visibility = View.GONE
            binding.tourCard.visibility = View.GONE
            binding.btnOrganicMaps.visibility = View.GONE
            binding.btnShare.visibility = View.GONE
        }
    }

    private fun showSuccessUI(result: FetchResult) {
        runOnUiThread {
            binding.urlField.tag = result
            binding.tourName.text = result.tour.name
            binding.tourStats.text = "%.1f km  ·  %d m up  ·  %d m down".format(
                result.tour.distance / 1000, result.tour.up.toInt(), result.tour.down.toInt()
            )
            binding.loadingBar.visibility = View.GONE
            binding.tourCard.visibility = View.VISIBLE
            binding.btnOrganicMaps.visibility = View.VISIBLE
            binding.btnShare.visibility = View.VISIBLE
        }
    }

    private fun showError(msg: String) {
        runOnUiThread {
            binding.loadingBar.visibility = View.GONE
            binding.tourCard.visibility = View.GONE
            binding.urlField.tag = null
            binding.errorText.text = msg
            binding.errorText.visibility = View.VISIBLE
            
            binding.btnOrganicMaps.visibility = View.GONE
            binding.btnShare.visibility = View.GONE
        }
    }

    private fun resetUI() {
        binding.loadingBar.visibility = View.GONE
        binding.errorText.visibility = View.GONE
        binding.tourCard.visibility = View.GONE
        binding.urlField.tag = null
        
        binding.btnOrganicMaps.visibility = View.GONE
        binding.btnShare.visibility = View.GONE
    }

    // --- Pairing logic ---

    private val requestNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            generatePairingCode()
        } else {
            showError("Notifications must be enabled to receive tracks from your PC.")
        }
    }

    private fun setupPairingButton() {
        binding.btnPairBrowser.setOnClickListener {
            // Check if we already have permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    // Permission is missing. Show explainer dialog FIRST.
                    AlertDialog.Builder(this)
                        .setTitle("Pair with PC")
                        .setMessage("To receive tracks from your PC, TrackDrop needs permission to show notifications when a link is sent.\n\nClick OK to continue to the permission request.")
                        .setPositiveButton("OK") { _, _ ->
                            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        .show()
                } else {
                    // We already have permission! Skip the dialog.
                    generatePairingCode()
                }
            } else {
                // Under Android 13, no permission needed. Skip the dialog.
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
                
                // Call the Cloud Function on a background thread
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
                        }
                        conn.disconnect()
                    } catch (e: Exception) {
                        Log.e("TrackDrop", "Error registering code", e)
                    }
                }.start()
                
            } else {
                Log.e("TrackDrop", "Failed to get FCM token", task.exception)
                showError("Failed to get FCM token.")
            }
        }
    }

    private fun showPairingCodeDialog(code: String) {
        val formattedCode = "${code.substring(0,3)}-${code.substring(3,6)}"
        AlertDialog.Builder(this)
            .setTitle("Pair with PC")
            .setMessage("Open the TrackDrop extension in your browser and enter this code:\n\n$formattedCode")
            .setPositiveButton("Done", null)
            .show()
    }

    // --- HTML Builders ---

    private fun buildSuccessHtml(routeName: String): String {
        val safeName = routeName.replace("<", "&lt;").replace(">", "&gt;")
        return "<div id='success' style='display:none;'><h2 style='color:#4F6814;'>Route received!</h2><p style='color:#666;font-size:14px;'>$safeName</p></div>" +
                "<script>" +
                "document.getElementById('loader').style.display='none';" +
                "document.getElementById('success').style.display='block';" +
                "setTimeout(function(){window.close();}, 1000);" +
                "</script>"
    }

    private fun buildErrorHtml(errorMsg: String): String {
        val safeError = errorMsg.replace("<", "&lt;").replace(">", "&gt;")
        return "<div id='error' style='display:none;'><h2 style='color:#C62828;'>Failed to fetch route</h2>" +
                "<p style='color:#666;font-size:14px;'>$safeError</p>" +
                "<p style='font-size:12px;color:#999;'>Make sure you are on the final shared page, not the editor.</p></div>" +
                "<script>" +
                "document.getElementById('loader').style.display='none';" +
                "document.getElementById('error').style.display='block';" +
                "setTimeout(function(){window.close();}, 6000);" +
                "</script>"
    }
}
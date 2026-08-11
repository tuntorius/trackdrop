package com.tuntori.trackdrop

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.SocketException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isFetching = false
    private var currentResult: FetchResult? = null
    private var fetchJob: Job? = null

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

        setupPairingButton()
        handleShareIntent(intent)
    }

    // --- Share / clipboard intake ---

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

        ForegroundManager.listener = { result ->
            if (result != null) {
                handleForegroundPush(result)
            } else {
                showError("Failed to fetch pushed track.")
            }
        }

        val fromNotification = intent.getBooleanExtra("FROM_NOTIFICATION", false)
        val isShareIntent = intent.action == Intent.ACTION_SEND

        // Determine the URL to fetch:
        // 1. From notification tap (intent has NOTIFICATION_URL)
        // 2. From pending notification in SharedPreferences (screen was off,
        //    app is in foreground, but onPause cleared the listener)
        val url = if (fromNotification) {
            intent.getStringExtra("NOTIFICATION_URL")
        } else if (!isShareIntent) {
            getPendingNotificationUrl()
        } else {
            null
        }

        if (fromNotification) {
            intent.removeExtra("FROM_NOTIFICATION")
            intent.removeExtra("NOTIFICATION_URL")
        }

        if (url != null) {
            // Clear the pending URL and cancel the notification, whether the
            // user tapped it or we're auto-consuming it.
            clearPendingNotificationUrl()
            cancelNotification()

            val provider = ProviderRegistry.getProviderForUrl(url)
            if (provider != null) {
                binding.urlLabel.text = url
                scheduleFetch(url, provider)
            } else {
                showError("Unsupported URL format.")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        ForegroundManager.listener = null
    }

    private fun handleForegroundPush(result: FetchResult) {
        runOnUiThread {
            showSuccessUI(result)
        }
    }

    private fun getPendingNotificationUrl(): String? {
        return getSharedPreferences("trackdrop_prefs", Context.MODE_PRIVATE)
            .getString(TrackDropMessagingService.PENDING_URL_KEY, null)
    }

    private fun clearPendingNotificationUrl() {
        getSharedPreferences("trackdrop_prefs", Context.MODE_PRIVATE)
            .edit()
            .remove(TrackDropMessagingService.PENDING_URL_KEY)
            .apply()
    }

    private fun cancelNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(TrackDropMessagingService.NOTIFICATION_ID)
    }

    // --- Fetching ---

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

    // --- UI state ---

    private fun showLoadingUI() {
        runOnUiThread {
            binding.urlLabel.text = "Fetching route..."
            binding.loadingBar.visibility = View.VISIBLE
            binding.errorText.visibility = View.GONE
            binding.tourCard.visibility = View.GONE
            binding.appGridContainer.visibility = View.GONE
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

            binding.routePreview.setPoints(result.points)

            populateAppGrid(result)
        }
    }

    private fun showError(msg: String) {
        runOnUiThread {
            binding.loadingBar.visibility = View.GONE
            binding.tourCard.visibility = View.GONE
            currentResult = null
            binding.errorText.text = msg
            binding.errorText.visibility = View.VISIBLE

            binding.appGridContainer.visibility = View.GONE
        }
    }

    // --- App grid ---

    private fun populateAppGrid(result: FetchResult) {
        lifecycleScope.launch {
            val sourcePackage = ProviderRegistry.getProviderForUrl(result.url)?.sourceAppPackage
            val apps = withContext(Dispatchers.IO) {
                ShareService.getGpxApps(this@MainActivity, result.gpx, result.filename)
                    .filter { it.packageName != sourcePackage }
            }

            binding.appGrid.removeAllViews()

            if (apps.isEmpty()) {
                binding.appGridContainer.visibility = View.GONE
                return@launch
            }

            val inflater = LayoutInflater.from(this@MainActivity)
            for (app in apps) {
                val itemView = inflater.inflate(R.layout.item_app_button, binding.appGrid, false)
                itemView.findViewById<ImageView>(R.id.appIcon).setImageDrawable(app.icon)
                itemView.findViewById<TextView>(R.id.appName).text = app.name
                itemView.setOnClickListener {
                    ShareService.openGpxInPackage(
                        this@MainActivity,
                        result.gpx,
                        result.filename,
                        app.packageName
                    )
                }
                binding.appGrid.addView(itemView)
            }

            binding.appGridContainer.visibility = View.VISIBLE
        }
    }

    // --- Pairing ---

    private val requestNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            generatePairingCode()
        } else {
            hidePairingProgress()
            if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                showNotificationSettingsDialog()
            } else {
                showError("Notifications must be enabled to receive tracks from your PC.")
            }
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
                            hidePairingProgress()
                            binding.btnPairBrowser.isEnabled = true // Re-enable on cancel
                        }
                        .setOnCancelListener {
                            hidePairingProgress()
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

        showPairingProgress()

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                lifecycleScope.launch {
                    val error = withContext(Dispatchers.IO) {
                        PairingApi.registerPairingCode(code, token)
                    }
                    hidePairingProgress()
                    if (error == null) {
                        showPairingCodeDialog(code)
                    } else {
                        showError(error)
                        binding.btnPairBrowser.isEnabled = true
                    }
                }
            } else {
                Log.e("TrackDrop", "Failed to get FCM token", task.exception)
                hidePairingProgress()
                showError("Failed to get FCM token.")
                binding.btnPairBrowser.isEnabled = true
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

    private fun showPairingProgress() {
        binding.pairingProgressContainer.visibility = View.VISIBLE
    }

    private fun hidePairingProgress() {
        binding.pairingProgressContainer.visibility = View.GONE
    }

    private fun showNotificationSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Notifications are blocked")
            .setMessage("TrackDrop needs notification permission to receive tracks from your PC, but it's currently blocked. Please enable it in Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

package com.tuntori.trackdrop

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.SocketException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isFetching = false
    private var currentResult: FetchResult? = null
    private var favAppPackageName: String? = null
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

        // Load saved favorite app
        favAppPackageName = getSharedPreferences("trackdrop_prefs", MODE_PRIVATE)
            .getString("fav_app_package", null)

        setupButtons()
        setupPairingButton()
        handleShareIntent(intent)
    }

    // --- Button setup ---

    private fun setupButtons() {
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
        TrackCache.load(this)?.let { showSuccessUI(it) }
    }

    private fun handleForegroundPush(result: FetchResult) {
        runOnUiThread {
            showSuccessUI(result)
        }
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

    // --- Pairing ---

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
                Thread {
                    val error = PairingApi.registerPairingCode(code, token)
                    runOnUiThread {
                        if (error == null) {
                            showPairingCodeDialog(code)
                        } else {
                            showError(error)
                            binding.btnPairBrowser.isEnabled = true
                        }
                    }
                }.start()
            } else {
                Log.e("TrackDrop", "Failed to get FCM token", task.exception)
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

    // --- Favorite App ---

    private fun showAppPickerDialog() {
        AppPickerDialog.show(
            context = this,
            onAppSelected = { packageName, appLabel ->
                favAppPackageName = packageName
                getSharedPreferences("trackdrop_prefs", MODE_PRIVATE)
                    .edit()
                    .putString("fav_app_package", packageName)
                    .apply()
                updateFavAppButton(appLabel)
            },
            onNoApps = {
                showError("No GPX-capable apps installed.")
            }
        )
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
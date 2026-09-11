package com.marcos.bassenhancer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.marcos.bassenhancer.core.CaptureMode
import com.marcos.bassenhancer.core.HapticEngine
import com.marcos.bassenhancer.core.PrefsRepository
import com.marcos.bassenhancer.service.BassService
import com.marcos.bassenhancer.ui.BassEnhancerTheme
import com.marcos.bassenhancer.ui.MainScreen

class MainActivity : ComponentActivity() {

    private lateinit var prefs: PrefsRepository
    private lateinit var haptics: HapticEngine

    /** Set while we are waiting on a permission grant that should be followed by a start. */
    private var startAfterPermission = false

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                prefs.update { it.copy(enabled = true) }
                BassService.startWithProjection(this, result.resultCode, data)
            } else {
                prefs.update { it.copy(enabled = false) }
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val audioOk = granted[Manifest.permission.RECORD_AUDIO] ?: hasAudioPermission()
            if (startAfterPermission && audioOk) {
                startAfterPermission = false
                beginCapture()
            } else {
                startAfterPermission = false
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = PrefsRepository.get(this)
        haptics = HapticEngine(this)

        setContent {
            BassEnhancerTheme {
                MainScreen(
                    prefs = prefs,
                    haptics = haptics,
                    onToggle = { wanted -> if (wanted) requestStart() else stopCapture() },
                )
            }
        }

        if (intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true) requestStart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) requestStart()
    }

    private fun hasAudioPermission() = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    private fun requestStart() {
        val missing = buildList {
            if (!hasAudioPermission()) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isNotEmpty()) {
            startAfterPermission = true
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        beginCapture()
    }

    private fun beginCapture() {
        when (prefs.current.captureMode) {
            CaptureMode.VISUALIZER -> {
                prefs.update { it.copy(enabled = true) }
                BassService.startVisualizerMode(this)
            }

            CaptureMode.PLAYBACK -> {
                val manager = getSystemService(MediaProjectionManager::class.java)
                if (manager == null) {
                    prefs.update { it.copy(enabled = false) }
                    return
                }
                projectionLauncher.launch(manager.createScreenCaptureIntent())
            }
        }
    }

    private fun stopCapture() {
        prefs.update { it.copy(enabled = false) }
        BassService.stop(this)
    }

    companion object {
        const val EXTRA_AUTO_START = "auto_start"
    }
}

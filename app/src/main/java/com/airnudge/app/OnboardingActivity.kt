package com.airnudge.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat

class OnboardingActivity : ComponentActivity() {
    private lateinit var cameraStatus: TextView
    private lateinit var accessibilityStatus: TextView
    private var reviewMode = false

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        updateStatuses()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reviewMode = intent.getBooleanExtra(EXTRA_REVIEW, false)
        if (SettingsStore.isOnboardingComplete(this) && !reviewMode) {
            openMain()
            return
        }
        setContentView(R.layout.activity_onboarding)
        cameraStatus = findViewById(R.id.onboardingCameraStatus)
        accessibilityStatus = findViewById(R.id.onboardingAccessibilityStatus)
        findViewById<Button>(R.id.onboardingCameraButton).setOnClickListener {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
        findViewById<Button>(R.id.onboardingAccessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.tutorialButton).setOnClickListener {
            startActivity(Intent(this, GestureTutorialActivity::class.java))
        }
        findViewById<Button>(R.id.onboardingContinueButton).setOnClickListener {
            SettingsStore.setOnboardingComplete(this)
            if (reviewMode) finish() else openMain()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::cameraStatus.isInitialized) updateStatuses()
    }

    private fun updateStatuses() {
        val cameraEnabled = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        cameraStatus.text = getString(if (cameraEnabled) R.string.camera_ready else R.string.camera_not_ready)
        accessibilityStatus.text = getString(
            if (AirNudgeAccessibilityService.isAvailable()) {
                R.string.accessibility_ready
            } else {
                R.string.accessibility_not_ready
            }
        )
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        const val EXTRA_REVIEW = "review"
    }
}

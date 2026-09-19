package com.airnudge.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class CalibrationActivity : AppCompatActivity() {
    private lateinit var instruction: TextView
    private lateinit var progress: TextView
    private lateinit var finishButton: Button
    private val measurements = mutableListOf<Float>()
    private var step = 0
    private var serviceWasRunning = false
    private var cleanupComplete = false
    private val expectedGestures = listOf(
        GestureType.SWIPE_UP,
        GestureType.SWIPE_DOWN,
        GestureType.PINCH
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val gestureName = intent.getStringExtra(GestureControlService.EXTRA_GESTURE) ?: return
            if (step >= expectedGestures.size || gestureName != expectedGestures[step].name) return
            val measurement = intent.getFloatExtra(GestureControlService.EXTRA_GESTURE_MEASUREMENT, -1f)
            if (measurement <= 0f) return
            measurements += measurement
            step++
            updateStep()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)
        instruction = findViewById(R.id.calibrationInstruction)
        progress = findViewById(R.id.calibrationProgress)
        finishButton = findViewById(R.id.calibrationFinishButton)
        finishButton.setOnClickListener { finishCalibration() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            instruction.setText(R.string.calibration_camera_required)
            finishButton.isEnabled = true
            return
        }
        serviceWasRunning = savedInstanceState?.getBoolean(KEY_SERVICE_WAS_RUNNING)
            ?: GestureControlService.isRunning
        step = savedInstanceState?.getInt(KEY_STEP) ?: 0
        savedInstanceState?.getFloatArray(KEY_MEASUREMENTS)?.let { measurements.addAll(it.toList()) }
        ContextCompat.startForegroundService(
            this,
            Intent(this, GestureControlService::class.java)
                .setAction(GestureControlService.ACTION_CALIBRATION_START)
        )
        updateStep()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_SERVICE_WAS_RUNNING, serviceWasRunning)
        outState.putInt(KEY_STEP, step)
        outState.putFloatArray(KEY_MEASUREMENTS, measurements.toFloatArray())
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(GestureControlService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onStop() {
        unregisterReceiver(receiver)
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing && !cleanupComplete) stopCalibrationService()
        super.onDestroy()
    }

    private fun updateStep() {
        progress.text = getString(R.string.calibration_progress, step, expectedGestures.size)
        if (step == expectedGestures.size) {
            val thresholds = CalibrationCalculator.calculate(
                measurements[0], measurements[1], measurements[2]
            )
            SettingsStore.saveCalibration(this, thresholds.swipeDistance, thresholds.pinchRatio)
            instruction.setText(R.string.calibration_complete)
            finishButton.isEnabled = true
        } else {
            instruction.text = getString(
                R.string.calibration_do_gesture,
                expectedGestures[step].name.replace('_', ' ')
            )
        }
    }

    private fun finishCalibration() {
        stopCalibrationService()
        finish()
    }

    private fun stopCalibrationService() {
        val action = if (serviceWasRunning) {
            GestureControlService.ACTION_CALIBRATION_STOP
        } else {
            GestureControlService.ACTION_STOP
        }
        startService(Intent(this, GestureControlService::class.java).setAction(action))
        cleanupComplete = true
    }

    companion object {
        private const val KEY_SERVICE_WAS_RUNNING = "service_was_running"
        private const val KEY_STEP = "step"
        private const val KEY_MEASUREMENTS = "measurements"
    }
}

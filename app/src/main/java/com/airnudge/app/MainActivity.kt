package com.airnudge.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var controlSwitch: android.widget.Switch
    private lateinit var statusText: TextView
    private lateinit var gestureText: TextView
    private lateinit var cameraPermissionStatus: TextView
    private lateinit var cameraPermissionButton: Button
    private lateinit var accessibilityStatus: TextView
    private lateinit var metricsText: TextView

    private var gestureService: GestureControlService? = null
    private var isBound = false
    private var updatingSwitch = false

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updatePermissionStatus()
        if (granted) startGestureControl() else setControlSwitch(false)
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            gestureService = (binder as GestureControlService.LocalBinder).service
            isBound = true
            gestureService?.attachPreview(previewView.surfaceProvider)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            gestureService = null
            isBound = false
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra(GestureControlService.EXTRA_STATE)) {
                GestureControlService.STATE_STARTING -> showStatus(R.string.status_camera_starting, true)
                GestureControlService.STATE_NO_HAND -> showStatus(R.string.status_camera_active, true)
                GestureControlService.STATE_HAND -> showStatus(R.string.status_hand_detected, true)
                GestureControlService.STATE_ERROR -> {
                    setControlSwitch(false)
                    statusText.text = intent.getStringExtra(GestureControlService.EXTRA_ERROR_MESSAGE)
                        ?: getString(R.string.status_hand_landmarker_error)
                    statusText.setTextColor(statusColor(false))
                }
                GestureControlService.STATE_STOPPED -> {
                    setControlSwitch(false)
                    showStatus(R.string.status_inactive, false)
                }
            }
            if (intent.hasExtra(GestureControlService.EXTRA_ACTION_DISPATCHED) &&
                !intent.getBooleanExtra(GestureControlService.EXTRA_ACTION_DISPATCHED, false)
            ) {
                showStatus(R.string.status_accessibility_needed, false)
            }

            intent.getStringExtra(GestureControlService.EXTRA_GESTURE)?.let { gesture ->
                gestureText.text = getString(
                    R.string.hand_result,
                    gesture,
                    intent.getIntExtra(GestureControlService.EXTRA_CONFIDENCE, 0),
                    intent.getLongExtra(GestureControlService.EXTRA_GESTURE_LATENCY, 0L)
                )
            }
            intent.getStringExtra(GestureControlService.EXTRA_METRICS)?.let { metricsText.text = it }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        controlSwitch = findViewById(R.id.controlSwitch)
        statusText = findViewById(R.id.statusText)
        gestureText = findViewById(R.id.gestureText)
        cameraPermissionStatus = findViewById(R.id.cameraPermissionStatus)
        cameraPermissionButton = findViewById(R.id.cameraPermissionButton)
        accessibilityStatus = findViewById(R.id.accessibilityStatus)
        metricsText = findViewById(R.id.metricsText)

        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        controlSwitch.setOnCheckedChangeListener { _, enabled ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            if (enabled) requestOrStartGestureControl() else stopGestureControl()
        }
        cameraPermissionButton.setOnClickListener {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<CheckBox>(R.id.metricsCheckBox).setOnCheckedChangeListener { _, shown ->
            metricsText.visibility = if (shown) View.VISIBLE else View.GONE
        }
        updatePermissionStatus()
    }

    override fun onStart() {
        super.onStart()
        registerStatusReceiver()
        updateAccessibilityStatus()
        setControlSwitch(GestureControlService.isRunning)
        if (GestureControlService.isRunning) {
            bindGestureService()
        } else if (SettingsStore.isControlRequested(this) && hasCameraPermission()) {
            startGestureControl()
            setControlSwitch(true)
        }
    }

    override fun onStop() {
        if (isBound) {
            gestureService?.attachPreview(null)
            unbindService(serviceConnection)
            isBound = false
            gestureService = null
        }
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    private fun requestOrStartGestureControl() {
        if (hasCameraPermission()) startGestureControl()
        else requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startGestureControl() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val intent = Intent(this, GestureControlService::class.java)
            .setAction(GestureControlService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
        bindGestureService()
        showStatus(R.string.status_camera_starting, true)
    }

    private fun stopGestureControl() {
        startService(
            Intent(this, GestureControlService::class.java)
                .setAction(GestureControlService.ACTION_STOP)
        )
        gestureText.setText(R.string.hand_placeholder)
        metricsText.setText(R.string.metrics_placeholder)
        showStatus(R.string.status_inactive, false)
    }

    private fun bindGestureService() {
        if (!isBound) {
            bindService(Intent(this, GestureControlService::class.java), serviceConnection, BIND_AUTO_CREATE)
        }
    }

    private fun registerStatusReceiver() {
        val filter = IntentFilter(GestureControlService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun updatePermissionStatus() {
        val granted = hasCameraPermission()
        cameraPermissionStatus.setText(if (granted) R.string.enabled else R.string.disabled)
        cameraPermissionStatus.setTextColor(statusColor(granted))
        cameraPermissionButton.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun updateAccessibilityStatus() {
        val expectedService = ComponentName(this, AirNudgeAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty().split(':').mapNotNull(ComponentName::unflattenFromString).any { it == expectedService }
        accessibilityStatus.setText(if (enabled) R.string.enabled else R.string.disabled)
        accessibilityStatus.setTextColor(statusColor(enabled))
    }

    private fun setControlSwitch(enabled: Boolean) {
        updatingSwitch = true
        controlSwitch.isChecked = enabled
        updatingSwitch = false
    }

    private fun showStatus(message: Int, active: Boolean) {
        statusText.setText(message)
        statusText.setTextColor(statusColor(active))
    }

    private fun statusColor(active: Boolean): Int = ContextCompat.getColor(
        this,
        if (active) R.color.status_good else R.color.status_inactive
    )
}

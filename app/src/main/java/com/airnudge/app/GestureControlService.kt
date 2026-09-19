package com.airnudge.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GestureControlService : LifecycleService(), HandLandmarkerDetector.Listener {
    inner class LocalBinder : Binder() {
        val service: GestureControlService get() = this@GestureControlService
    }

    private val binder = LocalBinder()
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val gestureRecognizer = GestureRecognizer()
    private val frameMetrics = FrameMetrics()
    private val analysisRateController = AnalysisRateController()
    private lateinit var performanceMonitor: PerformanceMonitor
    private var handDetector: HandLandmarkerDetector? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var previewSurfaceProvider: Preview.SurfaceProvider? = null
    private var detectorFrames = 0
    private var detectorWindowStartedMs = 0L
    private var detectorFps = 0.0
    private var latestInferenceTimeMs = 0L
    private var lastProfileLogAtMs = 0L
    private var latestFrameMetrics = FrameValues()
    private var analysisWidth = 0
    private var analysisHeight = 0
    private var cameraActive = false
    private var cameraStarting = false
    private var cameraGeneration = 0
    private var screenInteractive = true
    private var calibrationMode = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenInteractive = false
                    pauseCameraForScreenOff()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenInteractive = true
                    if (isRunning && !cameraActive) startCamera()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        performanceMonitor = PerformanceMonitor(this)
        screenInteractive = getSystemService(PowerManager::class.java).isInteractive
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            SettingsStore.setControlRequested(this, false)
            stopSelfSafely()
            return Service.START_NOT_STICKY
        }

        if (intent == null && !SettingsStore.isControlRequested(this)) {
            stopSelf()
            return Service.START_NOT_STICKY
        }

        calibrationMode = intent?.action == ACTION_CALIBRATION_START ||
            (calibrationMode && intent?.action != ACTION_CALIBRATION_STOP)
        if (intent?.action == ACTION_CALIBRATION_STOP) calibrationMode = false
        if (intent?.action == ACTION_START) SettingsStore.setControlRequested(this, true)

        startInForeground()
        if (!isRunning) {
            isRunning = true
            broadcastState(STATE_STARTING)
            if (screenInteractive) startCamera()
        }
        return Service.START_STICKY
    }

    fun attachPreview(surfaceProvider: Preview.SurfaceProvider?) {
        previewSurfaceProvider = surfaceProvider
        preview?.surfaceProvider = surfaceProvider
    }

    private fun startCamera() {
        if (!screenInteractive || cameraActive || cameraStarting) return
        cameraStarting = true
        val generation = ++cameraGeneration
        try {
            handDetector = HandLandmarkerDetector(applicationContext, this)
        } catch (exception: Exception) {
            cameraStarting = false
            Log.e(TAG, "Hand Landmarker failed to initialize.", exception)
            failAndStop(getString(R.string.error_detector_initialization))
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                if (!isRunning || !screenInteractive || generation != cameraGeneration) {
                    if (generation == cameraGeneration) cameraStarting = false
                    return@addListener
                }
                val provider = providerFuture.get()
                cameraProvider = provider
                preview = Preview.Builder().build().also { it.surfaceProvider = previewSurfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(analyzerExecutor) { image ->
                    val values = frameMetrics.onFrame(image.imageInfo.timestamp)
                    if (values != null) latestFrameMetrics = values
                    analysisWidth = image.width
                    analysisHeight = image.height
                    try {
                        val now = SystemClock.elapsedRealtime()
                        val performance = performanceMonitor.sampleIfDue(now)
                        if (analysisRateController.shouldAnalyze(now, performance.thermalSevere)) {
                            val accepted = handDetector?.detect(image) == true
                            if (!accepted) analysisRateController.onDetectorBusy()
                        }
                    } catch (exception: Exception) {
                        Log.e(TAG, "Frame analysis failed.", exception)
                    } finally {
                        image.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis
                )
                cameraActive = true
                cameraStarting = false
                broadcastState(STATE_NO_HAND)
            } catch (exception: Exception) {
                if (generation != cameraGeneration) return@addListener
                cameraStarting = false
                Log.e(TAG, "Camera initialization failed.", exception)
                failAndStop(getString(R.string.error_camera_initialization))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onHandResult(
        result: com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult,
        inferenceTimeMs: Long
    ) {
        if (!isRunning) return
        latestInferenceTimeMs = inferenceTimeMs
        updateDetectorFps()
        val landmarks = result.landmarks().firstOrNull()
        analysisRateController.onResult(
            handDetected = landmarks?.size == 21,
            inferenceTimeMs = inferenceTimeMs,
            nowMs = SystemClock.elapsedRealtime()
        )
        logProfileIfDue()
        val confidence = result.handedness().firstOrNull()?.firstOrNull()?.score() ?: 0f
        if (landmarks == null || landmarks.size != 21) {
            gestureRecognizer.onHandLost()
            AirNudgeAccessibilityService.hideCursor()
            broadcastResult(STATE_NO_HAND, null, inferenceTimeMs, null)
            return
        }

        val observation = HandObservation(
            landmarks.map { HandPoint(it.x(), it.y()) },
            confidence,
            result.timestampMs()
        )
        val settings = SettingsStore.load(this)
        gestureRecognizer.updateConfig(recognitionConfig(settings))
        val gesture = gestureRecognizer.process(observation)
        updateAirCursor(settings, observation, gesture)
        broadcastResult(STATE_HAND, observation, inferenceTimeMs, gesture)
        if (gesture != null && !calibrationMode) dispatchAction(gesture, observation.timestampMs, settings)
    }

    override fun onHandLandmarkerError(message: String) {
        Log.e(TAG, message)
        failAndStop(getString(R.string.error_detector_runtime))
    }

    private fun dispatchAction(
        gesture: GestureResult,
        frameTimestampMs: Long,
        settings: AppSettings
    ) {
        val action = SettingsStore.actionFor(this, gesture.type)
        if (action == ActionType.NONE) {
            val latency = gesture.confirmationTimeMs +
                (SystemClock.uptimeMillis() - frameTimestampMs).coerceAtLeast(0L)
            broadcastGesture(gesture, latency, true)
            return
        }
        AirNudgeAccessibilityService.executeAction(
            action,
            tapAtCursor = settings.airCursorEnabled
        ) { dispatched ->
            val actionDelayMs = (SystemClock.uptimeMillis() - frameTimestampMs).coerceAtLeast(0L)
            val endToEndLatency = gesture.confirmationTimeMs + actionDelayMs
            Log.i(
                TAG,
                "Gesture ${gesture.type}, action $action: dispatched=$dispatched " +
                    "latency=${endToEndLatency}ms"
            )
            broadcastGesture(gesture, endToEndLatency, dispatched)
        }
    }

    private fun updateAirCursor(
        settings: AppSettings,
        observation: HandObservation,
        gesture: GestureResult?
    ) {
        if (!settings.airCursorEnabled) {
            AirNudgeAccessibilityService.hideCursor()
            return
        }
        if (gestureRecognizer.isPointing(observation)) {
            AirNudgeAccessibilityService.updateCursor(
                x = 1f - observation.indexTip.x,
                y = observation.indexTip.y,
                sensitivity = settings.cursorSensitivity,
                smoothing = settings.cursorSmoothing,
                sizeDp = settings.pointerSizeDp
            )
        } else if (!gestureRecognizer.isPinching(observation) && gesture?.type != GestureType.PINCH) {
            AirNudgeAccessibilityService.hideCursor()
        }
    }

    private fun recognitionConfig(settings: AppSettings): RecognitionConfig =
        when (settings.sensitivity) {
            Sensitivity.LOW -> RecognitionConfig(
                0.65f, settings.calibratedSwipeDistance ?: 0.21f, 180L,
                settings.cooldownMs, settings.calibratedPinchRatio ?: 0.38f
            )
            Sensitivity.BALANCED -> RecognitionConfig(
                0.55f, settings.calibratedSwipeDistance ?: 0.16f, 140L,
                settings.cooldownMs, settings.calibratedPinchRatio ?: 0.38f
            )
            Sensitivity.HIGH -> RecognitionConfig(
                0.50f, settings.calibratedSwipeDistance ?: 0.12f, 100L,
                settings.cooldownMs, settings.calibratedPinchRatio ?: 0.38f
            )
        }

    private fun broadcastResult(
        state: String,
        observation: HandObservation?,
        inferenceTimeMs: Long,
        gesture: GestureResult?
    ) {
        val metrics = if (observation == null) {
            getString(
                R.string.metrics_format,
                latestFrameMetrics.cameraFps,
                latestFrameMetrics.analyzerFps,
                detectorFps,
                inferenceTimeMs,
                latestFrameMetrics.frameAgeMs,
                0,
                0f, 0f, 0f, 0f, 0f, 0f
            )
        } else {
            val palm = observation.palmCenter
            getString(
                R.string.metrics_format,
                latestFrameMetrics.cameraFps,
                latestFrameMetrics.analyzerFps,
                detectorFps,
                inferenceTimeMs,
                latestFrameMetrics.frameAgeMs,
                observation.landmarks.size,
                observation.indexTip.x,
                observation.indexTip.y,
                observation.thumbTip.x,
                observation.thumbTip.y,
                palm.x,
                palm.y
            )
        }
        val performance = performanceMonitor.snapshot
        val performanceMetrics = getString(
            R.string.performance_metrics_format,
            analysisRateController.targetIntervalMs,
            analysisRateController.droppedFrames,
            "${analysisWidth}x${analysisHeight}",
            performance.cpuPercent,
            performance.usedMemoryMb,
            performance.batteryCurrentMa?.toString() ?: "—",
            performance.batteryTemperatureC?.let { String.format("%.1f", it) } ?: "—",
            performance.thermalStatus
        )
        val intent = statusIntent(state).putExtra(EXTRA_METRICS, "$metrics\n$performanceMetrics")
        gesture?.let {
            intent.putExtra(EXTRA_GESTURE, it.type.name)
                .putExtra(EXTRA_CONFIDENCE, (it.confidence * 100).toInt())
                .putExtra(EXTRA_GESTURE_LATENCY, it.confirmationTimeMs)
                .putExtra(EXTRA_GESTURE_MEASUREMENT, it.measurement ?: -1f)
        }
        sendBroadcast(intent)
    }

    private fun broadcastGesture(gesture: GestureResult, latencyMs: Long, dispatched: Boolean) {
        sendBroadcast(
            statusIntent(STATE_HAND)
                .putExtra(EXTRA_GESTURE, gesture.type.name)
                .putExtra(EXTRA_CONFIDENCE, (gesture.confidence * 100).toInt())
                .putExtra(EXTRA_GESTURE_LATENCY, latencyMs)
                .putExtra(EXTRA_GESTURE_MEASUREMENT, gesture.measurement ?: -1f)
                .putExtra(EXTRA_ACTION_DISPATCHED, dispatched)
        )
    }

    private fun broadcastState(state: String) {
        sendBroadcast(statusIntent(state))
    }

    private fun failAndStop(message: String) {
        SettingsStore.setControlRequested(this, false)
        sendBroadcast(statusIntent(STATE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, message))
        stopSelfSafely(notifyStopped = false)
    }

    private fun statusIntent(state: String): Intent =
        Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATE, state)

    private fun updateDetectorFps() {
        val now = SystemClock.uptimeMillis()
        if (detectorWindowStartedMs == 0L) detectorWindowStartedMs = now
        detectorFrames++
        val elapsed = now - detectorWindowStartedMs
        if (elapsed >= 1_000L) {
            detectorFps = detectorFrames * 1_000.0 / elapsed
            detectorFrames = 0
            detectorWindowStartedMs = now
        }
    }

    private fun logProfileIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastProfileLogAtMs < PROFILE_LOG_INTERVAL_MS) return
        lastProfileLogAtMs = now
        val performance = performanceMonitor.snapshot
        Log.i(
            TAG,
            "Profile camera=${String.format("%.1f", latestFrameMetrics.cameraFps)}fps " +
                "detector=${String.format("%.1f", detectorFps)}fps " +
                "inference=${latestInferenceTimeMs}ms frameAge=${latestFrameMetrics.frameAgeMs}ms " +
                "interval=${analysisRateController.targetIntervalMs}ms " +
                "dropped=${analysisRateController.droppedFrames} resolution=${analysisWidth}x$analysisHeight " +
                "cpu=${String.format("%.1f", performance.cpuPercent)}% " +
                "memory=${performance.usedMemoryMb}MB batteryCurrent=${performance.batteryCurrentMa}mA " +
                "batteryTemp=${performance.batteryTemperatureC}C thermal=${performance.thermalStatus}"
        )
    }

    private fun startInForeground() {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), flags)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, GestureControlService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPendingIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_airnudge)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.stop), stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun pauseCameraForScreenOff() {
        cameraGeneration++
        cameraStarting = false
        cameraActive = false
        cameraProvider?.unbindAll()
        handDetector?.close()
        handDetector = null
        gestureRecognizer.onHandLost()
        analysisRateController.reset()
        AirNudgeAccessibilityService.hideCursor()
        Log.i(TAG, "Camera paused because the screen is off.")
    }

    private fun stopSelfSafely(notifyStopped: Boolean = true) {
        isRunning = false
        cameraGeneration++
        cameraStarting = false
        cameraActive = false
        cameraProvider?.unbindAll()
        handDetector?.close()
        handDetector = null
        AirNudgeAccessibilityService.hideCursor()
        gestureRecognizer.onHandLost()
        frameMetrics.reset()
        analysisRateController.reset()
        latestFrameMetrics = FrameValues()
        detectorFrames = 0
        detectorWindowStartedMs = 0L
        detectorFps = 0.0
        latestInferenceTimeMs = 0L
        lastProfileLogAtMs = 0L
        if (notifyStopped) broadcastState(STATE_STOPPED)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        unregisterReceiver(screenReceiver)
        cameraProvider?.unbindAll()
        handDetector?.close()
        analyzerExecutor.shutdown()
        super.onDestroy()
    }

    private data class FrameValues(
        val cameraFps: Double = 0.0,
        val analyzerFps: Double = 0.0,
        val frameAgeMs: Long = 0L
    )

    private class FrameMetrics {
        private var windowStartNanos = 0L
        private var previousTimestampNanos = 0L
        private var frames = 0
        private var intervals = 0
        private var intervalTotalNanos = 0L

        fun onFrame(timestampNanos: Long): FrameValues? {
            val now = SystemClock.elapsedRealtimeNanos()
            if (windowStartNanos == 0L) windowStartNanos = now
            if (previousTimestampNanos > 0L && timestampNanos > previousTimestampNanos) {
                intervalTotalNanos += timestampNanos - previousTimestampNanos
                intervals++
            }
            previousTimestampNanos = timestampNanos
            frames++
            val elapsed = now - windowStartNanos
            if (elapsed < TimeUnit.SECONDS.toNanos(1)) return null
            val values = FrameValues(
                cameraFps = if (intervals == 0) 0.0 else
                    intervals * TimeUnit.SECONDS.toNanos(1).toDouble() / intervalTotalNanos,
                analyzerFps = frames * TimeUnit.SECONDS.toNanos(1).toDouble() / elapsed,
                frameAgeMs = TimeUnit.NANOSECONDS.toMillis((now - timestampNanos).coerceAtLeast(0L))
            )
            windowStartNanos = now
            frames = 0
            intervals = 0
            intervalTotalNanos = 0L
            return values
        }

        fun reset() {
            windowStartNanos = 0L
            previousTimestampNanos = 0L
            frames = 0
            intervals = 0
            intervalTotalNanos = 0L
        }
    }

    companion object {
        private const val TAG = "GestureControlService"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL = "gesture_control"
        private const val PROFILE_LOG_INTERVAL_MS = 5_000L

        const val ACTION_START = "com.airnudge.app.action.START"
        const val ACTION_STOP = "com.airnudge.app.action.STOP"
        const val ACTION_STATUS = "com.airnudge.app.action.STATUS"
        const val ACTION_CALIBRATION_START = "com.airnudge.app.action.CALIBRATION_START"
        const val ACTION_CALIBRATION_STOP = "com.airnudge.app.action.CALIBRATION_STOP"
        const val EXTRA_STATE = "state"
        const val EXTRA_GESTURE = "gesture"
        const val EXTRA_CONFIDENCE = "confidence"
        const val EXTRA_GESTURE_LATENCY = "gesture_latency"
        const val EXTRA_GESTURE_MEASUREMENT = "gesture_measurement"
        const val EXTRA_ACTION_DISPATCHED = "action_dispatched"
        const val EXTRA_METRICS = "metrics"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        const val STATE_STARTING = "starting"
        const val STATE_NO_HAND = "no_hand"
        const val STATE_HAND = "hand"
        const val STATE_ERROR = "error"
        const val STATE_STOPPED = "stopped"

        @Volatile
        var isRunning = false
            private set
    }
}

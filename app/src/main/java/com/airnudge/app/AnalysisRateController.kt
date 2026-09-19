package com.airnudge.app

import kotlin.math.ceil
import kotlin.math.max

class AnalysisRateController {
    private var startedAtMs = 0L
    private var lastSubmittedAtMs = 0L
    private var lastHandSeenAtMs = 0L
    private var averageInferenceMs = 0.0

    var droppedFrames: Long = 0
        private set

    var targetIntervalMs: Long = ACTIVE_MIN_INTERVAL_MS
        private set

    fun shouldAnalyze(nowMs: Long, thermalSevere: Boolean): Boolean {
        if (startedAtMs == 0L) startedAtMs = nowMs
        targetIntervalMs = when {
            thermalSevere -> THERMAL_INTERVAL_MS
            lastHandSeenAtMs > 0L && nowMs - lastHandSeenAtMs <= RECENT_HAND_MS ->
                max(ACTIVE_MIN_INTERVAL_MS, ceil(averageInferenceMs * INFERENCE_HEADROOM).toLong())
            nowMs - startedAtMs >= NO_HAND_GRACE_MS -> IDLE_INTERVAL_MS
            else -> ACTIVE_MIN_INTERVAL_MS
        }
        if (lastSubmittedAtMs > 0L && nowMs - lastSubmittedAtMs < targetIntervalMs) {
            droppedFrames++
            return false
        }
        lastSubmittedAtMs = nowMs
        return true
    }

    fun onResult(handDetected: Boolean, inferenceTimeMs: Long, nowMs: Long) {
        if (handDetected) lastHandSeenAtMs = nowMs
        averageInferenceMs = if (averageInferenceMs == 0.0) {
            inferenceTimeMs.toDouble()
        } else {
            averageInferenceMs * 0.8 + inferenceTimeMs * 0.2
        }
    }

    fun onDetectorBusy() {
        droppedFrames++
    }

    fun reset() {
        startedAtMs = 0L
        lastSubmittedAtMs = 0L
        lastHandSeenAtMs = 0L
        averageInferenceMs = 0.0
        droppedFrames = 0
        targetIntervalMs = ACTIVE_MIN_INTERVAL_MS
    }

    companion object {
        private const val ACTIVE_MIN_INTERVAL_MS = 33L
        private const val IDLE_INTERVAL_MS = 100L
        private const val THERMAL_INTERVAL_MS = 150L
        private const val NO_HAND_GRACE_MS = 2_000L
        private const val RECENT_HAND_MS = 1_200L
        private const val INFERENCE_HEADROOM = 1.15
    }
}

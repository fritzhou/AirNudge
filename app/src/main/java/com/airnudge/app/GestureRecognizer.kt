package com.airnudge.app

import kotlin.math.abs
import kotlin.math.hypot

enum class GestureType {
    OPEN_PALM,
    CLOSED_FIST,
    PINCH,
    SWIPE_UP,
    SWIPE_DOWN,
    SWIPE_LEFT,
    SWIPE_RIGHT
}

data class GestureResult(
    val type: GestureType,
    val confidence: Float,
    val confirmationTimeMs: Long,
    val measurement: Float? = null
)

data class HandObservation(
    val landmarks: List<HandPoint>,
    val confidence: Float,
    val timestampMs: Long
) {
    val thumbTip: HandPoint get() = landmarks[4]
    val indexTip: HandPoint get() = landmarks[8]

    val palmCenter: HandPoint
        get() {
            val palmIndices = intArrayOf(0, 5, 9, 13, 17)
            return HandPoint(
                x = palmIndices.map { landmarks[it].x }.average().toFloat(),
                y = palmIndices.map { landmarks[it].y }.average().toFloat()
            )
        }
}

data class HandPoint(val x: Float, val y: Float)

data class RecognitionConfig(
    val minimumConfidence: Float = 0.55f,
    val swipeDistance: Float = 0.16f,
    val poseConfirmationMs: Long = 140L,
    val cooldownMs: Long = 550L,
    val pinchRatio: Float = 0.38f
)

class GestureRecognizer {
    private data class MotionSample(val point: HandPoint, val timestampMs: Long, val confidence: Float)

    private val motionHistory = ArrayDeque<MotionSample>()
    private var candidatePose: GestureType? = null
    private var candidateStartedMs = 0L
    private var emittedPose: GestureType? = null
    private var cooldownUntilMs = 0L
    private var config = RecognitionConfig()

    fun updateConfig(newConfig: RecognitionConfig) {
        config = newConfig
    }

    fun isPointing(observation: HandObservation): Boolean {
        if (observation.landmarks.size != LANDMARK_COUNT) return false
        val landmarks = observation.landmarks
        val indexExtended = landmarks[8].y < landmarks[6].y - FINGER_MARGIN
        val otherFingersFolded = intArrayOf(12, 16, 20).all { tip ->
            landmarks[tip].y >= landmarks[tip - 2].y - FINGER_MARGIN
        }
        return indexExtended && otherFingersFolded
    }

    fun isPinching(observation: HandObservation): Boolean {
        if (observation.landmarks.size != LANDMARK_COUNT) return false
        val palmSize = distance(observation.landmarks[0], observation.landmarks[9]).coerceAtLeast(0.04f)
        return distance(observation.thumbTip, observation.indexTip) < palmSize * config.pinchRatio
    }

    fun process(observation: HandObservation): GestureResult? {
        if (observation.landmarks.size != LANDMARK_COUNT || observation.confidence < config.minimumConfidence) {
            resetTracking()
            return null
        }

        if (observation.timestampMs < cooldownUntilMs) {
            motionHistory.clear()
            if (classifyPose(observation) != emittedPose) emittedPose = null
            return null
        }
        addMotionSample(observation)

        detectSwipe()?.let { return emit(it, observation.timestampMs) }

        val pose = classifyPose(observation)
        if (pose == null) {
            candidatePose = null
            emittedPose = null
            return null
        }
        if (pose == emittedPose) return null
        if (pose != candidatePose) {
            candidatePose = pose
            candidateStartedMs = observation.timestampMs
            return null
        }
        if (observation.timestampMs - candidateStartedMs < config.poseConfirmationMs) return null

        emittedPose = pose
        return emit(
            GestureResult(
                pose,
                observation.confidence,
                observation.timestampMs - candidateStartedMs,
                if (pose == GestureType.PINCH) pinchRatio(observation) else null
            ),
            observation.timestampMs
        )
    }

    fun onHandLost() {
        resetTracking()
    }

    private fun addMotionSample(observation: HandObservation) {
        // Mirror X so swipe labels match what users see in the front-camera preview.
        val center = observation.palmCenter
        val point = HandPoint(1f - center.x, center.y)
        if (motionHistory.isNotEmpty() && distance(motionHistory.last().point, point) > MAX_TRACKING_STEP) {
            motionHistory.clear()
        }
        motionHistory.addLast(MotionSample(point, observation.timestampMs, observation.confidence))
        while (motionHistory.size > MAX_HISTORY_SIZE ||
            observation.timestampMs - motionHistory.first().timestampMs > HISTORY_WINDOW_MS
        ) {
            motionHistory.removeFirst()
        }
    }

    private fun detectSwipe(): GestureResult? {
        if (motionHistory.size < MIN_SWIPE_SAMPLES) return null
        val start = motionHistory.first()
        val end = motionHistory.last()
        val deltaX = end.point.x - start.point.x
        val deltaY = end.point.y - start.point.y
        val horizontal = abs(deltaX)
        val vertical = abs(deltaY)

        val type = when {
            horizontal >= config.swipeDistance && horizontal > vertical * DIRECTION_DOMINANCE ->
                if (deltaX < 0) GestureType.SWIPE_LEFT else GestureType.SWIPE_RIGHT
            vertical >= config.swipeDistance && vertical > horizontal * DIRECTION_DOMINANCE ->
                if (deltaY < 0) GestureType.SWIPE_UP else GestureType.SWIPE_DOWN
            else -> return null
        }

        val primaryDelta: (MotionSample, MotionSample) -> Float = if (horizontal > vertical) {
            { previous, current -> current.point.x - previous.point.x }
        } else {
            { previous, current -> current.point.y - previous.point.y }
        }
        val expectedSign = if ((horizontal > vertical && deltaX > 0) ||
            (vertical >= horizontal && deltaY > 0)
        ) 1 else -1
        val pairs = motionHistory.zipWithNext()
        val consistentSteps = pairs.count { (previous, current) ->
            val step = primaryDelta(previous, current)
            abs(step) < MIN_MEANINGFUL_STEP || step * expectedSign > 0
        }
        if (consistentSteps.toFloat() / pairs.size < MIN_DIRECTION_CONSISTENCY) return null

        val confidence = motionHistory.map { it.confidence }.average().toFloat()
        return GestureResult(type, confidence, end.timestampMs - start.timestampMs, maxOf(horizontal, vertical))
    }

    private fun classifyPose(observation: HandObservation): GestureType? {
        val landmarks = observation.landmarks
        val palm = observation.palmCenter
        val palmSize = distance(landmarks[0], landmarks[9]).coerceAtLeast(0.04f)
        if (isPinching(observation)) return GestureType.PINCH

        val extendedFingers = listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18)
            .count { (tip, joint) -> landmarks[tip].y < landmarks[joint].y - FINGER_MARGIN }
        val thumbExtended = distance(landmarks[4], landmarks[5]) > palmSize * THUMB_OPEN_RATIO
        if (extendedFingers == 4 && thumbExtended) return GestureType.OPEN_PALM

        val fingertips = intArrayOf(4, 8, 12, 16, 20)
        val compactFingers = fingertips.count {
            hypot(landmarks[it].x - palm.x, landmarks[it].y - palm.y) < palmSize * FIST_RATIO
        }
        return if (extendedFingers == 0 && compactFingers >= 4) GestureType.CLOSED_FIST else null
    }

    private fun emit(result: GestureResult, timestampMs: Long): GestureResult {
        cooldownUntilMs = timestampMs + config.cooldownMs
        motionHistory.clear()
        candidatePose = null
        return result
    }

    private fun resetTracking() {
        motionHistory.clear()
        candidatePose = null
        emittedPose = null
    }

    private fun distance(first: HandPoint, second: HandPoint): Float =
        hypot(first.x - second.x, first.y - second.y)

    private fun pinchRatio(observation: HandObservation): Float {
        val palmSize = distance(observation.landmarks[0], observation.landmarks[9]).coerceAtLeast(0.04f)
        return distance(observation.thumbTip, observation.indexTip) / palmSize
    }

    companion object {
        private const val LANDMARK_COUNT = 21
        private const val MAX_HISTORY_SIZE = 10
        private const val MIN_SWIPE_SAMPLES = 4
        private const val HISTORY_WINDOW_MS = 350L
        private const val DIRECTION_DOMINANCE = 1.45f
        private const val MIN_DIRECTION_CONSISTENCY = 0.75f
        private const val MIN_MEANINGFUL_STEP = 0.004f
        private const val MAX_TRACKING_STEP = 0.12f
        private const val THUMB_OPEN_RATIO = 0.9f
        private const val FIST_RATIO = 1.35f
        private const val FINGER_MARGIN = 0.015f
    }
}

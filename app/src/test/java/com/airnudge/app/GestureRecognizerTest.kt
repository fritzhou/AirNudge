package com.airnudge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GestureRecognizerTest {
    @Test
    fun smallMovementDoesNotProduceSwipe() {
        val recognizer = GestureRecognizer()
        var result: GestureResult? = null

        repeat(6) { frame ->
            result = recognizer.process(observation(centerX = 0.5f + frame * 0.005f, timestamp = frame * 35L))
        }

        assertNull(result)
    }

    @Test
    fun consistentUpwardMovementProducesOneSwipe() {
        val recognizer = GestureRecognizer()
        val results = mutableListOf<GestureResult>()

        repeat(8) { frame ->
            recognizer.process(
                observation(centerY = 0.75f - frame * 0.035f, timestamp = 1_000L + frame * 30L)
            )?.let(results::add)
        }

        assertEquals(listOf(GestureType.SWIPE_UP), results.map { it.type })
    }

    @Test
    fun heldPinchIsEmittedOnlyOnce() {
        val recognizer = GestureRecognizer()
        val results = mutableListOf<GestureResult>()

        repeat(30) { frame ->
            recognizer.process(pinchObservation(timestamp = 2_000L + frame * 35L))?.let(results::add)
        }

        assertEquals(listOf(GestureType.PINCH), results.map { it.type })
    }

    @Test
    fun highSensitivityAcceptsShorterSwipe() {
        val balanced = GestureRecognizer()
        val high = GestureRecognizer().apply {
            updateConfig(RecognitionConfig(swipeDistance = 0.12f))
        }
        var balancedResult: GestureResult? = null
        var highResult: GestureResult? = null

        repeat(6) { frame ->
            val hand = observation(centerY = 0.65f - frame * 0.026f, timestamp = 3_000L + frame * 30L)
            balancedResult = balanced.process(hand) ?: balancedResult
            highResult = high.process(hand) ?: highResult
        }

        assertNull(balancedResult)
        assertEquals(GestureType.SWIPE_UP, highResult?.type)
    }

    @Test
    fun pointingRequiresOnlyIndexFingerExtended() {
        val recognizer = GestureRecognizer()
        val observation = observation(timestamp = 4_000L)

        assertEquals(true, recognizer.isPointing(observation))
    }

    @Test
    fun trackingJumpDoesNotProduceSwipe() {
        val recognizer = GestureRecognizer()
        recognizer.process(observation(centerX = 0.2f, timestamp = 5_000L))

        val result = recognizer.process(observation(centerX = 0.8f, timestamp = 5_033L))

        assertNull(result)
    }

    private fun observation(
        centerX: Float = 0.5f,
        centerY: Float = 0.5f,
        timestamp: Long
    ): HandObservation {
        val points = MutableList(21) { HandPoint(centerX, centerY) }
        points[0] = HandPoint(centerX, centerY + 0.10f)
        points[5] = HandPoint(centerX - 0.08f, centerY)
        points[9] = HandPoint(centerX, centerY)
        points[13] = HandPoint(centerX + 0.04f, centerY)
        points[17] = HandPoint(centerX + 0.08f, centerY)
        points[4] = HandPoint(centerX - 0.12f, centerY - 0.04f)
        points[8] = HandPoint(centerX - 0.02f, centerY - 0.18f)
        points[6] = HandPoint(centerX - 0.02f, centerY - 0.08f)
        return HandObservation(points, 0.9f, timestamp)
    }

    private fun pinchObservation(timestamp: Long): HandObservation {
        val observation = observation(timestamp = timestamp)
        val points = observation.landmarks.toMutableList()
        points[4] = HandPoint(0.49f, 0.36f)
        points[8] = HandPoint(0.50f, 0.36f)
        return HandObservation(points, 0.9f, timestamp)
    }
}

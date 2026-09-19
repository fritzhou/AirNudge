package com.airnudge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisRateControllerTest {
    @Test
    fun reducesWorkAfterNoHandGracePeriod() {
        val controller = AnalysisRateController()

        assertTrue(controller.shouldAnalyze(1_000L, thermalSevere = false))
        controller.onResult(handDetected = false, inferenceTimeMs = 20L, nowMs = 1_020L)
        assertTrue(controller.shouldAnalyze(3_100L, thermalSevere = false))
        assertEquals(100L, controller.targetIntervalMs)
        assertFalse(controller.shouldAnalyze(3_150L, thermalSevere = false))
    }

    @Test
    fun handPresenceRestoresResponsiveRate() {
        val controller = AnalysisRateController()
        controller.shouldAnalyze(1_000L, thermalSevere = false)
        controller.onResult(handDetected = true, inferenceTimeMs = 20L, nowMs = 3_100L)

        assertTrue(controller.shouldAnalyze(3_133L, thermalSevere = false))
        assertEquals(33L, controller.targetIntervalMs)
    }

    @Test
    fun measuredSlowInferenceControlsSustainableRate() {
        val controller = AnalysisRateController()
        controller.shouldAnalyze(1_000L, thermalSevere = false)
        controller.onResult(handDetected = true, inferenceTimeMs = 80L, nowMs = 1_080L)

        assertFalse(controller.shouldAnalyze(1_081L, thermalSevere = false))
        assertEquals(92L, controller.targetIntervalMs)
        assertTrue(controller.shouldAnalyze(1_092L, thermalSevere = false))
    }

    @Test
    fun severeThermalStatusCapsAnalysisRate() {
        val controller = AnalysisRateController()

        assertTrue(controller.shouldAnalyze(1_000L, thermalSevere = true))
        assertFalse(controller.shouldAnalyze(1_100L, thermalSevere = true))
        assertEquals(150L, controller.targetIntervalMs)
    }
}

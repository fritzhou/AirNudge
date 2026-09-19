package com.airnudge.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationCalculatorTest {
    @Test
    fun createsComfortableThresholdsFromCompletedGestures() {
        val result = CalibrationCalculator.calculate(0.20f, 0.18f, 0.30f)

        assertEquals(0.144f, result.swipeDistance, 0.0001f)
        assertEquals(0.375f, result.pinchRatio, 0.0001f)
    }

    @Test
    fun clampsExtremeCalibrationValues() {
        val result = CalibrationCalculator.calculate(0.50f, 0.50f, 0.05f)

        assertEquals(0.24f, result.swipeDistance, 0.0001f)
        assertEquals(0.25f, result.pinchRatio, 0.0001f)
    }
}

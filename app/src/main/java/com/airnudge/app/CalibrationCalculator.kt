package com.airnudge.app

data class CalibrationThresholds(val swipeDistance: Float, val pinchRatio: Float)

object CalibrationCalculator {
    fun calculate(swipeUpDistance: Float, swipeDownDistance: Float, pinchRatio: Float) =
        CalibrationThresholds(
            swipeDistance = (minOf(swipeUpDistance, swipeDownDistance) * 0.8f).coerceIn(0.10f, 0.24f),
            pinchRatio = (pinchRatio * 1.25f).coerceIn(0.25f, 0.55f)
        )
}

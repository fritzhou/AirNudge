package com.airnudge.app

import android.content.Context

enum class ActionType {
    NONE,
    SCROLL_UP,
    SCROLL_DOWN,
    SWIPE_LEFT,
    SWIPE_RIGHT,
    TAP,
    BACK,
    HOME,
    PLAY_PAUSE,
    VOLUME_UP,
    VOLUME_DOWN
}

enum class Sensitivity {
    LOW,
    BALANCED,
    HIGH
}

data class AppSettings(
    val sensitivity: Sensitivity,
    val cooldownMs: Long,
    val airCursorEnabled: Boolean,
    val cursorSensitivity: Int,
    val cursorSmoothing: Int,
    val pointerSizeDp: Int,
    val calibratedSwipeDistance: Float?,
    val calibratedPinchRatio: Float?
)

object SettingsStore {
    private const val PREFERENCES = "airnudge_settings"
    private const val KEY_SENSITIVITY = "sensitivity"
    private const val KEY_COOLDOWN = "cooldown"
    private const val KEY_CURSOR_ENABLED = "cursor_enabled"
    private const val KEY_CURSOR_SENSITIVITY = "cursor_sensitivity"
    private const val KEY_CURSOR_SMOOTHING = "cursor_smoothing"
    private const val KEY_POINTER_SIZE = "pointer_size"
    private const val KEY_CALIBRATED_SWIPE = "calibrated_swipe"
    private const val KEY_CALIBRATED_PINCH = "calibrated_pinch"
    private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
    private const val KEY_CONTROL_REQUESTED = "control_requested"

    fun load(context: Context): AppSettings {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return AppSettings(
            sensitivity = enumValueOrDefault(
                preferences.getString(KEY_SENSITIVITY, null),
                Sensitivity.BALANCED
            ),
            cooldownMs = preferences.getInt(KEY_COOLDOWN, 550).toLong(),
            airCursorEnabled = preferences.getBoolean(KEY_CURSOR_ENABLED, false),
            cursorSensitivity = preferences.getInt(KEY_CURSOR_SENSITIVITY, 100),
            cursorSmoothing = preferences.getInt(KEY_CURSOR_SMOOTHING, 55),
            pointerSizeDp = preferences.getInt(KEY_POINTER_SIZE, 24),
            calibratedSwipeDistance = preferences.getFloat(KEY_CALIBRATED_SWIPE, -1f).takeIf { it > 0f },
            calibratedPinchRatio = preferences.getFloat(KEY_CALIBRATED_PINCH, -1f).takeIf { it > 0f }
        )
    }

    fun setSensitivity(context: Context, sensitivity: Sensitivity) = edit(context) {
        putString(KEY_SENSITIVITY, sensitivity.name)
    }

    fun setCooldown(context: Context, milliseconds: Int) = edit(context) {
        putInt(KEY_COOLDOWN, milliseconds.coerceIn(250, 1_200))
    }

    fun setAirCursorEnabled(context: Context, enabled: Boolean) = edit(context) {
        putBoolean(KEY_CURSOR_ENABLED, enabled)
    }

    fun setCursorSensitivity(context: Context, percent: Int) = edit(context) {
        putInt(KEY_CURSOR_SENSITIVITY, percent.coerceIn(60, 160))
    }

    fun setCursorSmoothing(context: Context, percent: Int) = edit(context) {
        putInt(KEY_CURSOR_SMOOTHING, percent.coerceIn(0, 100))
    }

    fun setPointerSize(context: Context, sizeDp: Int) = edit(context) {
        putInt(KEY_POINTER_SIZE, sizeDp.coerceIn(12, 48))
    }

    fun saveCalibration(context: Context, swipeDistance: Float, pinchRatio: Float) = edit(context) {
        putFloat(KEY_CALIBRATED_SWIPE, swipeDistance.coerceIn(0.10f, 0.24f))
        putFloat(KEY_CALIBRATED_PINCH, pinchRatio.coerceIn(0.25f, 0.55f))
    }

    fun clearCalibration(context: Context) = edit(context) {
        remove(KEY_CALIBRATED_SWIPE)
        remove(KEY_CALIBRATED_PINCH)
    }

    fun isOnboardingComplete(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ONBOARDING_COMPLETE, false)

    fun setOnboardingComplete(context: Context) = edit(context) {
        putBoolean(KEY_ONBOARDING_COMPLETE, true)
    }

    fun isControlRequested(context: Context): Boolean =
        preferences(context).getBoolean(KEY_CONTROL_REQUESTED, false)

    fun setControlRequested(context: Context, enabled: Boolean) = edit(context) {
        putBoolean(KEY_CONTROL_REQUESTED, enabled)
    }

    fun actionFor(context: Context, gesture: GestureType): ActionType {
        val value = preferences(context).getString(actionKey(gesture), null)
        return enumValueOrDefault(value, defaultAction(gesture))
    }

    fun setAction(context: Context, gesture: GestureType, action: ActionType) = edit(context) {
        putString(actionKey(gesture), action.name)
    }

    private fun defaultAction(gesture: GestureType): ActionType = when (gesture) {
        GestureType.SWIPE_UP -> ActionType.SCROLL_UP
        GestureType.SWIPE_DOWN -> ActionType.SCROLL_DOWN
        GestureType.SWIPE_LEFT -> ActionType.SWIPE_LEFT
        GestureType.SWIPE_RIGHT -> ActionType.SWIPE_RIGHT
        GestureType.CLOSED_FIST -> ActionType.BACK
        GestureType.PINCH -> ActionType.TAP
        GestureType.OPEN_PALM -> ActionType.PLAY_PAUSE
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun actionKey(gesture: GestureType) = "action_${gesture.name}"

    private inline fun edit(
        context: Context,
        update: android.content.SharedPreferences.Editor.() -> Unit
    ) {
        preferences(context).edit().apply(update).apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: default
}

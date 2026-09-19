package com.airnudge.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.hypot

class AirNudgeAccessibilityService : AccessibilityService() {
    private lateinit var windowManager: WindowManager
    private var cursorView: View? = null
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorVisible = false

    override fun onServiceConnected() {
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Log.i(TAG, "AirNudge accessibility service connected.")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        removeCursor()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        removeCursor()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        Log.w(TAG, "AirNudge accessibility service interrupted.")
    }

    private fun execute(action: ActionType, tapAtCursor: Boolean): Boolean = when (action) {
        ActionType.NONE -> true
        ActionType.SCROLL_UP -> swipe(vertical = true, forward = true)
        ActionType.SCROLL_DOWN -> swipe(vertical = true, forward = false)
        ActionType.SWIPE_LEFT -> swipe(vertical = false, forward = true)
        ActionType.SWIPE_RIGHT -> swipe(vertical = false, forward = false)
        ActionType.TAP -> tap(if (tapAtCursor && cursorVisible) cursorX else null, if (tapAtCursor && cursorVisible) cursorY else null)
        ActionType.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
        ActionType.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
        ActionType.PLAY_PAUSE -> sendMediaKey()
        ActionType.VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
        ActionType.VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
    }

    private fun swipe(vertical: Boolean, forward: Boolean): Boolean {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        val path = Path()
        if (vertical) {
            val startY = if (forward) height * 0.75f else height * 0.25f
            val endY = if (forward) height * 0.25f else height * 0.75f
            path.moveTo(width * 0.5f, startY)
            path.lineTo(width * 0.5f, endY)
        } else {
            val startX = if (forward) width * 0.8f else width * 0.2f
            val endX = if (forward) width * 0.2f else width * 0.8f
            path.moveTo(startX, height * 0.5f)
            path.lineTo(endX, height * 0.5f)
        }
        return dispatchPath(path, SWIPE_DURATION_MS)
    }

    private fun tap(x: Float?, y: Float?): Boolean {
        val path = Path().apply {
            moveTo(
                x ?: resources.displayMetrics.widthPixels * 0.5f,
                y ?: resources.displayMetrics.heightPixels * 0.5f
            )
        }
        return dispatchPath(path, TAP_DURATION_MS)
    }

    private fun sendMediaKey(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        return true
    }

    private fun adjustVolume(direction: Int): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        return true
    }

    private fun updateCursor(position: CursorPosition) {
        val density = resources.displayMetrics.density
        val sizePx = (position.sizeDp * density).toInt()
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        val gain = position.sensitivity / 100f
        val targetX = ((position.x - 0.5f) * gain + 0.5f).coerceIn(0f, 1f) * width
        val targetY = ((position.y - 0.5f) * gain + 0.5f).coerceIn(0f, 1f) * height
        val smoothingFactor = 0.65f - position.smoothing / 100f * 0.5f

        if (!cursorVisible) {
            cursorX = targetX
            cursorY = targetY
        } else if (hypot(targetX - cursorX, targetY - cursorY) >= density * CURSOR_DEAD_ZONE_DP) {
            cursorX += (targetX - cursorX) * smoothingFactor
            cursorY += (targetY - cursorY) * smoothingFactor
        }

        val view = cursorView ?: createCursor(sizePx).also { cursorView = it }
        view.visibility = View.VISIBLE
        cursorVisible = true
        val layout = view.layoutParams as WindowManager.LayoutParams
        layout.width = sizePx
        layout.height = sizePx
        layout.x = (cursorX - sizePx / 2).toInt()
        layout.y = (cursorY - sizePx / 2).toInt()
        windowManager.updateViewLayout(view, layout)
    }

    private fun createCursor(sizePx: Int): View {
        val view = View(this)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(210, 49, 93, 171))
            setStroke((2 * resources.displayMetrics.density).toInt(), Color.WHITE)
        }
        val layout = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager.addView(view, layout)
        return view
    }

    private fun hideCursor() {
        cursorVisible = false
        cursorView?.visibility = View.GONE
    }

    private fun removeCursor() {
        if (::windowManager.isInitialized) cursorView?.let(windowManager::removeView)
        cursorView = null
        cursorVisible = false
    }

    private fun dispatchPath(path: Path, durationMs: Long): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        val dispatched = dispatchGesture(gesture, null, null)
        if (!dispatched) Log.e(TAG, "Gesture action could not be dispatched.")
        return dispatched
    }

    companion object {
        private const val TAG = "AirNudgeAccessibility"
        private const val SWIPE_DURATION_MS = 220L
        private const val TAP_DURATION_MS = 60L
        private const val CURSOR_DEAD_ZONE_DP = 2f

        @Volatile
        private var instance: AirNudgeAccessibilityService? = null
        private val mainHandler = Handler(Looper.getMainLooper())
        private var latestCursorPosition: CursorPosition? = null
        private val cursorRunnable = Runnable {
            val service = instance
            val position = latestCursorPosition
            if (service != null && position != null) service.updateCursor(position)
        }
        private val hideCursorRunnable = Runnable { instance?.hideCursor() }

        fun executeAction(action: ActionType, tapAtCursor: Boolean, callback: (Boolean) -> Unit) {
            mainHandler.post {
                val service = instance
                if (service == null) {
                    Log.w(TAG, "Accessibility service is unavailable.")
                    callback(false)
                } else {
                    callback(service.execute(action, tapAtCursor))
                }
            }
        }

        fun updateCursor(x: Float, y: Float, sensitivity: Int, smoothing: Int, sizeDp: Int) {
            latestCursorPosition = CursorPosition(x, y, sensitivity, smoothing, sizeDp)
            mainHandler.removeCallbacks(hideCursorRunnable)
            mainHandler.removeCallbacks(cursorRunnable)
            mainHandler.post(cursorRunnable)
        }

        fun hideCursor() {
            mainHandler.removeCallbacks(cursorRunnable)
            mainHandler.removeCallbacks(hideCursorRunnable)
            mainHandler.post(hideCursorRunnable)
        }

        fun isAvailable(): Boolean = instance != null
    }

    private data class CursorPosition(
        val x: Float,
        val y: Float,
        val sensitivity: Int,
        val smoothing: Int,
        val sizeDp: Int
    )
}

package com.airnudge.app

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class HandLandmarkerDetector(
    context: Context,
    private val listener: Listener
) : AutoCloseable {
    interface Listener {
        fun onHandResult(result: HandLandmarkerResult, inferenceTimeMs: Long)
        fun onHandLandmarkerError(message: String)
    }

    private val handLandmarker: HandLandmarker
    private val processing = AtomicBoolean(false)
    private var reusableBitmap: Bitmap? = null
    private var packedBuffer: ByteBuffer? = null
    private var rowBuffer = ByteArray(0)
    @Volatile
    private var closed = false

    init {
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_FILE).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.55f)
            .setMinHandPresenceConfidence(0.55f)
            .setMinTrackingConfidence(0.55f)
            .setResultListener { result, _ ->
                val inferenceTime = (SystemClock.uptimeMillis() - result.timestampMs()).coerceAtLeast(0L)
                processing.set(false)
                listener.onHandResult(result, inferenceTime)
            }
            .setErrorListener { error ->
                processing.set(false)
                Log.e(TAG, "Hand Landmarker failed.", error)
                listener.onHandLandmarkerError("Hand tracking stopped unexpectedly")
            }
            .build()
        handLandmarker = HandLandmarker.createFromOptions(context, options)
    }

    fun detect(image: ImageProxy): Boolean {
        if (closed || !processing.compareAndSet(false, true)) return false
        return try {
            val bitmap = image.copyToReusableBitmap()
            val mpImage = BitmapImageBuilder(bitmap).build()
            val processingOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(image.imageInfo.rotationDegrees)
                .build()
            handLandmarker.detectAsync(mpImage, processingOptions, SystemClock.uptimeMillis())
            true
        } catch (exception: Exception) {
            processing.set(false)
            throw exception
        }
    }

    override fun close() {
        closed = true
        handLandmarker.close()
        reusableBitmap?.recycle()
        reusableBitmap = null
        packedBuffer = null
        rowBuffer = ByteArray(0)
    }

    private fun ImageProxy.copyToReusableBitmap(): Bitmap {
        val bitmap = reusableBitmap?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                reusableBitmap?.recycle()
                reusableBitmap = it
            }
        val plane = planes[0]
        val source = plane.buffer
        val packedRowBytes = width * 4
        if (plane.rowStride == packedRowBytes) {
            source.rewind()
            bitmap.copyPixelsFromBuffer(source)
            return bitmap
        }

        val requiredCapacity = packedRowBytes * height
        val packed = packedBuffer?.takeIf { it.capacity() >= requiredCapacity }
            ?: ByteBuffer.allocate(requiredCapacity).also { packedBuffer = it }
        if (rowBuffer.size != packedRowBytes) rowBuffer = ByteArray(packedRowBytes)
        packed.clear()
        for (y in 0 until height) {
            source.position(y * plane.rowStride)
            source.get(rowBuffer)
            packed.put(rowBuffer)
        }
        packed.rewind()
        bitmap.copyPixelsFromBuffer(packed)
        return bitmap
    }

    companion object {
        private const val TAG = "HandLandmarker"
        private const val MODEL_FILE = "hand_landmarker.task"
    }
}

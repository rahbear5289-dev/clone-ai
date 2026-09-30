package com.jarvis.assistant.automation

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream

object ScreenCaptureHolder {
    @Volatile var resultCode: Int = Activity.RESULT_CANCELED
    @Volatile var resultData: Intent? = null
    @Volatile var activeProjection: MediaProjection? = null
    @Volatile var lastFrameAt: Long = 0L
    @Volatile var lastJpeg: ByteArray? = null

    fun hasProjection(): Boolean =
        activeProjection != null || (resultData != null && resultCode == Activity.RESULT_OK)

    fun clear() {
        try { activeProjection?.stop() } catch (_: Exception) {}
        activeProjection = null
        resultData = null
        resultCode = Activity.RESULT_CANCELED
        lastJpeg = null
        lastFrameAt = 0L
    }
}

class ScreenCaptureEngine(private val context: Context) {
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "ScreenCapture"
    }

    fun isReady(): Boolean = ScreenCaptureHolder.hasProjection()

    @Synchronized
    @SuppressLint("WrongConstant")
    fun captureFreshJpeg(): ByteArray? {
        if (!ScreenCaptureHolder.hasProjection()) return null
        return try {
            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            var projection = ScreenCaptureHolder.activeProjection

            if (projection == null) {
                val data = ScreenCaptureHolder.resultData ?: return null
                projection = mgr.getMediaProjection(ScreenCaptureHolder.resultCode, data)
                if (projection == null) return null

                // Android 14 (API 34) strictly requires registerCallback before createVirtualDisplay
                val callback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.i(TAG, "MediaProjection stopped by system")
                        teardownDisplay()
                        ScreenCaptureHolder.activeProjection = null
                    }
                }
                projection.registerCallback(callback, mainHandler)
                ScreenCaptureHolder.activeProjection = projection
            }

            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels.coerceAtMost(1080)
            val height = (metrics.heightPixels * (width.toFloat() / metrics.widthPixels)).toInt()
            val density = metrics.densityDpi

            // Recreate imageReader if dimensions changed or null
            if (imageReader == null || imageReader?.width != width || imageReader?.height != height) {
                teardownDisplay()
                val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                imageReader = reader
                virtualDisplay = projection.createVirtualDisplay(
                    "luna-screen",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    mainHandler
                )
            }

            val reader = imageReader ?: return null

            // Poll for fresh frame (wait up to 1200ms)
            var jpeg: ByteArray? = null
            val deadline = System.currentTimeMillis() + 1200
            while (jpeg == null && System.currentTimeMillis() < deadline) {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    jpeg = imageToJpeg(image, width, height)
                    image.close()
                } else {
                    Thread.sleep(60)
                }
            }

            if (jpeg != null) {
                ScreenCaptureHolder.lastJpeg = jpeg
                ScreenCaptureHolder.lastFrameAt = System.currentTimeMillis()
                Log.i(TAG, "Fresh screen capture acquired: ${jpeg.size} bytes")
            }
            jpeg
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed: ${e.message}", e)
            null
        }
    }

    private fun imageToJpeg(image: android.media.Image, width: Int, height: Int): ByteArray? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 75, out)
            cropped.recycle()
            bitmap.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "JPEG encode failed: ${e.message}")
            null
        }
    }

    private fun teardownDisplay() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
    }

    fun stop() {
        teardownDisplay()
        ScreenCaptureHolder.clear()
    }
}

package com.jarvis.assistant.util

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VisionFeedMode {
    OFF,
    CAMERA_REAR,
    CAMERA_FRONT,
    SCREEN_FEED
}

/**
 * CameraVisionManager manages:
 * - Camera Feed Mode: smooth switching between Front and Rear cameras
 * - Screen Feed Mode: live screen activity analysis state
 */
class CameraVisionManager(private val context: Context) {

    companion object {
        private const val TAG = "CameraVisionManager"
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val _feedMode = MutableStateFlow(VisionFeedMode.OFF)
    val feedMode: StateFlow<VisionFeedMode> = _feedMode.asStateFlow()

    private val _statusDescription = MutableStateFlow("Vision Standby")
    val statusDescription: StateFlow<String> = _statusDescription.asStateFlow()

    private var currentTextureView: TextureView? = null

    fun attachTextureView(textureView: TextureView) {
        currentTextureView = textureView
    }

    fun toggleCameraFeed(textureView: TextureView? = null) {
        if (textureView != null) {
            currentTextureView = textureView
        }
        when (_feedMode.value) {
            VisionFeedMode.OFF, VisionFeedMode.SCREEN_FEED -> {
                openCamera(useFront = false)
            }
            VisionFeedMode.CAMERA_REAR -> {
                // Switch smoothly to Front camera
                openCamera(useFront = true)
            }
            VisionFeedMode.CAMERA_FRONT -> {
                closeCamera()
                _feedMode.value = VisionFeedMode.OFF
                _statusDescription.value = "Vision Standby"
            }
        }
    }

    fun switchCameraLens() {
        if (_feedMode.value == VisionFeedMode.CAMERA_REAR) {
            openCamera(useFront = true)
        } else if (_feedMode.value == VisionFeedMode.CAMERA_FRONT) {
            openCamera(useFront = false)
        }
    }

    fun toggleScreenFeed() {
        if (_feedMode.value == VisionFeedMode.SCREEN_FEED) {
            _feedMode.value = VisionFeedMode.OFF
            _statusDescription.value = "Vision Standby"
        } else {
            closeCamera()
            _feedMode.value = VisionFeedMode.SCREEN_FEED
            _statusDescription.value = "Screen Feed Active: Analyzing foreground window"
        }
    }

    @SuppressLint("MissingPermission")
    fun openCamera(useFront: Boolean) {
        startBackgroundThread()
        closeCamera()

        try {
            val targetFacing = if (useFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            var targetCameraId: String? = null

            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == targetFacing) {
                    targetCameraId = id
                    break
                }
            }

            if (targetCameraId == null && cameraManager.cameraIdList.isNotEmpty()) {
                targetCameraId = cameraManager.cameraIdList[0]
            }

            if (targetCameraId == null) {
                _statusDescription.value = "No camera found on device"
                return
            }

            cameraManager.openCamera(targetCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    _feedMode.value = if (useFront) VisionFeedMode.CAMERA_FRONT else VisionFeedMode.CAMERA_REAR
                    _statusDescription.value = if (useFront) "Camera Feed: Front Lens Active" else "Camera Feed: Rear Lens Active"
                    startPreview()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    cameraDevice = null
                    _feedMode.value = VisionFeedMode.OFF
                    _statusDescription.value = "Camera error: $error"
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera: ${e.message}", e)
            _statusDescription.value = "Camera unavailable"
        }
    }

    private fun startPreview() {
        val texture = currentTextureView?.surfaceTexture ?: return
        val camera = cameraDevice ?: return

        try {
            val surface = Surface(texture)
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }

            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start camera repeating request: ${e.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Camera capture session configuration failed")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Error in startPreview: ${e.message}", e)
        }
    }

    fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera: ${e.message}")
        }
        stopBackgroundThread()
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackground").apply {
                start()
                backgroundHandler = Handler(looper)
            }
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500)
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping background thread: ${e.message}")
        }
    }
}

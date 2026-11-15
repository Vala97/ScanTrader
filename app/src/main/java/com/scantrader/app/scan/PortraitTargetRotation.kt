package com.scantrader.app.scan

import android.view.Surface
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.view.LifecycleCameraController

/**
 * CameraX follows the accelerometer and calls [ImageAnalysis.setTargetRotation]
 * even while the activity stays portrait. Held flat, that reports rot=180 and
 * the crop stays 1280×574. A late sample can stick on the preview after the
 * listener is removed, which shows up as a zoom crop shifted to the right.
 * Pin preview, analysis, and capture to portrait, and call [lock] again once
 * the preview surface is streaming.
 */
object PortraitTargetRotation {
    fun lock(controller: LifecycleCameraController) {
        runCatching {
            val type = controller.javaClass.superclass ?: return
            val provider = read(type, controller, "mRotationProvider")
            val listener = read(type, controller, "mDeviceRotationListener")
            if (provider != null && listener != null) {
                provider.javaClass.methods.firstOrNull { it.name == "removeListener" }
                    ?.invoke(provider, listener)
            }
            (read(type, controller, "mPreview") as? Preview)
                ?.setTargetRotation(Surface.ROTATION_0)
            (read(type, controller, "mImageAnalysis") as? ImageAnalysis)
                ?.setTargetRotation(Surface.ROTATION_0)
            (read(type, controller, "mImageCapture") as? ImageCapture)
                ?.setTargetRotation(Surface.ROTATION_0)
        }
    }

    /** Preview use-case rotation, or -1 when the camera has not created it yet. */
    fun previewRotation(controller: LifecycleCameraController): Int {
        val type = controller.javaClass.superclass ?: return -1
        return (read(type, controller, "mPreview") as? Preview)?.targetRotation ?: -1
    }

    private fun read(type: Class<*>, target: Any, name: String): Any? =
        runCatching {
            type.getDeclaredField(name).apply { isAccessible = true }.get(target)
        }.getOrNull()
}

package com.scantrader.app.scan

import android.view.Surface
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.view.LifecycleCameraController

/**
 * CameraX follows the accelerometer and calls [ImageAnalysis.setTargetRotation]
 * even while the preview stays portrait. Held flat, that reports rot=180 and
 * the crop stays 1280×574. Pin analysis and capture to portrait instead.
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
            (read(type, controller, "mImageAnalysis") as? ImageAnalysis)
                ?.setTargetRotation(Surface.ROTATION_0)
            (read(type, controller, "mImageCapture") as? ImageCapture)
                ?.setTargetRotation(Surface.ROTATION_0)
        }
    }

    private fun read(type: Class<*>, target: Any, name: String): Any? =
        runCatching {
            type.getDeclaredField(name).apply { isAccessible = true }.get(target)
        }.getOrNull()
}

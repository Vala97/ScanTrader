package com.scantrader.app.scan

import android.view.Surface
import androidx.camera.view.LifecycleCameraController

/**
 * Portrait 0° lock for analysis frames and stills. The activity is locked to
 * portrait, so the rotation that matches the preview is the sensor mounting
 * relative to [Surface.ROTATION_0], read with the public CameraX API.
 * The tilt-driven `imageInfo.rotationDegrees` / EXIF tag is not used when this
 * is known (see [CameraBitmaps.rotationForPortrait]).
 *
 * [refresh] must run on the main thread (CameraController is main-thread only).
 * The analyzer thread only reads [degrees].
 */
object SensorPortrait {
    @Volatile
    var degrees: Int? = null
        private set

    fun refresh(controller: LifecycleCameraController): Int? {
        val read = runCatching {
            controller.cameraInfo?.getSensorRotationDegrees(Surface.ROTATION_0)
        }.getOrNull()
        if (read != degrees) {
            degrees = read
            ScanDebug.d("camera: portrait lock sensorRotation=${read ?: "unknown"} display=0")
        }
        return read
    }
}

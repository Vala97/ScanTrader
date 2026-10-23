package com.scantrader.app.scan

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector

data class DeviceCamera(
    val id: String,
    val label: String,
    val focalLengthMm: Float?,
)

object CameraDevices {
    /** Back-facing cameras available for scanning, labelled for the settings UI. */
    fun listBackCameras(context: Context): List<DeviceCamera> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()
        val cams = manager.cameraIdList.mapNotNull { id ->
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
                ?: return@mapNotNull null
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
                return@mapNotNull null
            }
            val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
            DeviceCamera(id = id, label = "", focalLengthMm = focal)
        }.sortedBy { it.focalLengthMm ?: Float.MAX_VALUE }

        if (cams.isEmpty()) return emptyList()
        if (cams.size == 1) {
            return listOf(cams[0].labelled("Principale"))
        }

        val shortest = cams.first().focalLengthMm
        val longest = cams.last().focalLengthMm
        val spread = if (shortest != null && longest != null) longest - shortest else 0f

        val roles = cams.mapIndexed { index, _ ->
            when {
                spread > 1f && index == 0 -> "Ultra-grandangolo"
                spread > 1f && index == cams.lastIndex -> "Teleobiettivo"
                else -> "Principale"
            }
        }
        // If multiple share "Principale", number them.
        val roleCounts = roles.groupingBy { it }.eachCount()
        var principalIndex = 0
        return cams.mapIndexed { index, cam ->
            val role = roles[index]
            val label = if ((roleCounts[role] ?: 0) > 1 && role == "Principale") {
                principalIndex += 1
                "Principale $principalIndex"
            } else {
                role
            }
            cam.labelled(label)
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun selectorFor(cameraId: String?): CameraSelector {
        if (cameraId.isNullOrBlank()) return CameraSelector.DEFAULT_BACK_CAMERA
        return CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter { infos ->
                val match = infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
                match.ifEmpty { infos }
            }
            .build()
    }

    private fun DeviceCamera.labelled(role: String): DeviceCamera =
        copy(label = focalLengthMm?.let { "$role · ${"%.1f".format(it)} mm" } ?: role)
}

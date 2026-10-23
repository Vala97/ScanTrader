package com.scantrader.app.scan

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector

object CameraDevices {
    /** Back cameras, including physical lenses nested in a logical multi-camera. */
    fun backCameras(context: Context): List<BackCamera> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()
        val ids = runCatching { manager.cameraIdList.toList() }.getOrNull() ?: return emptyList()
        val openIds = ids.toSet()
        return ids.mapNotNull { id ->
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
                ?: return@mapNotNull null
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
                return@mapNotNull null
            }
            val (minZoom, maxZoom) = zoomBounds(chars)
            BackCamera(
                id = id,
                focalLengthMm = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull(),
                minZoomRatio = minZoom,
                maxZoomRatio = maxZoom,
                physical = physicalLenses(manager, chars, openIds),
            )
        }.sortedBy { it.focalLengthMm ?: Float.MAX_VALUE }
    }

    fun lensOptions(context: Context): List<LensOption> = LensCatalog.optionsFor(backCameras(context))

    @OptIn(ExperimentalCamera2Interop::class)
    fun selectorFor(cameraId: String?): CameraSelector {
        if (cameraId.isNullOrBlank()) return CameraSelector.DEFAULT_BACK_CAMERA
        return CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter { infos ->
                val match = infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
                if (match.isEmpty()) {
                    ScanDebug.w("lens id $cameraId not in CameraX list; using default back")
                    infos
                } else {
                    match
                }
            }
            .build()
    }

    private fun zoomBounds(chars: CameraCharacteristics): Pair<Float, Float> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (range != null) {
                val lower = range.lower
                val upper = range.upper
                if (lower > 0f && upper >= lower) return lower to upper
            }
        }
        val digital = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        return 1f to digital.coerceAtLeast(1f)
    }

    private fun physicalLenses(
        manager: CameraManager,
        chars: CameraCharacteristics,
        openIds: Set<String>,
    ): List<PhysicalLens> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return emptyList()
        if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
            return emptyList()
        }
        return chars.physicalCameraIds.mapNotNull { pid ->
            val physicalChars = runCatching { manager.getCameraCharacteristics(pid) }.getOrNull()
            val facing = physicalChars?.get(CameraCharacteristics.LENS_FACING)
            if (facing != null && facing != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
            PhysicalLens(
                id = pid,
                focalLengthMm = physicalChars
                    ?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.minOrNull(),
                directlyOpenable = pid in openIds,
            )
        }
    }
}

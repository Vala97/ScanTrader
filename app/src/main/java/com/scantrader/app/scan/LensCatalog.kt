package com.scantrader.app.scan

import java.util.Locale
import kotlin.math.abs

/**
 * Back camera as reported by Camera2, including physical lenses of a logical
 * multi-camera. [directlyOpenable] is true only when that id is in
 * [android.hardware.camera2.CameraManager.getCameraIdList] and CameraX can bind it.
 */
data class PhysicalLens(
    val id: String,
    val focalLengthMm: Float?,
    val directlyOpenable: Boolean,
)

data class BackCamera(
    val id: String,
    val focalLengthMm: Float?,
    val minZoomRatio: Float,
    val maxZoomRatio: Float,
    val physical: List<PhysicalLens> = emptyList(),
)

enum class LensKind {
    /** Logical camera, no forced lens or zoom. */
    AUTOMATIC,

    /** Bind this Camera2 id. */
    CAMERA,

    /** Keep the logical camera and call [androidx.camera.core.CameraControl.setZoomRatio]. */
    ZOOM,
}

enum class LensRole {
    AUTOMATIC,
    ULTRAWIDE,
    MAIN,
    TELE,
}

data class LensOption(
    val key: String,
    val kind: LensKind,
    val role: LensRole,
    val cameraId: String? = null,
    val zoomRatio: Float? = null,
    val focalLengthMm: Float? = null,
    /** 1-based when several options share a role; 0 when the role is unique. */
    val roleOrdinal: Int = 0,
    /** Zoom fallbacks that share a role show the ratio (2× vs 5×). */
    val showZoom: Boolean = false,
)

/**
 * Pure lens list used by the scan panel. Android enumeration lives in [CameraDevices].
 *
 * Two or more directly openable back cameras are bound by id. Otherwise (one logical
 * camera, physical lenses not openable — Pixel 9 Pro) the list is the zoom presets
 * 0.5× / 1× / 2× / 5× that fall inside the camera's zoom range.
 */
object LensCatalog {
    const val AUTO_KEY = "auto"

    val PRESETS: List<Float> = listOf(0.5f, 1f, 2f, 5f)

    fun keyForZoom(ratio: Float): String {
        val text = String.format(Locale.US, "%.1f", ratio)
        val trimmed = if (text.endsWith(".0")) text.dropLast(2) else text
        return "zoom:$trimmed"
    }

    fun keyForCamera(id: String): String = "cam:$id"

    fun optionsFor(cameras: List<BackCamera>): List<LensOption> {
        if (cameras.isEmpty()) return emptyList()
        val openIds = cameras.map { it.id }.toSet()
        val logicalWithOpenChildren = cameras.filter { cam ->
            cam.physical.any { it.directlyOpenable && it.id in openIds && it.id != cam.id }
        }
        val withoutCoveredLogical = cameras.filter { it.id !in logicalWithOpenChildren.map { cam -> cam.id }.toSet() }
        val directCameras = withoutCoveredLogical.ifEmpty { cameras }

        val concrete = mutableListOf<LensOption>()
        if (directCameras.size >= 2) {
            concrete += cameraOptions(directCameras)
        }

        val unbound = cameras
            .flatMap { it.physical }
            .filter { !it.directlyOpenable && it.id !in openIds }
            .distinctBy { it.id }
        val needZoom = directCameras.size < 2 || unbound.isNotEmpty()
        if (needZoom) {
            val source = rangeSource(cameras)
            val covered = if (directCameras.size < 2) {
                emptySet()
            } else {
                concrete.map { it.role }.toSet()
            }
            val hostId = if (directCameras.size >= 2) source.id else null
            concrete += zoomOptions(source, hostId, covered)
        }

        return finish(concrete)
    }

    /** Saved lens, else a legacy camera id, else Automatica. */
    fun resolve(
        options: List<LensOption>,
        savedLensKey: String?,
        savedCameraId: String?,
    ): LensOption? {
        if (options.isEmpty()) return null
        if (!savedLensKey.isNullOrBlank()) {
            options.firstOrNull { it.key == savedLensKey }?.let { return it }
        }
        if (!savedCameraId.isNullOrBlank()) {
            options.firstOrNull { it.kind == LensKind.CAMERA && it.cameraId == savedCameraId }
                ?.let { return it }
        }
        return options.firstOrNull { it.kind == LensKind.AUTOMATIC } ?: options.first()
    }

    /** Zoom presets replace the last pinch. Automatic and a bound camera keep it. */
    fun zoomToApply(option: LensOption?): Float? =
        if (option?.kind == LensKind.ZOOM) option.zoomRatio else null

    internal fun inZoomRange(ratio: Float, min: Float, max: Float): Boolean {
        if (!ratio.isFinite() || ratio <= 0f || !min.isFinite() || !max.isFinite()) return false
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        return ratio >= lo - 0.001f && ratio <= hi + 0.001f
    }

    private fun cameraOptions(cameras: List<BackCamera>): List<LensOption> {
        val focals = cameras.map { it.focalLengthMm }
        return cameras
            .sortedBy { it.focalLengthMm ?: Float.MAX_VALUE }
            .map { cam ->
                LensOption(
                    key = keyForCamera(cam.id),
                    kind = LensKind.CAMERA,
                    role = roleForFocal(cam.focalLengthMm, focals),
                    cameraId = cam.id,
                    zoomRatio = 1f,
                    focalLengthMm = cam.focalLengthMm,
                )
            }
    }

    private fun zoomOptions(
        source: BackCamera,
        hostId: String?,
        coveredRoles: Set<LensRole>,
    ): List<LensOption> {
        val presets = PRESETS.filter { ratio ->
            inZoomRange(ratio, source.minZoomRatio, source.maxZoomRatio) &&
                (coveredRoles.isEmpty() || roleForZoom(ratio) !in coveredRoles)
        }
        if (presets.isEmpty()) return emptyList()
        val unboundPhysicals = source.physical.filter { !it.directlyOpenable }
        val carriers = unboundPhysicals.filter { it.focalLengthMm != null }.ifEmpty {
            // A logical camera often reports only the shortest focal. Don't treat that as the
            // main lens when physical focals could not be read.
            if (unboundPhysicals.isNotEmpty()) {
                emptyList()
            } else {
                listOfNotNull(source.focalLengthMm?.let { PhysicalLens(source.id, it, directlyOpenable = true) })
            }
        }
        val carrierFocals = carriers.map { it.focalLengthMm }
        val assigned = presets.map { ratio ->
            val role = roleForZoom(ratio)
            val focal = focalFor(role, ratio, presets, carriers, carrierFocals)
            LensOption(
                key = keyForZoom(ratio),
                kind = LensKind.ZOOM,
                role = role,
                cameraId = hostId,
                zoomRatio = ratio,
                focalLengthMm = focal,
            )
        }
        return assigned.map { option ->
            // Ratio in the label when we have no focal, so 2× and 5× stay distinct.
            option.copy(showZoom = option.focalLengthMm == null)
        }
    }

    private fun focalFor(
        role: LensRole,
        ratio: Float,
        presets: List<Float>,
        carriers: List<PhysicalLens>,
        carrierFocals: List<Float?>,
    ): Float? {
        val matching = carriers.mapNotNull { lens ->
            val focal = lens.focalLengthMm ?: return@mapNotNull null
            if (roleForFocal(focal, carrierFocals) == role) focal else null
        }
        if (matching.isEmpty()) return null
        if (role != LensRole.TELE) return matching.first()
        val telePresets = presets.filter { roleForZoom(it) == LensRole.TELE }
        return if (telePresets.size <= 1 || ratio == telePresets.maxOrNull()) {
            matching.maxOrNull()
        } else {
            null
        }
    }

    private fun rangeSource(cameras: List<BackCamera>): BackCamera {
        val withUnbound = cameras.filter { cam -> cam.physical.any { !it.directlyOpenable } }
        val pool = if (withUnbound.isNotEmpty()) withUnbound else cameras
        return pool.maxBy { it.maxZoomRatio - it.minZoomRatio }
    }

    private fun finish(concrete: List<LensOption>): List<LensOption> {
        val unique = concrete
            .distinctBy { it.key }
            .sortedBy { rank(it) }
        if (unique.size < 2) return withOrdinals(unique)
        return listOf(
            LensOption(key = AUTO_KEY, kind = LensKind.AUTOMATIC, role = LensRole.AUTOMATIC),
        ) + withOrdinals(unique)
    }

    private fun rank(option: LensOption): Float {
        val roleRank = when (option.role) {
            LensRole.ULTRAWIDE -> 0f
            LensRole.MAIN -> 1f
            LensRole.TELE -> 2f
            LensRole.AUTOMATIC -> -1f
        }
        val sub = option.zoomRatio ?: option.focalLengthMm ?: 0f
        return roleRank * 1000f + sub
    }

    private fun withOrdinals(options: List<LensOption>): List<LensOption> {
        val counts = options.groupingBy { it.role }.eachCount()
        val seen = mutableMapOf<LensRole, Int>()
        return options.map { option ->
            val shared = (counts[option.role] ?: 0) > 1
            // Number duplicate mains. Other roles are already distinct via focal or zoom.
            val number = shared && (option.role == LensRole.MAIN ||
                (option.focalLengthMm == null && !option.showZoom))
            if (!number) {
                option
            } else {
                val n = (seen[option.role] ?: 0) + 1
                seen[option.role] = n
                option.copy(roleOrdinal = n)
            }
        }
    }

    internal fun roleForZoom(ratio: Float): LensRole = when {
        ratio < 0.9f -> LensRole.ULTRAWIDE
        ratio <= 1.2f -> LensRole.MAIN
        else -> LensRole.TELE
    }

    internal fun roleForFocal(focal: Float?, peers: List<Float?>): LensRole {
        if (focal == null) return LensRole.MAIN
        val known = peers.filterNotNull().sorted()
        if (known.size <= 1) return LensRole.MAIN
        if (known.last() - known.first() <= 1f) return LensRole.MAIN
        return when {
            abs(focal - known.first()) <= 0.05f && focal < 4f -> LensRole.ULTRAWIDE
            abs(focal - known.last()) <= 0.05f && focal > 9f -> LensRole.TELE
            else -> LensRole.MAIN
        }
    }
}

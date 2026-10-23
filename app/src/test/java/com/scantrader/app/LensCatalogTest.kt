package com.scantrader.app

import com.scantrader.app.scan.BackCamera
import com.scantrader.app.scan.LensCatalog
import com.scantrader.app.scan.LensKind
import com.scantrader.app.scan.LensRole
import com.scantrader.app.scan.PhysicalLens
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensCatalogTest {
    @Test
    fun pixelLikeLogicalCameraUsesZoomPresets() {
        val options = LensCatalog.optionsFor(listOf(pixelLike()))

        assertEquals(
            listOf("auto", "zoom:0.5", "zoom:1", "zoom:2", "zoom:5"),
            options.map { it.key },
        )
        assertEquals(
            listOf(LensRole.AUTOMATIC, LensRole.ULTRAWIDE, LensRole.MAIN, LensRole.TELE, LensRole.TELE),
            options.map { it.role },
        )
        assertTrue(options.none { it.kind == LensKind.CAMERA })
        assertTrue(options.filter { it.kind == LensKind.ZOOM }.all { it.cameraId == null })
        assertEquals(2.2f, options.first { it.key == "zoom:0.5" }.focalLengthMm)
        assertEquals(6.9f, options.first { it.key == "zoom:1" }.focalLengthMm)
        assertNull(options.first { it.key == "zoom:2" }.focalLengthMm)
        assertTrue(options.first { it.key == "zoom:2" }.showZoom)
        assertEquals(18f, options.first { it.key == "zoom:5" }.focalLengthMm)
        assertTrue(options.size > 1)
    }

    @Test
    fun unreadablePhysicalFocalsKeepZoomNames() {
        val options = LensCatalog.optionsFor(
            listOf(
                BackCamera(
                    id = "0",
                    focalLengthMm = 2.2f,
                    minZoomRatio = 0.5f,
                    maxZoomRatio = 30f,
                    physical = listOf(
                        PhysicalLens("2", null, directlyOpenable = false),
                        PhysicalLens("3", null, directlyOpenable = false),
                        PhysicalLens("4", null, directlyOpenable = false),
                    ),
                ),
            ),
        )
        assertTrue(options.filter { it.kind == LensKind.ZOOM }.all { it.focalLengthMm == null && it.showZoom })
        assertEquals(listOf("auto", "zoom:0.5", "zoom:1", "zoom:2", "zoom:5"), options.map { it.key })
    }

    @Test
    fun zoomRangeAloneOffersInRangePresets() {
        val options = LensCatalog.optionsFor(
            listOf(BackCamera(id = "0", focalLengthMm = 6.9f, minZoomRatio = 1f, maxZoomRatio = 8f)),
        )
        assertEquals(listOf("auto", "zoom:1", "zoom:2", "zoom:5"), options.map { it.key })
        assertFalse(options.any { it.key == "zoom:0.5" })
    }

    @Test
    fun presetOutsideRangeIsExcluded() {
        val options = LensCatalog.optionsFor(
            listOf(
                BackCamera(
                    id = "0",
                    focalLengthMm = 6.9f,
                    minZoomRatio = 1f,
                    maxZoomRatio = 3f,
                    physical = listOf(PhysicalLens("9", null, directlyOpenable = false)),
                ),
            ),
        )
        assertEquals(listOf("auto", "zoom:1", "zoom:2"), options.map { it.key })
    }

    @Test
    fun singleCameraWithoutExtraZoomHidesTheSelector() {
        val options = LensCatalog.optionsFor(
            listOf(BackCamera(id = "0", focalLengthMm = 5.4f, minZoomRatio = 1f, maxZoomRatio = 1f)),
        )
        assertTrue(options.size <= 1)
        assertFalse(options.any { it.kind == LensKind.AUTOMATIC })
    }

    @Test
    fun twoOpenableCamerasBindDirectlyWithoutZoomFallback() {
        val options = LensCatalog.optionsFor(
            listOf(
                BackCamera(id = "2", focalLengthMm = 2.2f, minZoomRatio = 1f, maxZoomRatio = 4f),
                BackCamera(id = "0", focalLengthMm = 6.9f, minZoomRatio = 1f, maxZoomRatio = 8f),
                BackCamera(id = "4", focalLengthMm = 18f, minZoomRatio = 1f, maxZoomRatio = 8f),
            ),
        )
        assertEquals(listOf("auto", "cam:2", "cam:0", "cam:4"), options.map { it.key })
        assertEquals(
            listOf(LensRole.AUTOMATIC, LensRole.ULTRAWIDE, LensRole.MAIN, LensRole.TELE),
            options.map { it.role },
        )
        assertTrue(options.none { it.kind == LensKind.ZOOM })
        assertEquals("2", options.first { it.key == "cam:2" }.cameraId)
    }

    @Test
    fun unboundUltrawideFallsBackToZoomWhileOpenableLensesBind() {
        val options = LensCatalog.optionsFor(
            listOf(
                BackCamera(
                    id = "0",
                    focalLengthMm = 6.9f,
                    minZoomRatio = 0.5f,
                    maxZoomRatio = 30f,
                    physical = listOf(
                        PhysicalLens("2", 2.2f, directlyOpenable = false),
                        PhysicalLens("3", 6.9f, directlyOpenable = true),
                        PhysicalLens("4", 18f, directlyOpenable = true),
                    ),
                ),
                BackCamera(id = "3", focalLengthMm = 6.9f, minZoomRatio = 1f, maxZoomRatio = 1f),
                BackCamera(id = "4", focalLengthMm = 18f, minZoomRatio = 1f, maxZoomRatio = 4f),
            ),
        )
        assertEquals(listOf("auto", "zoom:0.5", "cam:3", "cam:4"), options.map { it.key })
        val ultrawide = options.first { it.key == "zoom:0.5" }
        assertEquals(LensKind.ZOOM, ultrawide.kind)
        assertEquals("0", ultrawide.cameraId)
        assertTrue(options.none { it.key.startsWith("zoom:") && it.key != "zoom:0.5" })
    }

    @Test
    fun similarFocalsStayMainAndAreNumbered() {
        val options = LensCatalog.optionsFor(
            listOf(
                BackCamera(id = "0", focalLengthMm = 5.1f, minZoomRatio = 1f, maxZoomRatio = 1f),
                BackCamera(id = "1", focalLengthMm = 5.4f, minZoomRatio = 1f, maxZoomRatio = 1f),
            ),
        )
        val mains = options.filter { it.role == LensRole.MAIN }
        assertEquals(listOf(1, 2), mains.map { it.roleOrdinal })
        assertTrue(options.any { it.kind == LensKind.AUTOMATIC })
    }

    @Test
    fun zoomKeysRoundTripIndependentOfLocale() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.ITALY)
        try {
            assertEquals("zoom:0.5", LensCatalog.keyForZoom(0.5f))
            assertEquals("zoom:1", LensCatalog.keyForZoom(1f))
            assertEquals("zoom:2", LensCatalog.keyForZoom(2f))
            assertEquals("zoom:5", LensCatalog.keyForZoom(5f))
            val options = LensCatalog.optionsFor(listOf(pixelLike()))
            options.forEach { option ->
                assertEquals(option.key, LensCatalog.resolve(options, option.key, "ignored")?.key)
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun resolvePrefersLensKeyThenLegacyCameraThenAutomatic() {
        val pixel = LensCatalog.optionsFor(listOf(pixelLike()))
        val tele = LensCatalog.resolve(pixel, "zoom:5", "0")
        assertEquals("zoom:5", tele?.key)
        assertEquals(5f, LensCatalog.zoomToApply(tele))
        assertNull(LensCatalog.zoomToApply(LensCatalog.resolve(pixel, null, null)))
        assertEquals(LensCatalog.AUTO_KEY, LensCatalog.resolve(pixel, null, "0")?.key)
        assertEquals(LensCatalog.AUTO_KEY, LensCatalog.resolve(pixel, "zoom:30", "0")?.key)

        val direct = LensCatalog.optionsFor(
            listOf(
                BackCamera(id = "2", focalLengthMm = 2.2f, minZoomRatio = 1f, maxZoomRatio = 2f),
                BackCamera(id = "4", focalLengthMm = 18f, minZoomRatio = 1f, maxZoomRatio = 2f),
            ),
        )
        assertEquals("cam:4", LensCatalog.resolve(direct, null, "4")?.key)
        assertNull(LensCatalog.zoomToApply(LensCatalog.resolve(direct, "cam:4", null)))
    }

    private fun pixelLike(): BackCamera = BackCamera(
        id = "0",
        focalLengthMm = 6.9f,
        minZoomRatio = 0.5f,
        maxZoomRatio = 30f,
        physical = listOf(
            PhysicalLens("2", 2.2f, directlyOpenable = false),
            PhysicalLens("3", 6.9f, directlyOpenable = false),
            PhysicalLens("4", 18f, directlyOpenable = false),
        ),
    )
}

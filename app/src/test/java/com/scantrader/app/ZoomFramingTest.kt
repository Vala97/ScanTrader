package com.scantrader.app

import com.scantrader.app.scan.ZoomFraming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomFramingTest {
    @Test
    fun defaultRatioAfterBindDoesNotReplaceStoredFraming() {
        assertTrue(ZoomFraming.ignoreReportedZoom(stored = 2.4f, reported = 1f, restoreArmed = true))
        assertTrue(ZoomFraming.ignoreReportedZoom(stored = 0.5f, reported = 1f, restoreArmed = true))
        assertFalse(ZoomFraming.ignoreReportedZoom(stored = 2.4f, reported = 2.37f, restoreArmed = true))
        assertFalse(ZoomFraming.ignoreReportedZoom(stored = 2.4f, reported = 1f, restoreArmed = false))
    }

    @Test
    fun clampsRestoreRatioToTheCameraRange() {
        assertEquals(5f, ZoomFraming.clamp(8f, min = 0.5f, max = 5f))
        assertEquals(0.5f, ZoomFraming.clamp(0.2f, min = 0.5f, max = 5f))
        assertEquals(1.8f, ZoomFraming.clamp(1.8f, min = 0.5f, max = 5f))
        assertNull(ZoomFraming.clamp(0f, min = 0.5f, max = 5f))
    }

    @Test
    fun unbindJumpToOneIsNotAPinch() {
        assertTrue(ZoomFraming.isUnbindReset(stored = 2.4f, reported = 1f))
        assertTrue(ZoomFraming.isUnbindReset(stored = 0.5f, reported = 1f))
        assertFalse(ZoomFraming.isUnbindReset(stored = 1f, reported = 1f))
        assertFalse(ZoomFraming.isUnbindReset(stored = 2.4f, reported = 2.2f))
    }

    @Test
    fun pendingRestoreDoesNotAcceptTheDefaultRatio() {
        assertFalse(ZoomFraming.acceptReportedZoom(stored = 2.4f, reported = 1f, restorePending = true))
        assertFalse(ZoomFraming.acceptReportedZoom(stored = 0.5f, reported = 1f, restorePending = false))
        assertTrue(ZoomFraming.acceptReportedZoom(stored = 2.4f, reported = 2.37f, restorePending = true))
        assertTrue(ZoomFraming.acceptReportedZoom(stored = 2.4f, reported = 2.1f, restorePending = false))
    }

    @Test
    fun sameLensDoesNotRebindWhenReturningFromConfirm() {
        assertFalse(
            ZoomFraming.shouldRebindCamera(
                alreadyBound = true,
                boundCameraId = null,
                requestedCameraId = null,
            ),
        )
        assertFalse(
            ZoomFraming.shouldRebindCamera(
                alreadyBound = true,
                boundCameraId = "0",
                requestedCameraId = "0",
            ),
        )
        assertTrue(
            ZoomFraming.shouldRebindCamera(
                alreadyBound = false,
                boundCameraId = "0",
                requestedCameraId = "0",
            ),
        )
        assertTrue(
            ZoomFraming.shouldRebindCamera(
                alreadyBound = true,
                boundCameraId = "0",
                requestedCameraId = "2",
            ),
        )
    }

    @Test
    fun zoomLogNamesRequestedAppliedLensAndPreviewSize() {
        val line = ZoomFraming.zoomLogLine(
            requested = 2f,
            applied = 2f,
            lens = "zoom:2",
            previewWidth = 1080,
            previewHeight = 2400,
        )
        assertEquals(
            "zoom: requested=2.00 applied=2.00 lens=zoom:2 previewSize=1080x2400",
            line,
        )
        val waiting = ZoomFraming.zoomLogLine(
            requested = 0.5f,
            applied = null,
            lens = null,
            previewWidth = 0,
            previewHeight = 0,
        )
        assertEquals(
            "zoom: requested=0.50 applied=pending lens=none previewSize=0x0",
            waiting,
        )
    }

    @Test
    fun analysisSizeStaysFixedAcrossRebind() {
        assertEquals(1280, ZoomFraming.ANALYSIS_WIDTH)
        assertEquals(960, ZoomFraming.ANALYSIS_HEIGHT)
    }

    @Test
    fun zoomIsNotAppliedBeforeCameraControlAndZoomState() {
        val gate = com.scantrader.app.scan.ZoomRestoreController()
        val beforeControl = gate.onZoomSample(
            reported = 1f,
            requested = 2.4f,
            minZoom = 0.5f,
            maxZoom = 5f,
            cameraControlReady = false,
        )
        assertTrue(beforeControl is com.scantrader.app.scan.ZoomRestoreStep.Wait)

        val controlReady = gate.onCameraControlReady(requested = 2.4f, minZoom = 0.5f, maxZoom = 5f)
        assertTrue(controlReady is com.scantrader.app.scan.ZoomRestoreStep.Apply)
        val apply = controlReady as com.scantrader.app.scan.ZoomRestoreStep.Apply
        assertEquals(2.4f, apply.requested, 0.001f)
        assertEquals(2.4f, apply.applied, 0.001f)
    }

    @Test
    fun defaultRatioAfterBindIsReappliedUntilTheStoredZoomIsObserved() {
        val gate = com.scantrader.app.scan.ZoomRestoreController()
        gate.onCameraControlReady(requested = 2f, minZoom = 0.5f, maxZoom = 8f)
        val first = gate.onZoomSample(
            reported = 1f,
            requested = 2f,
            minZoom = 0.5f,
            maxZoom = 8f,
            cameraControlReady = true,
        )
        assertTrue(first is com.scantrader.app.scan.ZoomRestoreStep.Apply)

        val resetAgain = gate.onZoomSample(
            reported = 1f,
            requested = 2f,
            minZoom = 0.5f,
            maxZoom = 8f,
            cameraControlReady = true,
        )
        assertTrue(resetAgain is com.scantrader.app.scan.ZoomRestoreStep.Apply)

        val settled = gate.onZoomSample(
            reported = 2f,
            requested = 2f,
            minZoom = 0.5f,
            maxZoom = 8f,
            cameraControlReady = true,
        )
        assertTrue(settled is com.scantrader.app.scan.ZoomRestoreStep.Confirmed)

        val pinch = gate.onZoomSample(
            reported = 2.6f,
            requested = 2f,
            minZoom = 0.5f,
            maxZoom = 8f,
            cameraControlReady = true,
        )
        assertTrue(pinch is com.scantrader.app.scan.ZoomRestoreStep.Passthrough)
    }

    @Test
    fun matchingSampleFromBeforeControlDoesNotSettleTheRestore() {
        val gate = com.scantrader.app.scan.ZoomRestoreController()
        val early = gate.onZoomSample(
            reported = 2f,
            requested = 2f,
            minZoom = 0.5f,
            maxZoom = 8f,
            cameraControlReady = false,
        )
        assertTrue(early is com.scantrader.app.scan.ZoomRestoreStep.Wait)
        val flushed = gate.onCameraControlReady(requested = 2f, minZoom = 0.5f, maxZoom = 8f)
        assertTrue(flushed is com.scantrader.app.scan.ZoomRestoreStep.Apply)
        assertFalse(flushed is com.scantrader.app.scan.ZoomRestoreStep.Confirmed)
    }
}

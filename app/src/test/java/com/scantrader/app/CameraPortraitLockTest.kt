package com.scantrader.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import com.scantrader.app.scan.CameraBitmaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Portrait 0° lock: the sensor mounting decides, never the tilt-driven reading. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraPortraitLockTest {
    @Test
    fun landscapeBufferUsesSensorRotationForAnyReading() {
        for (reported in listOf(0, 90, 180, 270)) {
            assertEquals(
                "back sensor, reported=$reported",
                90,
                CameraBitmaps.rotationForPortrait(reported, 1280, 960, sensorPortraitDegrees = 90),
            )
            assertEquals(
                "front sensor, reported=$reported",
                270,
                CameraBitmaps.rotationForPortrait(reported, 1280, 960, sensorPortraitDegrees = 270),
            )
        }
    }

    @Test
    fun unknownSensorKeepsTheOldGuess() {
        assertEquals(90, CameraBitmaps.rotationForPortrait(180, 1280, 960, sensorPortraitDegrees = null))
        assertEquals(270, CameraBitmaps.rotationForPortrait(270, 1280, 960, sensorPortraitDegrees = null))
    }

    @Test
    fun stillIgnoresExif270OnABackSensor() {
        // Landscape JPEG pixels, EXIF says 270 because the phone was read as upside down.
        assertEquals(90, CameraBitmaps.rotationForStill(270, 4000, 3000, sensorPortraitDegrees = 90))
        assertEquals(90, CameraBitmaps.rotationForStill(0, 4000, 3000, sensorPortraitDegrees = 90))
        assertEquals(270, CameraBitmaps.rotationForStill(90, 4000, 3000, sensorPortraitDegrees = 270))
        // The camera already stood the pixels up: no extra turn from EXIF.
        assertEquals(0, CameraBitmaps.rotationForStill(270, 3000, 4000, sensorPortraitDegrees = 90))
        // Sensor unknown: EXIF as before.
        assertEquals(270, CameraBitmaps.rotationForStill(270, 4000, 3000, sensorPortraitDegrees = null))
    }

    @Test
    fun syntheticCardStaysTitleUpWhenTiltReads270() {
        // Upright card as the user sees it: red title band at the top, blue bottom.
        val card = Bitmap.createBitmap(30, 40, Bitmap.Config.ARGB_8888)
        for (y in 0 until 40) {
            for (x in 0 until 30) {
                card.setPixel(x, y, if (y < 8) Color.RED else if (y >= 32) Color.BLUE else Color.WHITE)
            }
        }
        // A 90° back sensor stores that frame turned 90° counter-clockwise (landscape).
        val sensorBuffer = Bitmap.createBitmap(card, 0, 0, 30, 40, Matrix().apply { postRotate(-90f) }, false)
        assertEquals(40, sensorBuffer.width)
        assertEquals(30, sensorBuffer.height)

        val rotation = CameraBitmaps.rotationForPortrait(
            reportedDegrees = 270,
            sourceWidth = sensorBuffer.width,
            sourceHeight = sensorBuffer.height,
            sensorPortraitDegrees = 90,
        )
        val upright = CameraBitmaps.rotateDegrees(sensorBuffer, rotation, recycleSource = false)
        assertEquals(30, upright.width)
        assertEquals(40, upright.height)
        assertEquals(Color.RED, upright.getPixel(15, 2))
        assertEquals(Color.BLUE, upright.getPixel(15, 37))

        // The old reading (270 trusted) is the 180° flip the Glade scan showed.
        val flipped = CameraBitmaps.rotateDegrees(sensorBuffer, 270, recycleSource = false)
        assertTrue(flipped.getPixel(15, 2) == Color.BLUE && flipped.getPixel(15, 37) == Color.RED)
    }
}

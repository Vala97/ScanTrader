package com.scantrader.app.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface

/**
 * Converts CameraX buffers into upright bitmaps that match the on-screen preview
 * orientation. Raw [ImageProxy.toBitmap] / JPEG decode often leave the sensor
 * landscape buffer unrotated, so the card-frame crop hits rules text instead of
 * the title / collector line.
 */
object CameraBitmaps {
    data class Upright(
        val bitmap: Bitmap,
        /** Rotation actually applied. Portrait UI keeps 90°/270°. */
        val rotationDegrees: Int,
        /** [androidx.camera.core.ImageInfo.getRotationDegrees] before the portrait lock. */
        val reportedRotation: Int,
        val sourceSize: Size,
        val cropApplied: Boolean,
    )

    /**
     * The UI is locked to portrait 0°. The rotation that stands a landscape
     * sensor buffer up to match that preview is the sensor mounting relative to
     * [android.view.Surface.ROTATION_0] (`cameraInfo.getSensorRotationDegrees(ROTATION_0)`):
     * 90 for the Pixel back camera, usually 270 for a front camera.
     *
     * [reportedDegrees] ([androidx.camera.core.ImageInfo.getRotationDegrees] or the JPEG
     * EXIF tag) follows the accelerometer when CameraX is not pinned. Held over a
     * table it can read the phone as upside down and report 270 on a 90 sensor,
     * which turns the card 180°. With a known [sensorPortraitDegrees] that reading
     * is ignored. Without one, the old guess stays (0/180 → 90, 90/270 kept).
     */
    fun rotationForPortrait(
        reportedDegrees: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        sensorPortraitDegrees: Int? = null,
    ): Int {
        val reported = Math.floorMod(reportedDegrees, 360)
        if (sourceWidth <= sourceHeight) return reported
        val sensor = sensorPortraitDegrees?.let { Math.floorMod(it, 360) }
        if (sensor == 90 || sensor == 270) return sensor
        return if (reported == 90 || reported == 270) reported else 90
    }

    /**
     * Rotation for a saved still. The decoded pixels ignore EXIF. A landscape
     * JPEG gets the sensor portrait rotation, never the tilt-driven EXIF tag.
     * A JPEG the camera already stood up (portrait pixels) is kept as is.
     * Without a known sensor rotation the EXIF tag is used as before.
     */
    fun rotationForStill(
        exifDegrees: Int,
        pixelWidth: Int,
        pixelHeight: Int,
        sensorPortraitDegrees: Int?,
    ): Int {
        val sensor = sensorPortraitDegrees?.let { Math.floorMod(it, 360) }
        if (sensor != 90 && sensor != 270) return Math.floorMod(exifDegrees, 360)
        return if (pixelWidth > pixelHeight) sensor else 0
    }

    fun fromImageProxy(proxy: ImageProxy, sensorPortraitDegrees: Int? = null): Upright {
        val reported = proxy.imageInfo.rotationDegrees
        val source = proxy.toBitmap()
        val sourceSize = Size(source.width, source.height)
        val rotation = rotationForPortrait(reported, source.width, source.height, sensorPortraitDegrees)
        val upright = rotate(source, rotation)
        val rect = proxy.cropRect
        val fullBuffer = rect.left <= 0 &&
            rect.top <= 0 &&
            rect.right >= proxy.width &&
            rect.bottom >= proxy.height
        if (fullBuffer || rect.width() <= 1 || rect.height() <= 1) {
            return finish(upright, rotation, reported, sourceSize, cropApplied = false)
        }
        val mapped = mapCropRect(
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
            rotationDegrees = rotation,
            width = proxy.width,
            height = proxy.height,
        )
        val left = mapped.left.coerceIn(0, upright.width - 1)
        val top = mapped.top.coerceIn(0, upright.height - 1)
        val w = mapped.width.coerceAtMost(upright.width - left).coerceAtLeast(1)
        val h = mapped.height.coerceAtMost(upright.height - top).coerceAtLeast(1)
        if (left == 0 && top == 0 && w == upright.width && h == upright.height) {
            return finish(upright, rotation, reported, sourceSize, cropApplied = false)
        }
        val cropped = Bitmap.createBitmap(upright, left, top, w, h)
        if (cropped !== upright) upright.recycle()
        return finish(cropped, rotation, reported, sourceSize, cropApplied = true)
    }

    data class Still(val bitmap: Bitmap, val exifDegrees: Int, val rotationDegrees: Int)

    fun fromJpegFile(path: String, sensorPortraitDegrees: Int? = null): Still? {
        val bitmap = BitmapFactory.decodeFile(path) ?: return null
        val exif = when (
            ExifInterface(path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        val degrees = rotationForStill(exif, bitmap.width, bitmap.height, sensorPortraitDegrees)
        return Still(ensurePortrait(rotate(bitmap, degrees)), exif, degrees)
    }

    /** A landscape analysis or still buffer is stood up before the card crop. */
    fun ensurePortrait(source: Bitmap): Bitmap {
        if (source.width <= source.height) return source
        return rotate(source, 90)
    }

    fun rotateDegrees(source: Bitmap, degrees: Int, recycleSource: Boolean = true): Bitmap {
        if (degrees % 360 == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (recycleSource && rotated !== source) source.recycle()
        return rotated
    }

    private fun finish(
        bitmap: Bitmap,
        rotation: Int,
        reported: Int,
        sourceSize: Size,
        cropApplied: Boolean,
    ): Upright {
        val portrait = ensurePortrait(bitmap)
        return Upright(portrait, rotation, reported, sourceSize, cropApplied)
    }

    private fun rotate(source: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        return rotated
    }

    data class MappedRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** Maps a buffer-space crop rect into coordinates after a clockwise rotation. */
    fun mapCropRect(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        rotationDegrees: Int,
        width: Int,
        height: Int,
    ): MappedRect {
        val mapped = when ((rotationDegrees % 360 + 360) % 360) {
            90 -> MappedRect(height - bottom, left, height - top, right)
            180 -> MappedRect(width - right, height - bottom, width - left, height - top)
            270 -> MappedRect(top, width - right, bottom, width - left)
            else -> MappedRect(left, top, right, bottom)
        }
        return MappedRect(
            left = minOf(mapped.left, mapped.right),
            top = minOf(mapped.top, mapped.bottom),
            right = maxOf(mapped.left, mapped.right),
            bottom = maxOf(mapped.top, mapped.bottom),
        )
    }
}

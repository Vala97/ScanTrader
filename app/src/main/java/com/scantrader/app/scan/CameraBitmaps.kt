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
        /** CameraX viewport crop was smaller than the buffer. The pixels are not cropped here. */
        val cropApplied: Boolean,
        val cropLeft: Int,
        val cropTop: Int,
        val cropRight: Int,
        val cropBottom: Int,
    )

    /**
     * Landscape sensor buffers become portrait at 90° or 270°. That is the
     * rotation the upright scans used (`rot=90`, `bmp=574x1280`).
     * 0° and 180° leave the buffer landscape: CameraX took them from the
     * accelerometer while the phone was flat, not from the portrait preview.
     */
    fun rotationForPortrait(reportedDegrees: Int, sourceWidth: Int, sourceHeight: Int): Int {
        val reported = Math.floorMod(reportedDegrees, 360)
        if (sourceWidth <= sourceHeight) return reported
        return if (reported == 90 || reported == 270) reported else 90
    }

    fun fromImageProxy(proxy: ImageProxy): Upright {
        val reported = proxy.imageInfo.rotationDegrees
        val source = proxy.toBitmap()
        val sourceSize = Size(source.width, source.height)
        val rotation = rotationForPortrait(reported, source.width, source.height)
        val upright = rotate(source, rotation)
        val rect = proxy.cropRect
        val fullBuffer = rect.left <= 0 &&
            rect.top <= 0 &&
            rect.right >= proxy.width &&
            rect.bottom >= proxy.height
        // Leave the buffer intact. [CardFrameGeometry.analysisRect] maps the guide
        // once: linearly when the buffer already matches the view, otherwise
        // with FILL_CENTER. Cropping this rect and mapping again shifts the card.
        val cropApplied = !fullBuffer && rect.width() > 1 && rect.height() > 1
        return finish(
            bitmap = upright,
            rotation = rotation,
            reported = reported,
            sourceSize = sourceSize,
            cropApplied = cropApplied,
            cropLeft = rect.left,
            cropTop = rect.top,
            cropRight = rect.right,
            cropBottom = rect.bottom,
        )
    }

    fun fromJpegFile(path: String): Bitmap? {
        val bitmap = BitmapFactory.decodeFile(path) ?: return null
        val degrees = when (
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
        return ensurePortrait(rotate(bitmap, degrees))
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
        cropLeft: Int,
        cropTop: Int,
        cropRight: Int,
        cropBottom: Int,
    ): Upright {
        val portrait = ensurePortrait(bitmap)
        return Upright(
            bitmap = portrait,
            rotationDegrees = rotation,
            reportedRotation = reported,
            sourceSize = sourceSize,
            cropApplied = cropApplied,
            cropLeft = cropLeft,
            cropTop = cropTop,
            cropRight = cropRight,
            cropBottom = cropBottom,
        )
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

package com.scantrader.app

import com.scantrader.app.vision.NpyReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NpyReaderStreamTest {
    private fun floatBytes(values: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buf.putFloat(it) }
        return buf.array()
    }

    /** Hands out at most 7 bytes per read, like a zip entry stream. */
    private class Trickle(bytes: ByteArray) : InputStream() {
        private val inner = ByteArrayInputStream(bytes)
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, minOf(len, 7))
    }

    private fun npy(header: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte(), 1, 0))
        var text = header
        while ((10 + text.length + 1) % 16 != 0) text += " "
        text += "\n"
        out.write(text.length and 0xff)
        out.write(text.length shr 8)
        out.write(text.toByteArray(Charsets.US_ASCII))
        out.write(body)
        return out.toByteArray()
    }

    @Test
    fun chunkedFloatsMatchAcrossChunkBoundaries() {
        val values = FloatArray(1031) { i -> i * 0.25f - 100f }
        for (chunk in listOf(4, 12, 64, 4096, 64 * 1024)) {
            val read = NpyReader.readFloats(Trickle(floatBytes(values)), values.size, chunk)
            assertArrayEquals("chunk=$chunk", values, read, 0f)
        }
    }

    @Test
    fun matrixAndIdsReadFromAStream() {
        val rows = 5
        val cols = 3
        val values = FloatArray(rows * cols) { it.toFloat() / 7f }
        val matrix = NpyReader.readFloatMatrix(
            Trickle(npy("{'descr': '<f4', 'fortran_order': False, 'shape': ($rows, $cols), }", floatBytes(values))),
        )
        assertEquals(rows, matrix.rows)
        assertEquals(cols, matrix.cols)
        assertArrayEquals(values, matrix.data, 0f)

        val ids = listOf("mtg-1", "pkm-é", "x")
        val width = 6
        val body = ByteBuffer.allocate(ids.size * width * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (id in ids) {
            val cps = id.codePoints().toArray()
            for (k in 0 until width) body.putInt(if (k < cps.size) cps[k] else 0)
        }
        val read = NpyReader.readUnicode1D(
            Trickle(npy("{'descr': '<U$width', 'fortran_order': False, 'shape': (${ids.size},), }", body.array())),
        )
        assertEquals(ids, read.toList())
    }

    @Test
    fun truncatedStreamFails() {
        val result = runCatching { NpyReader.readFloats(ByteArrayInputStream(ByteArray(10)), 4) }
        assertTrue(result.isFailure)
    }
}

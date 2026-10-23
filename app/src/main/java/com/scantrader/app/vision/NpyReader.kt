package com.scantrader.app.vision

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Minimal NumPy `.npy` reader for Milo catalogs (float32 matrices + `<Un` unicode ids).
 */
object NpyReader {
    fun readFloatMatrix(input: InputStream): FloatArray2D {
        val header = readHeader(input)
        require(header.descr == "<f4" || header.descr == "|f4" || header.descr == floatDescr) {
            "Expected float32, got ${header.descr}"
        }
        require(header.shape.size == 2) { "Expected 2D float matrix, got ${header.shape.toList()}" }
        val rows = header.shape[0]
        val cols = header.shape[1]
        val count = rows * cols
        val bytes = input.readNBytesExact(count * 4)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val data = FloatArray(count)
        buf.get(data)
        return FloatArray2D(rows, cols, data)
    }

    fun readUnicode1D(input: InputStream): Array<String> {
        val header = readHeader(input)
        val match = Regex("""([<>|])U(\d+)""").matchEntire(header.descr)
            ?: error("Expected unicode descr <Un, got ${header.descr}")
        val width = match.groupValues[2].toInt()
        require(header.shape.size == 1) { "Expected 1D unicode array" }
        val count = header.shape[0]
        val bytesPerChar = 4
        val rowBytes = width * bytesPerChar
        val raw = input.readNBytesExact(count * rowBytes)
        val charset = Charset.forName("UTF-32LE")
        return Array(count) { i ->
            val offset = i * rowBytes
            val slice = raw.copyOfRange(offset, offset + rowBytes)
            // Trim trailing NUL code points.
            var end = slice.size
            while (end >= 4 &&
                slice[end - 4] == 0.toByte() &&
                slice[end - 3] == 0.toByte() &&
                slice[end - 2] == 0.toByte() &&
                slice[end - 1] == 0.toByte()
            ) {
                end -= 4
            }
            String(slice, 0, end, charset)
        }
    }

    private fun readHeader(input: InputStream): NpyHeader {
        val magic = input.readNBytesExact(6)
        require(magic.contentEquals(byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte()))) {
            "Not a .npy file"
        }
        val major = input.read()
        val minor = input.read()
        require(major == 1 && minor == 0) { "Unsupported npy version $major.$minor" }
        val headerLenBytes = input.readNBytesExact(2)
        val headerLen = (headerLenBytes[0].toInt() and 0xff) or ((headerLenBytes[1].toInt() and 0xff) shl 8)
        val headerText = input.readNBytesExact(headerLen).toString(Charsets.US_ASCII)
        val descr = Regex("""'descr':\s*'([^']+)'""").find(headerText)?.groupValues?.get(1)
            ?: error("Missing descr in $headerText")
        val fortran = headerText.contains("'fortran_order': True")
        require(!fortran) { "Fortran-order arrays not supported" }
        val shapeMatch = Regex("""'shape':\s*\(([^)]*)\)""").find(headerText)
            ?: error("Missing shape in $headerText")
        val shape = shapeMatch.groupValues[1]
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.toInt() }
            .toIntArray()
        return NpyHeader(descr, shape)
    }

    private fun InputStream.readNBytesExact(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val read = read(out, off, n - off)
            if (read < 0) error("Unexpected EOF ($off/$n)")
            off += read
        }
        return out
    }

    private data class NpyHeader(val descr: String, val shape: IntArray)

    private const val floatDescr = "<f4"
}

data class FloatArray2D(
    val rows: Int,
    val cols: Int,
    val data: FloatArray,
) {
    fun row(index: Int): FloatArray {
        val start = index * cols
        return data.copyOfRange(start, start + cols)
    }
}

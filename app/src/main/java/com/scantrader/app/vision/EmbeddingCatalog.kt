package com.scantrader.app.vision

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On-device nearest-neighbour gallery of L2-normalised Milo embeddings.
 * Packed as `.stcat` for fast reload after the first NPZ conversion.
 */
class EmbeddingCatalog private constructor(
    val cardIds: Array<String>,
    private val embeddings: FloatArray,
    val dim: Int,
) {
    val size: Int get() = cardIds.size

    data class Hit(val cardId: String, val score: Float)

    fun search(query: FloatArray, topK: Int = 8): List<Hit> {
        require(query.size == dim)
        val k = min(topK, size).coerceAtLeast(0)
        if (k == 0) return emptyList()
        val bestScores = FloatArray(k) { Float.NEGATIVE_INFINITY }
        val bestIndex = IntArray(k) { -1 }
        var threshold = Float.NEGATIVE_INFINITY
        for (i in 0 until size) {
            val base = i * dim
            var dot = 0f
            for (d in 0 until dim) {
                dot += embeddings[base + d] * query[d]
            }
            if (dot <= threshold && bestIndex[k - 1] >= 0) continue
            // Insert into sorted top-k (descending).
            var pos = k - 1
            while (pos > 0 && dot > bestScores[pos - 1]) {
                bestScores[pos] = bestScores[pos - 1]
                bestIndex[pos] = bestIndex[pos - 1]
                pos--
            }
            if (dot > bestScores[pos] || bestIndex[pos] < 0) {
                bestScores[pos] = dot
                bestIndex[pos] = i
            }
            threshold = bestScores[k - 1]
        }
        return (0 until k)
            .filter { bestIndex[it] >= 0 }
            .map { Hit(cardIds[bestIndex[it]], bestScores[it]) }
    }

    fun save(file: File) {
        DataOutputStream(FileOutputStream(file).buffered()).use { out ->
            val magic = MAGIC.toByteArray(Charsets.US_ASCII)
            require(magic.size == 8)
            out.write(magic)
            out.writeInt(VERSION)
            out.writeInt(size)
            out.writeInt(dim)
            for (v in embeddings) out.writeFloat(v)
            for (id in cardIds) {
                val bytes = id.toByteArray(Charsets.UTF_8)
                require(bytes.size <= 65535)
                out.writeShort(bytes.size)
                out.write(bytes)
            }
        }
    }

    companion object {
        /** Exactly 8 ASCII bytes. */
        private const val MAGIC = "STCAT10\u0000"
        private const val VERSION = 1
        const val EMBED_DIM = 128

        fun create(ids: Array<String>, embeddings: FloatArray, dim: Int): EmbeddingCatalog =
            EmbeddingCatalog(ids, embeddings, dim)

        fun fromNpz(npzFile: File): EmbeddingCatalog {
            ZipFile(npzFile).use { zip ->
                val embEntry = zip.getEntry("embeddings.npy")
                    ?: error("embeddings.npy missing in ${npzFile.name}")
                val idsEntry = zip.getEntry("card_ids.npy")
                    ?: error("card_ids.npy missing in ${npzFile.name}")
                val matrix = zip.getInputStream(embEntry).use { NpyReader.readFloatMatrix(it) }
                val ids = zip.getInputStream(idsEntry).use { NpyReader.readUnicode1D(it) }
                require(matrix.rows == ids.size) {
                    "embeddings rows ${matrix.rows} != ids ${ids.size}"
                }
                require(matrix.cols == EMBED_DIM) { "Expected dim $EMBED_DIM, got ${matrix.cols}" }
                return EmbeddingCatalog(ids, matrix.data, matrix.cols)
            }
        }

        fun load(file: File): EmbeddingCatalog {
            DataInputStream(FileInputStream(file).buffered()).use { input ->
                val magicBytes = ByteArray(8)
                input.readFully(magicBytes)
                require(magicBytes.toString(Charsets.US_ASCII) == MAGIC) { "Bad stcat magic" }
                val version = input.readInt()
                require(version == VERSION) { "Unsupported stcat version $version" }
                val count = input.readInt()
                val dim = input.readInt()
                val embeddings = FloatArray(count * dim)
                for (i in embeddings.indices) {
                    embeddings[i] = input.readFloat()
                }
                val ids = Array(count) {
                    val len = input.readUnsignedShort()
                    val bytes = ByteArray(len)
                    input.readFully(bytes)
                    bytes.toString(Charsets.UTF_8)
                }
                return EmbeddingCatalog(ids, embeddings, dim)
            }
        }

        fun l2Normalize(vector: FloatArray): FloatArray {
            var sum = 0f
            for (v in vector) sum += v * v
            val inv = if (sum > 0f) 1f / sqrt(sum) else 0f
            return FloatArray(vector.size) { i -> vector[i] * inv }
        }
    }
}

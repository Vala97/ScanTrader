package com.scantrader.app.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Color
import com.scantrader.app.scan.ScanDebug
import java.io.File
import java.nio.FloatBuffer

/**
 * Skip the 180° embedding when the upright neighbour is already unmistakable.
 * Gallery noise starts at 0.55. 0.90 is above the 0.88 bar [com.scantrader.app.vision.MatchFusion]
 * treats as a decisive visual hit, and the margin keeps near-tied reprints on the slow path.
 */
object RotationSkip {
    const val MIN_BEST_COSINE = 0.90f
    const val MIN_MARGIN = 0.08f

    fun skip(scores: List<Float>): Boolean {
        val ordered = scores.filter { !it.isNaN() }.sortedDescending()
        val best = ordered.firstOrNull() ?: return false
        if (best < MIN_BEST_COSINE) return false
        val second = ordered.getOrNull(1) ?: return true
        return best - second >= MIN_MARGIN
    }
}

/**
 * NNAPI is registered only on a real device at API 29+ and only with the empty flag set.
 * [ai.onnxruntime.providers.NNAPIFlags.USE_FP16] changes the embedding.
 * [ai.onnxruntime.providers.NNAPIFlags.CPU_DISABLED] fails the load when an op has no device kernel.
 * An emulator NNAPI driver is not used.
 */
object OnnxProviders {
    const val MIN_NNAPI_SDK = 29

    fun emulator(fingerprint: String, hardware: String, model: String, product: String): Boolean {
        val blob = "$fingerprint $hardware $model $product".lowercase()
        return blob.contains("generic") ||
            blob.contains("emulator") ||
            blob.contains("sdk_gphone") ||
            blob.contains("goldfish") ||
            blob.contains("ranchu") ||
            blob.contains("android sdk")
    }

    fun nnapiIsSafe(sdkInt: Int, emulator: Boolean): Boolean {
        if (emulator) return false
        return sdkInt >= MIN_NNAPI_SDK
    }
}

data class RotationEmbed(
    val vectors: List<FloatArray>,
    val rotationSkipped: Boolean,
    val embedMs: Long,
)

/**
 * Milo ONNX embedder: 448×448 ImageNet-normalised RGB → 128-d L2 unit vector.
 * Model: https://huggingface.co/HanClinto/milo (AGPL-3.0)
 */
class MiloEmbedder(
    modelFile: File,
) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    val providerName: String
    private val inputName: String

    init {
        val opened = openSession(modelFile)
        session = opened.first
        providerName = opened.second
        inputName = session.inputNames.first()
        ScanDebug.d("onnx: provider=$providerName")
    }

    fun embed(cardCrop: Bitmap): FloatArray = runOnce(cardCrop)

    /**
     * Embeds upright, then 180°, unless [skipHalfTurn] says the upright cosine is already enough.
     * [skipHalfTurn] sees the 0° vector and returns true to skip the second inference.
     */
    fun embedWithRotation(
        cardCrop: Bitmap,
        skipHalfTurn: (FloatArray) -> Boolean = { false },
    ): RotationEmbed {
        val started = System.nanoTime()
        val upright = runOnce(cardCrop)
        if (skipHalfTurn(upright)) {
            val ms = elapsedMs(started)
            ScanDebug.d("embed: rotationSkipped=yes embedMs=$ms")
            return RotationEmbed(listOf(upright), rotationSkipped = true, embedMs = ms)
        }
        val rotated = Bitmap.createBitmap(
            cardCrop,
            0,
            0,
            cardCrop.width,
            cardCrop.height,
            android.graphics.Matrix().apply { postRotate(180f) },
            true,
        )
        val vectors = try {
            listOf(upright, runOnce(rotated))
        } finally {
            if (rotated !== cardCrop) rotated.recycle()
        }
        val ms = elapsedMs(started)
        ScanDebug.d("embed: rotationSkipped=no embedMs=$ms")
        return RotationEmbed(vectors, rotationSkipped = false, embedMs = ms)
    }

    private fun openSession(modelFile: File): Pair<OrtSession, String> {
        val path = modelFile.absolutePath
        val sdk = android.os.Build.VERSION.SDK_INT
        val emulator = OnnxProviders.emulator(
            android.os.Build.FINGERPRINT.orEmpty(),
            android.os.Build.HARDWARE.orEmpty(),
            android.os.Build.MODEL.orEmpty(),
            android.os.Build.PRODUCT.orEmpty(),
        )
        val nnapi = OnnxProviders.nnapiIsSafe(sdk, emulator)
        if (!nnapi) {
            ScanDebug.d("onnx: nnapi off sdk=$sdk emulator=$emulator")
        }
        val order = buildList {
            if (nnapi) add("nnapi+xnnpack")
            add("xnnpack")
            add("cpu")
        }
        var error: Throwable? = null
        for (name in order) {
            try {
                val session = OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(2)
                    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    when (name) {
                        "nnapi+xnnpack" -> {
                            // XNNPACK first so it keeps the ops it can run in float32.
                            // NNAPI gets only the leftovers, with no FP16 and no CPU_DISABLED.
                            options.addXnnpack(XNNPACK_OPTIONS)
                            options.addNnapi()
                        }
                        "xnnpack" -> options.addXnnpack(XNNPACK_OPTIONS)
                    }
                    env.createSession(path, options)
                }
                return session to name
            } catch (t: Throwable) {
                error = t
                ScanDebug.w("onnx: provider $name failed", t)
            }
        }
        throw error ?: IllegalStateException("ONNX session failed")
    }

    private fun runOnce(bitmap: Bitmap): FloatArray {
        val input = preprocess(bitmap)
        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val buffer = FloatBuffer.allocate(input.size)
        buffer.put(input)
        buffer.rewind()
        OnnxTensor.createTensor(env, buffer, shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val raw = result[0].value
                val vector = when (raw) {
                    is Array<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        (raw[0] as FloatArray).copyOf()
                    }
                    is FloatArray -> raw.copyOf()
                    else -> error("Unexpected ONNX output: ${raw?.javaClass}")
                }
                return EmbeddingCatalog.l2Normalize(vector)
            }
        }
    }

    private fun preprocess(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        try {
            val out = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            val plane = INPUT_SIZE * INPUT_SIZE
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = Color.red(c) / 255f
                val g = Color.green(c) / 255f
                val b = Color.blue(c) / 255f
                out[i] = (r - MEAN_R) / STD_R
                out[plane + i] = (g - MEAN_G) / STD_G
                out[2 * plane + i] = (b - MEAN_B) / STD_B
            }
            return out
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val INPUT_SIZE = 448
        private val XNNPACK_OPTIONS = mapOf("intra_op_num_threads" to "2")
        private const val MEAN_R = 0.485f
        private const val MEAN_G = 0.456f
        private const val MEAN_B = 0.406f
        private const val STD_R = 0.229f
        private const val STD_G = 0.224f
        private const val STD_B = 0.225f

        private fun elapsedMs(startedNs: Long): Long =
            (System.nanoTime() - startedNs) / 1_000_000L
    }
}

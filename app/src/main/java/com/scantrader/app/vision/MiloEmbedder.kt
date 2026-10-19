package com.scantrader.app.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.nio.FloatBuffer

/**
 * Milo ONNX embedder: 448×448 ImageNet-normalised RGB → 128-d L2 unit vector.
 * Model: https://huggingface.co/HanClinto/milo (AGPL-3.0)
 */
class MiloEmbedder(
    modelFile: File,
) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        modelFile.absolutePath,
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        },
    )
    private val inputName = session.inputNames.first()

    fun embed(cardCrop: Bitmap): FloatArray {
        val upright = runOnce(cardCrop)
        // Milo is sensitive to 180° rotation — keep the stronger match candidate upstream
        // by embedding both orientations when callers request it.
        return upright
    }

    fun embedWithRotation(cardCrop: Bitmap): List<FloatArray> {
        val upright = runOnce(cardCrop)
        val rotated = Bitmap.createBitmap(
            cardCrop,
            0,
            0,
            cardCrop.width,
            cardCrop.height,
            android.graphics.Matrix().apply { postRotate(180f) },
            true,
        )
        return try {
            listOf(upright, runOnce(rotated))
        } finally {
            if (rotated !== cardCrop) rotated.recycle()
        }
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
        private const val MEAN_R = 0.485f
        private const val MEAN_G = 0.456f
        private const val MEAN_B = 0.406f
        private const val STD_R = 0.229f
        private const val STD_G = 0.224f
        private const val STD_B = 0.225f
    }
}

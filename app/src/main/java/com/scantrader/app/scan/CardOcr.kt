package com.scantrader.app.scan

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CardOcr {
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val japanese by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    private val chinese by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * @param cjk also run the Japanese and Chinese recognizers. Used for Pokémon,
     * where the name may be in those scripts while the set code stays Latin.
     */
    suspend fun recognize(bitmap: Bitmap, cjk: Boolean = false): String {
        if (!cjk) return recognizeOne(latin, bitmap)
        return coroutineScope {
            listOf(latin, japanese, chinese).map { client ->
                async { runCatching { recognizeOne(client, bitmap) }.getOrDefault("") }
            }.awaitAll()
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString("\n")
        }
    }

    private suspend fun recognizeOne(client: TextRecognizer, bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            client.process(image)
                .addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result.text)
                }
                .addOnFailureListener { error ->
                    if (cont.isActive) cont.resumeWithException(error)
                }
        }
}

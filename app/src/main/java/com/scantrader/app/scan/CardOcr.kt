package com.scantrader.app.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
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

/**
 * Latin OCR already shows a title line plus a set code and a collector fraction.
 * The Japanese and Chinese recognizers are extra inferences; a number with no title
 * does not count. The title has to be one of the first lines, not a rules sentence.
 */
object CjkSkip {
    private val COLLECTOR = Regex("""\b\d{1,3}\s*/\s*\d{1,3}\b""")
    private val SET_CODE = Regex("""(?i)\b[a-z]{1,5}\d{1,3}[a-z]{0,3}\b""")
    private val RULES_WORD = Regex(
        """(?i)\b(?:draw|damage|search|attach|discard|energy|switch|knock|heal|your|opponent|if|when)\b""",
    )

    fun latinCoversTitleSetAndNumber(text: String): Boolean {
        if (!COLLECTOR.containsMatchIn(text) || !SET_CODE.containsMatchIn(text)) return false
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.take(2).any { looksLikeTitle(it) }
    }

    private fun looksLikeTitle(line: String): Boolean {
        if (line.length !in 4..40) return false
        if (line.any { it.isDigit() }) return false
        if (RULES_WORD.containsMatchIn(line)) return false
        val words = line.split(Regex("""\s+""")).filter { word -> word.any { it.isLetter() } }
        if (words.isEmpty() || words.size > 6) return false
        if (words.all { it.length < 4 }) return false
        if (words.size == 1 && words[0].length < 5) return false
        return true
    }
}

/**
 * Releases a bitmap only after every ML Kit task that reads it has finished.
 * A cancelled coroutine returns at once while ML Kit keeps reading the bitmap;
 * recycling it then logs "getConfig() on a recycle()'d bitmap". [close] marks
 * the caller done; [release] runs once, when closed and nothing is in flight.
 */
class OcrBitmapLease(private val release: () -> Unit) {
    private var inFlight = 0
    private var closed = false
    private var released = false

    @Synchronized
    fun begin() {
        inFlight++
    }

    fun end() {
        if (markEnd()) release()
    }

    fun close() {
        if (markClosed()) release()
    }

    @Synchronized
    private fun markEnd(): Boolean {
        if (inFlight > 0) inFlight--
        return shouldRelease()
    }

    @Synchronized
    private fun markClosed(): Boolean {
        closed = true
        return shouldRelease()
    }

    private fun shouldRelease(): Boolean {
        if (!closed || inFlight > 0 || released) return false
        released = true
        return true
    }
}

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
     * @param enhance upscale + contrast — useful for tiny vintage `32/102` on yellow borders.
     */
    suspend fun recognize(
        bitmap: Bitmap,
        cjk: Boolean = false,
        enhance: Boolean = false,
        enhanceScale: Float = 2.5f,
    ): String {
        val input = if (enhance) enhanceForOcr(bitmap, enhanceScale) else bitmap
        // The enhanced copy is ours: free it once ML Kit is done with it, even on cancel.
        val lease = if (input !== bitmap) OcrBitmapLease { input.recycle() } else null
        return try {
            if (!cjk) {
                recognizeOne(latin, input, lease)
            } else {
                val latinText = recognizeOne(latin, input, lease)
                if (CjkSkip.latinCoversTitleSetAndNumber(latinText)) {
                    ScanDebug.d("ocr: cjk skipped (latin title+set+number)")
                    latinText
                } else {
                    val extras = coroutineScope {
                        listOf(japanese, chinese).map { client ->
                            async { runCatching { recognizeOne(client, input, lease) }.getOrDefault("") }
                        }.awaitAll()
                    }
                    (listOf(latinText) + extras)
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString("\n")
                }
            }
        } finally {
            lease?.close()
        }
    }

    /**
     * Japanese and Chinese recognizers only (Magic script check). Both run together.
     * With [recycleWhenDone] the bitmap is recycled after both ML Kit tasks finish,
     * also when the caller is cancelled first.
     */
    suspend fun recognizeCjk(bitmap: Bitmap, recycleWhenDone: Boolean = false): String {
        val lease = if (recycleWhenDone) OcrBitmapLease { bitmap.recycle() } else null
        return try {
            coroutineScope {
                listOf(japanese, chinese).map { client ->
                    async { runCatching { recognizeOne(client, bitmap, lease) }.getOrDefault("") }
                }.awaitAll()
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString("\n")
            }
        } finally {
            lease?.close()
        }
    }

    private suspend fun recognizeOne(
        client: TextRecognizer,
        bitmap: Bitmap,
        lease: OcrBitmapLease? = null,
    ): String =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            lease?.begin()
            val task = try {
                client.process(image)
            } catch (t: Throwable) {
                lease?.end()
                throw t
            }
            task
                .addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result.text)
                }
                .addOnFailureListener { error ->
                    if (cont.isActive) cont.resumeWithException(error)
                }
                .addOnCompleteListener { lease?.end() }
        }

    /** Upscale + mild contrast for small collector numbers. Default 2.5×; the One Piece corner uses 4×. */
    fun enhanceForOcr(source: Bitmap, scale: Float = 2.5f): Bitmap {
        val factor = scale.coerceIn(1f, 4f)
        val w = (source.width * factor).toInt().coerceAtLeast(1)
        val h = (source.height * factor).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, w, h, true)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val matrix = ColorMatrix(
            floatArrayOf(
                1.35f, 0f, 0f, 0f, -20f,
                0f, 1.35f, 0f, 0f, -20f,
                0f, 0f, 1.35f, 0f, -20f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        if (scaled !== source) scaled.recycle()
        return out
    }
}

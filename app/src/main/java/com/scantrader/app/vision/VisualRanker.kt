package com.scantrader.app.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.scantrader.app.data.model.IdentifiedCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads catalog art and scores each candidate against the scan fingerprint.
 * Hybrid reverse-image: OCR proposes candidates, vision re-ranks them on-device.
 */
class VisualRanker(
    private val http: OkHttpClient,
) {
    suspend fun rank(
        query: ImageFingerprint.Result,
        candidates: List<IdentifiedCard>,
    ): List<IdentifiedCard> = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext emptyList()
        coroutineScope {
            val gate = Semaphore(4)
            candidates.map { card ->
                async {
                    gate.withPermit {
                        val visual = scoreCandidate(query, card.imageUrl)
                        card.copy(visualScore = visual)
                    }
                }
            }.awaitAll()
        }
    }

    private fun scoreCandidate(query: ImageFingerprint.Result, imageUrl: String?): Float? {
        if (imageUrl.isNullOrBlank()) return null
        val bytes = download(imageUrl) ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            val reference = ImageFingerprint.fromReference(bitmap)
            ImageFingerprint.similarity(query, reference)
        } finally {
            bitmap.recycle()
        }
    }

    private fun download(url: String): ByteArray? {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "image/*")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.bytes()
            }
        }.getOrNull()
    }
}

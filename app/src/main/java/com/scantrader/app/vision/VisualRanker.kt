package com.scantrader.app.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.scantrader.app.data.catalog.PokemonSets
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.scan.OcrParser
import com.scantrader.app.scan.ScanDebug
import com.scantrader.app.scan.ShotClock
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
 * Skip the art downloads when one catalog row already matches the OCR title,
 * the set, and the collector number. [MatchFusion.ocrAgreement] is 1 when those
 * three all hit. A collector number with no title also scores 1 (the missing
 * title is not in the weight), so the title has to be present on its own.
 * Two rows that both agree stay on the slow path: the images are what separate them.
 */
object VisualRankerSkip {
    const val MIN_AGREEMENT = 0.99f
    const val MIN_TITLE_CHARS = 3

    fun skip(parsed: ParsedCardText, candidates: List<IdentifiedCard>): Boolean {
        val title = parsed.nameGuess?.trim().orEmpty()
        if (title.length < MIN_TITLE_CHARS) return false
        if (parsed.collectorNumber.isNullOrBlank()) return false
        if (parsed.setCode.isNullOrBlank()) return false
        if (candidates.isEmpty()) return false
        val strong = candidates.count { card ->
            MatchFusion.ocrAgreement(card, parsed) + 1e-4f >= MIN_AGREEMENT &&
                setAgrees(card, parsed.setCode) &&
                numberAgrees(card, parsed.collectorNumber)
        }
        return strong == 1
    }

    /**
     * Set codes longer than five characters never enter [OcrParser.setCodeCandidates],
     * so agreement can read 1 without the set actually matching. Check it here.
     */
    private fun setAgrees(card: IdentifiedCard, raw: String?): Boolean {
        val set = raw?.trim()?.lowercase().orEmpty()
        if (set.isEmpty()) return false
        val cardSet = card.setCode.trim().lowercase()
        if (card.game == Game.POKEMON) {
            return cardSet == set || PokemonSets.sameSet(set, card.setCode)
        }
        return cardSet == set || cardSet.startsWith(set) || set.startsWith(cardSet)
    }

    private fun numberAgrees(card: IdentifiedCard, raw: String?): Boolean {
        val numbers = OcrParser.collectorCandidates(raw)
        if (numbers.isEmpty()) return false
        val cn = card.collectorNumber.trimStart('0').ifBlank { card.collectorNumber }
        return numbers.any {
            card.collectorNumber.equals(it, ignoreCase = true) ||
                cn.equals(it.trimStart('0').ifBlank { it }, ignoreCase = true)
        }
    }
}

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
        parsed: ParsedCardText? = null,
        shot: ShotClock? = null,
    ): List<IdentifiedCard> = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) {
            shot?.rankerSkipped = true
            return@withContext emptyList()
        }
        if (parsed != null && VisualRankerSkip.skip(parsed, candidates)) {
            shot?.rankerSkipped = true
            ScanDebug.d("visualRanker skipped (title+set+number agree)")
            return@withContext candidates
        }
        shot?.rankerSkipped = false
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

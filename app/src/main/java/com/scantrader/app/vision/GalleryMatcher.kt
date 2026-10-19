package com.scantrader.app.vision

import android.graphics.Bitmap
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.network.scryfall.ScryfallApi
import com.scantrader.app.network.scryfall.ScryfallCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Full-gallery reverse-image for MTG via Milo embeddings + Scryfall ID lookup.
 */
class GalleryMatcher(
    private val pack: NeuralPackManager,
    private val scryfall: ScryfallApi,
) {
    suspend fun matchMtg(
        cardCrop: Bitmap,
        parsed: ParsedCardText,
        topK: Int = 8,
    ): List<IdentifiedCard> = withContext(Dispatchers.Default) {
        val t0 = System.currentTimeMillis()
        if (!pack.ensureLoaded()) {
            com.scantrader.app.scan.ScanDebug.w("gallery: neural pack ensureLoaded=false")
            return@withContext emptyList()
        }
        val embedder = pack.embedderOrNull()
        val catalog = pack.catalogOrNull()
        if (embedder == null || catalog == null) {
            com.scantrader.app.scan.ScanDebug.w("gallery: embedder/catalog null after load")
            return@withContext emptyList()
        }

        val embeddings = embedder.embedWithRotation(cardCrop)
        val scoreById = linkedMapOf<String, Float>()
        for (emb in embeddings) {
            for (hit in catalog.search(emb, topK = topK)) {
                val prev = scoreById[hit.cardId]
                if (prev == null || hit.score > prev) {
                    scoreById[hit.cardId] = hit.score
                }
            }
        }
        val ranked = scoreById.entries
            .sortedByDescending { it.value }
            .take(topK)
            .filter { it.value >= MIN_SCORE }

        com.scantrader.app.scan.ScanDebug.d(
            "gallery: catalogSize=${catalog.size} rawHits=${scoreById.size} " +
                "aboveMin=${ranked.size} top=${ranked.firstOrNull()?.let { "${it.key}=${it.value}" }} " +
                "embedMs=${System.currentTimeMillis() - t0}",
        )

        if (ranked.isEmpty()) return@withContext emptyList()

        coroutineScope {
            ranked.map { (id, score) ->
                async(Dispatchers.IO) {
                    val card = runCatching { scryfall.cardById(id) }
                        .onFailure {
                            com.scantrader.app.scan.ScanDebug.w("gallery: scryfall $id failed", it)
                        }
                        .getOrNull() ?: return@async null
                    card.toIdentified(
                        visualScore = score.coerceIn(0f, 1f),
                        ocrScore = MatchFusion.ocrAgreement(
                            IdentifiedCard(
                                game = Game.MTG,
                                name = card.name,
                                setCode = card.set,
                                setName = card.setName.orEmpty(),
                                collectorNumber = card.collectorNumber,
                                scryfallId = card.id,
                            ),
                            parsed,
                        ),
                    )
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun ScryfallCard.toIdentified(visualScore: Float, ocrScore: Float): IdentifiedCard =
        IdentifiedCard(
            game = Game.MTG,
            name = name,
            setCode = set,
            setName = setName.orEmpty(),
            collectorNumber = collectorNumber,
            scryfallId = id,
            tcgplayerId = tcgplayerId?.toString(),
            imageUrl = bestImageUrl(),
            priceEur = prices?.eur?.takeIf { it.isNotBlank() },
            priceEurFoil = prices?.eurFoil?.takeIf { it.isNotBlank() },
            ocrScore = ocrScore,
            visualScore = visualScore,
        )

    companion object {
        /** Below this cosine, treat as non-match noise. */
        const val MIN_SCORE = 0.55f
    }
}

/**
 * Merge OCR candidates with gallery hits: same Scryfall id keeps the best scores.
 */
fun mergeHybridCandidates(
    ocrHits: List<IdentifiedCard>,
    galleryHits: List<IdentifiedCard>,
): List<IdentifiedCard> {
    if (galleryHits.isEmpty()) return ocrHits
    if (ocrHits.isEmpty()) return galleryHits
    val byKey = LinkedHashMap<String, IdentifiedCard>()
    fun keyOf(card: IdentifiedCard): String =
        card.scryfallId ?: "${card.setCode}|${card.collectorNumber}|${card.name}"

    fun putBest(card: IdentifiedCard) {
        val key = keyOf(card)
        val existing = byKey[key]
        if (existing == null) {
            byKey[key] = card
            return
        }
        byKey[key] = existing.copy(
            visualScore = maxOf(existing.visualScore ?: 0f, card.visualScore ?: 0f)
                .takeIf { (existing.visualScore != null || card.visualScore != null) },
            ocrScore = maxOf(existing.ocrScore ?: 0f, card.ocrScore ?: 0f)
                .takeIf { (existing.ocrScore != null || card.ocrScore != null) },
            imageUrl = existing.imageUrl ?: card.imageUrl,
            priceEur = existing.priceEur ?: card.priceEur,
            priceEurFoil = existing.priceEurFoil ?: card.priceEurFoil,
        )
    }
    // Gallery first so strong visual IDs are present even when OCR missed them.
    galleryHits.forEach(::putBest)
    ocrHits.forEach(::putBest)
    return byKey.values.toList()
}

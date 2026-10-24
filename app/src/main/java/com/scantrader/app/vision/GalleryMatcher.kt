package com.scantrader.app.vision

import android.graphics.Bitmap
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.network.scryfall.ScryfallCard
import com.scantrader.app.network.scryfall.ScryfallClient
import com.scantrader.app.network.tcgdex.TcgdexApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Full-gallery reverse-image via Milo embeddings + catalog ID lookup (Scryfall / TCGdex).
 */
class GalleryMatcher(
    private val pack: NeuralPackManager,
    private val scryfall: ScryfallClient,
    private val tcgdex: TcgdexApi,
) {
    suspend fun matchMtg(
        cardCrop: Bitmap,
        parsed: ParsedCardText,
        topK: Int = 8,
        printedLang: String? = null,
    ): List<IdentifiedCard> = matchGame(Game.MTG, cardCrop, parsed, topK, printedLang)

    suspend fun matchPokemon(
        cardCrop: Bitmap,
        parsed: ParsedCardText,
        topK: Int = 8,
    ): List<IdentifiedCard> = matchGame(Game.POKEMON, cardCrop, parsed, topK)

    private suspend fun matchGame(
        game: Game,
        cardCrop: Bitmap,
        parsed: ParsedCardText,
        topK: Int,
        printedLang: String? = null,
    ): List<IdentifiedCard> = withContext(Dispatchers.Default) {
        val t0 = System.currentTimeMillis()
        if (!pack.ensureLoaded(game)) {
            com.scantrader.app.scan.ScanDebug.w("gallery: neural pack ensureLoaded=false game=$game")
            return@withContext emptyList()
        }
        val embedder = pack.embedderOrNull()
        val catalog = pack.catalogOrNull(game)
        if (embedder == null || catalog == null) {
            com.scantrader.app.scan.ScanDebug.w("gallery: embedder/catalog null after load game=$game")
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
            "gallery: game=$game catalogSize=${catalog.size} rawHits=${scoreById.size} " +
                "aboveMin=${ranked.size} top=${ranked.firstOrNull()?.let { "${it.key}=${it.value}" }} " +
                "embedMs=${System.currentTimeMillis() - t0}",
        )

        if (ranked.isEmpty()) return@withContext emptyList()

        coroutineScope {
            ranked.map { (id, score) ->
                async(Dispatchers.IO) {
                    when (game) {
                        Game.MTG -> hydrateMtg(id, score, parsed, printedLang)
                        Game.POKEMON -> hydratePokemon(id, score, parsed)
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun hydrateMtg(
        id: String,
        score: Float,
        parsed: ParsedCardText,
        printedLang: String?,
    ): IdentifiedCard? {
        val card = runCatching { scryfall.cardById(id) }
            .onFailure {
                com.scantrader.app.scan.ScanDebug.w("gallery: scryfall $id failed", it)
            }
            .getOrNull() ?: return null
        val printed = localizedTitle(card, parsed, printedLang)
        val displayName = if (
            printed != null && MatchFusion.titleMatches(printed, parsed.nameGuess)
        ) {
            printed
        } else {
            card.name
        }
        val stub = IdentifiedCard(
            game = Game.MTG,
            name = displayName,
            setCode = card.set,
            setName = card.setName.orEmpty(),
            collectorNumber = card.collectorNumber,
            scryfallId = card.id,
            printedName = printed,
        )
        return card.toIdentified(
            visualScore = score.coerceIn(0f, 1f),
            ocrScore = MatchFusion.ocrAgreement(stub, parsed),
        ).copy(name = displayName, printedName = printed)
    }

    /**
     * English gallery ids have no `printed_name`. Fetch the scan language
     * (or Italian, the same fallback as the catalog) once and cache it.
     */
    private suspend fun localizedTitle(
        card: ScryfallCard,
        parsed: ParsedCardText,
        printedLang: String?,
    ): String? {
        if (MatchFusion.titleMatches(card.name, parsed.nameGuess)) return null
        val lang = when {
            printedLang.isNullOrBlank() -> null
            !printedLang.equals("en", ignoreCase = true) -> printedLang
            else -> "it"
        } ?: return null
        if (card.set.isBlank() || card.collectorNumber.isBlank()) return null
        val localized = runCatching {
            scryfall.cardBySetNumberLang(card.set, card.collectorNumber, lang)
        }.onFailure {
            com.scantrader.app.scan.ScanDebug.w(
                "gallery: printed $lang ${card.set}/${card.collectorNumber} failed",
                it,
            )
        }.getOrNull()
        return localized?.printedName?.takeIf { it.isNotBlank() }
    }

    private suspend fun hydratePokemon(
        id: String,
        score: Float,
        parsed: ParsedCardText,
    ): IdentifiedCard? {
        val card = runCatching { tcgdex.card("en", id) }
            .onFailure {
                com.scantrader.app.scan.ScanDebug.w("gallery: tcgdex $id failed", it)
            }
            .getOrNull() ?: return null
        val setId = card.set?.id?.takeIf { it.isNotBlank() } ?: id.substringBefore('-')
        val stub = IdentifiedCard(
            game = Game.POKEMON,
            name = card.name,
            setCode = setId,
            setName = card.set?.name.orEmpty(),
            collectorNumber = card.localId.ifBlank { id.substringAfter('-', "") },
            tcgdexId = card.id,
            imageUrl = card.image?.let { "$it/low.webp" },
        )
        return stub.copy(
            visualScore = score.coerceIn(0f, 1f),
            ocrScore = MatchFusion.ocrAgreement(stub, parsed),
            priceEur = formatEur(card.pricing?.cardmarket?.avg ?: card.pricing?.cardmarket?.trend),
            priceEurFoil = formatEur(
                card.pricing?.cardmarket?.avgHolo ?: card.pricing?.cardmarket?.trendHolo,
            ),
        )
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

    private fun formatEur(value: Double?): String? {
        if (value == null || value <= 0.0) return null
        return String.format(java.util.Locale.US, "%.2f", value)
    }

    companion object {
        /** Below this cosine, treat as non-match noise. */
        const val MIN_SCORE = 0.55f
    }
}

/**
 * Merge OCR candidates with gallery hits: same catalog id keeps the best scores.
 */
fun mergeHybridCandidates(
    ocrHits: List<IdentifiedCard>,
    galleryHits: List<IdentifiedCard>,
): List<IdentifiedCard> {
    if (galleryHits.isEmpty()) return ocrHits
    if (ocrHits.isEmpty()) return galleryHits
    val byKey = LinkedHashMap<String, IdentifiedCard>()
    fun keyOf(card: IdentifiedCard): String =
        card.scryfallId
            ?: card.tcgdexId
            ?: "${card.setCode}|${card.collectorNumber}|${card.name}"

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
            printedName = existing.printedName ?: card.printedName,
            tcgdexId = existing.tcgdexId ?: card.tcgdexId,
            scryfallId = existing.scryfallId ?: card.scryfallId,
        )
    }
    // Gallery first so strong visual IDs are present even when OCR missed them.
    galleryHits.forEach(::putBest)
    ocrHits.forEach(::putBest)
    return byKey.values.toList()
}

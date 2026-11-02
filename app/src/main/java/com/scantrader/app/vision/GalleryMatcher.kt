package com.scantrader.app.vision

import android.graphics.Bitmap
import com.scantrader.app.data.model.Game
import java.util.Locale
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.scan.ShotClock
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
        shot: ShotClock? = null,
    ): List<IdentifiedCard> = matchGame(Game.MTG, cardCrop, parsed, topK, printedLang, shot)

    /** One Piece, Dragon Ball, Yu-Gi-Oh!, Union Arena: l'id della galleria porta gia' i dati del blueprint CardTrader. */
    suspend fun matchCt(
        game: Game,
        cardCrop: Bitmap,
        parsed: ParsedCardText,
        topK: Int = 8,
        shot: ShotClock? = null,
    ): List<IdentifiedCard> = matchGame(game, cardCrop, parsed, topK, shot = shot)

    suspend fun matchPokemon(
        cardCrop: Bitmap,
        parsed: ParsedCardText,
        topK: Int = 8,
        shot: ShotClock? = null,
    ): List<IdentifiedCard> = matchGame(Game.POKEMON, cardCrop, parsed, topK, shot = shot)

    /**
     * Embeds the guide quad while OCR is still running. Hydration waits until
     * the parsed title exists. Null guide size returns an empty rank, not a square crop.
     */
    suspend fun rankFromGuide(
        game: Game,
        source: Bitmap,
        viewWidth: Float,
        viewHeight: Float,
        shot: ShotClock? = null,
        topK: Int = 8,
    ): List<Pair<String, Float>> {
        val card = com.scantrader.app.scan.CardDewarp.cardFromGuide(
            source,
            viewWidth,
            viewHeight,
        ) ?: return emptyList()
        return try {
            rankCrop(game, card, topK, shot)
        } finally {
            if (card !== source) card.recycle()
        }
    }

    suspend fun cardsFromRank(
        game: Game,
        ranked: List<Pair<String, Float>>,
        parsed: ParsedCardText,
        printedLang: String? = null,
    ): List<IdentifiedCard> {
        if (ranked.isEmpty()) return emptyList()
        return coroutineScope {
            ranked.map { (id, score) ->
                async(Dispatchers.IO) {
                    when (game) {
                        Game.MTG -> hydrateMtg(id, score, parsed, printedLang)
                        Game.POKEMON -> hydratePokemon(id, score, parsed)
                        Game.ONE_PIECE, Game.DRAGON_BALL, Game.YUGIOH, Game.UNION_ARENA ->
                            ctFromId(game, id, score, parsed)
                        Game.RIFTBOUND, Game.OTHER -> null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun matchGame(
        game: Game,
        cardCrop: Bitmap,
        parsed: ParsedCardText,
        topK: Int,
        printedLang: String? = null,
        shot: ShotClock? = null,
    ): List<IdentifiedCard> {
        val ranked = rankCrop(game, cardCrop, topK, shot)
        return cardsFromRank(game, ranked, parsed, printedLang)
    }

    private suspend fun rankCrop(
        game: Game,
        cardCrop: Bitmap,
        topK: Int,
        shot: ShotClock?,
    ): List<Pair<String, Float>> = withContext(Dispatchers.Default) {
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

        val uprightHits = ArrayList<EmbeddingCatalog.Hit>(topK)
        val pass = embedder.embedWithRotation(cardCrop) { vector ->
            uprightHits.clear()
            uprightHits.addAll(catalog.search(vector, topK = topK))
            RotationSkip.skip(uprightHits.map { it.score })
        }
        shot?.embedMs = pass.embedMs
        shot?.rotationSkipped = pass.rotationSkipped
        val scoreById = linkedMapOf<String, Float>()
        fun absorb(hits: List<EmbeddingCatalog.Hit>) {
            for (hit in hits) {
                val prev = scoreById[hit.cardId]
                if (prev == null || hit.score > prev) {
                    scoreById[hit.cardId] = hit.score
                }
            }
        }
        absorb(uprightHits)
        if (!pass.rotationSkipped) {
            val rotated = pass.vectors.getOrNull(1)
            if (rotated != null) absorb(catalog.search(rotated, topK = topK))
        }
        val best = scoreById.maxByOrNull { it.value }
        val ranked = scoreById.entries
            .sortedByDescending { it.value }
            .take(topK)
            .filter { it.value >= MIN_SCORE }
        val aspect = if (cardCrop.height == 0) 0f else cardCrop.width.toFloat() / cardCrop.height

        com.scantrader.app.scan.ScanDebug.d(
            "gallery: game=$game catalogSize=${catalog.size} rawHits=${scoreById.size} " +
                "best=${best?.let { "${it.key}=${"%.4f".format(Locale.US, it.value)}" } ?: "none"} " +
                "aboveMin=${ranked.size} top=${ranked.firstOrNull()?.let { "${it.key}=${it.value}" }} " +
                "bitmap=${cardCrop.width}x${cardCrop.height} aspect=${"%.3f".format(Locale.US, aspect)} " +
                "provider=${embedder.providerName} " +
                "embedMs=${pass.embedMs} rotationSkipped=${if (pass.rotationSkipped) "yes" else "no"} " +
                "totalMs=${System.currentTimeMillis() - t0}",
        )
        ranked.map { it.key to it.value }
    }

    /**
     * Id scritto da `costruisci.py`: bp, espansione (id, codice, nome), numero, nome, versione, rarita', immagine,
     * separati da \u001f. Nome e codice come [com.scantrader.app.data.catalog.CtGameCatalog], cosi' la stessa carta
     * trovata dal codice e dall'immagine diventa una riga sola.
     */
    private fun ctFromId(game: Game, id: String, score: Float, parsed: ParsedCardText): IdentifiedCard? {
        val f = id.split('\u001f')
        if (f.size < 9) return null
        val bp = f[0].toIntOrNull() ?: return null
        val number = f[4]
        val codeOk = parsed.collectorNumber?.let {
            com.scantrader.app.data.catalog.CtGameCatalog.base(it) == com.scantrader.app.data.catalog.CtGameCatalog.base(number)
        } == true
        val name = f[5].trim()
        return IdentifiedCard(
            game = game,
            name = name + (f[6].takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""),
            setCode = f[2].lowercase(java.util.Locale.ROOT),
            setName = f[3],
            collectorNumber = number,
            imageUrl = f[8].let { if (it.startsWith("http")) it else "https://www.cardtrader.com$it" },
            visualScore = score.coerceIn(0f, 1f),
            ocrScore = if (codeOk) com.scantrader.app.data.catalog.CtGameCatalog.CHOOSE_SCORE else 0f,
            catalogName = name,
            ctBlueprintId = bp,
            rarity = f[7].takeIf { it.isNotBlank() },
            catalogLang = "en",
        )
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
            printed != null && MatchFusion.titleMatches(printed.name, parsed.nameGuess)
        ) {
            printed.name
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
            printedName = printed?.name,
            printedLang = printed?.lang,
        )
        val identified = card.toIdentified(
            visualScore = score.coerceIn(0f, 1f),
            ocrScore = MatchFusion.ocrAgreement(stub, parsed),
        ).copy(
            name = displayName,
            catalogName = card.name,
            printedName = printed?.name,
            printedLang = printed?.lang,
        )
        val lang = card.lang?.trim()?.lowercase()
        if (lang.isNullOrEmpty() || lang == "en") return identified
        val english = runCatching {
            scryfall.cardBySetNumberLang(card.set, card.collectorNumber, "en")
        }.getOrNull() ?: return identified
        return identified.copy(
            catalogName = english.name.takeIf { it.isNotBlank() } ?: identified.catalogName,
            catalogScryfallId = english.id.takeIf { it.isNotBlank() && it != card.id },
        )
    }

    /**
     * English gallery ids have no `printed_name`. Fetch the scan language
     * (or Italian, the same fallback as the catalog) once and cache it.
     */
    private suspend fun localizedTitle(
        card: ScryfallCard,
        parsed: ParsedCardText,
        printedLang: String?,
    ): PrintedTitle? {
        val englishFaces = listOf(card.name) + card.cardFaces.orEmpty().mapNotNull { it.name }
        if (englishFaces.any { MatchFusion.titleMatches(it, parsed.nameGuess) }) return null
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
        // Adventure cards leave top-level printed_name empty and put it on each face.
        val title = localized?.localizedPrintedName() ?: return null
        return PrintedTitle(title, lang)
    }

    private data class PrintedTitle(val name: String, val lang: String)

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
            catalogName = card.name.takeIf { it.isNotBlank() },
            catalogLang = "en",
            imageUrl = card.image?.let { "$it/low.webp" },
        )
        return stub.copy(
            visualScore = score.coerceIn(0f, 1f),
            ocrScore = MatchFusion.ocrAgreement(stub, parsed),
            priceEur = formatEur(card.pricing?.cardmarket?.avg ?: card.pricing?.cardmarket?.trend),
            priceCurrency = if (
                card.pricing?.cardmarket?.avg != null || card.pricing?.cardmarket?.trend != null
            ) {
                "EUR"
            } else {
                null
            },
            priceSource = if (
                card.pricing?.cardmarket?.avg != null || card.pricing?.cardmarket?.trend != null
            ) {
                "Cardmarket"
            } else {
                null
            },
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
            catalogName = name.takeIf { it.isNotBlank() },
            tcgplayerId = tcgplayerId?.toString(),
            imageUrl = bestImageUrl(),
            priceEur = prices?.eur?.takeIf { it.isNotBlank() },
            priceEurFoil = prices?.eurFoil?.takeIf { it.isNotBlank() },
            priceUsd = prices?.usd?.takeIf { it.isNotBlank() },
            priceUsdFoil = prices?.usdFoil?.takeIf { it.isNotBlank() },
            priceCurrency = if (
                !prices?.eur.isNullOrBlank() || !prices?.eurFoil.isNullOrBlank()
            ) {
                "EUR"
            } else {
                null
            },
            priceSource = if (
                !prices?.eur.isNullOrBlank() || !prices?.eurFoil.isNullOrBlank()
            ) {
                "Scryfall"
            } else {
                null
            },
            catalogLang = lang?.trim()?.lowercase()?.takeIf { it.isNotBlank() },
            finish = com.scantrader.app.scan.CardFinish.from(finishes, frameEffects, promoTypes),
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
            priceUsd = existing.priceUsd ?: card.priceUsd,
            priceUsdFoil = existing.priceUsdFoil ?: card.priceUsdFoil,
            priceCurrency = existing.priceCurrency ?: card.priceCurrency,
            priceSource = existing.priceSource ?: card.priceSource,
            printedName = existing.printedName ?: card.printedName,
            printedLang = existing.printedLang ?: card.printedLang,
            catalogName = existing.catalogName ?: card.catalogName,
            catalogScryfallId = existing.catalogScryfallId ?: card.catalogScryfallId,
            catalogLang = existing.catalogLang ?: card.catalogLang,
            finish = existing.finish ?: card.finish,
            tcgdexId = existing.tcgdexId ?: card.tcgdexId,
            scryfallId = existing.scryfallId ?: card.scryfallId,
        )
    }
    // Gallery first so strong visual IDs are present even when OCR missed them.
    galleryHits.forEach(::putBest)
    ocrHits.forEach(::putBest)
    return byKey.values.toList()
}

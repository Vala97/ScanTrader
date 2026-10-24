package com.scantrader.app.data.catalog

import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.network.scryfall.ScryfallBusyException
import com.scantrader.app.network.scryfall.ScryfallCard
import com.scantrader.app.network.scryfall.ScryfallClient
import com.scantrader.app.network.tcgdex.TcgdexApi
import com.scantrader.app.network.tcgdex.TcgdexCard
import com.scantrader.app.network.tcgdex.TcgdexCardBrief
import com.scantrader.app.scan.OcrParser
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import retrofit2.HttpException

class CatalogLookup(
    private val scryfall: ScryfallClient,
    private val tcgdex: TcgdexApi,
) {
    suspend fun identify(game: Game, parsed: ParsedCardText, manualQuery: String? = null): List<IdentifiedCard> {
        val queryName = manualQuery?.trim()?.takeIf { it.isNotBlank() } ?: parsed.nameGuess
        return when (game) {
            Game.MTG -> lookupMtg(parsed, queryName)
            Game.POKEMON -> lookupPokemon(parsed, queryName)
        }.distinctBy { it.scryfallId ?: "${it.imageUrl}-${it.setCode}-${it.collectorNumber}-${it.name}" }
            .take(12)
    }

    private suspend fun lookupMtg(parsed: ParsedCardText, name: String?): List<IdentifiedCard> {
        if (scryfall.coolingDown()) throw ScryfallBusyException()
        val setCandidates = OcrParser.setCodeCandidates(parsed.setCode)
            .filter { code -> code.any { it.isLetter() } && code.count { it.isDigit() } < 3 }
            .take(2)
        val number = OcrParser.collectorCandidates(parsed.collectorNumber).firstOrNull()
        val searchName = OcrParser.cleanMtgNameGuess(name)
        if (searchName.isNullOrBlank() && number.isNullOrBlank()) return emptyList()

        // One set+number lookup. Stop as soon as a print is found.
        for (set in setCandidates) {
            if (number.isNullOrBlank()) break
            val card = runCatching { scryfall.cardBySetAndNumber(set, number) }.orRethrowBusy()
            if (card != null) return listOf(card.toIdentified(ocrScore = 0.98f))
        }

        // Old frame: number in the bottom band, no set code. One combined query.
        if (!searchName.isNullOrBlank() && !number.isNullOrBlank() && setCandidates.isEmpty()) {
            val byNumber = searchOnce("cn:$number (lang:it name:\"$searchName\" OR name:\"$searchName\")")
            if (byNumber.isNotEmpty()) return byNumber
        }

        if (!searchName.isNullOrBlank()) {
            val fuzzy = runCatching { scryfall.namedFuzzy(searchName) }.orRethrowBusy()
            val cnOk = number.isNullOrBlank() || collectorEquals(fuzzy?.collectorNumber, number)
            if (fuzzy != null && cnOk) {
                return listOf(fuzzy.toIdentified(ocrScore = 0.72f))
            }
            // Italian printed title when the English fuzzy name is a different card.
            val italian = searchOnce("lang:it !\"$searchName\"")
            if (italian.isNotEmpty()) return italian
        }
        return emptyList()
    }

    private suspend fun searchOnce(query: String): List<IdentifiedCard> {
        val response = runCatching { scryfall.search(query) }
            .onFailure { if (it !is HttpException && it !is com.scantrader.app.network.scryfall.ScryfallMissException) throw it }
            .getOrNull()
            ?: return emptyList()
        if (response.data.isEmpty()) return emptyList()
        val exact = query.contains("!\"")
        return response.data.take(4).map { it.toIdentified(ocrScore = if (exact) 0.88f else 0.7f) }
    }

    private fun <T> Result<T>.orRethrowBusy(): T? {
        val error = exceptionOrNull()
        if (error is ScryfallBusyException) throw error
        return getOrNull()
    }

    private fun collectorEquals(cardNumber: String?, wanted: String): Boolean {
        val left = cardNumber?.filter { it.isDigit() }?.trimStart('0').orEmpty()
        val right = wanted.filter { it.isDigit() }.trimStart('0')
        return left.isNotEmpty() && left == right
    }

    private suspend fun lookupPokemon(parsed: ParsedCardText, name: String?): List<IdentifiedCard> {
        val number = OcrParser.collectorCandidates(parsed.collectorNumber).firstOrNull()
        val noisyName = OcrParser.looksLikeAbilityOrAttackName(name)
        // When OCR grabbed an ability/attack, ignore name for search — identity is set+number.
        val searchName = name.takeUnless { noisyName }
        val hasSolidId = !number.isNullOrBlank() &&
            (!parsed.setCode.isNullOrBlank() || parsed.setCardCount != null)
        val langs = PokemonCatalog.languages(searchName, parsed.setCode)
        val cards = coroutineScope {
            langs.map { lang ->
                async { searchPokemonLanguage(lang, searchName, number, parsed, idFirst = hasSolidId || noisyName) }
            }.awaitAll().flatten()
        }
        val scored = cards
            .distinctBy { "${it.image}-${it.id}" }
            .map { card ->
                val setIds = PokemonSets.idsFor(parsed.setCode, cardLang(card)) +
                    PokemonSets.idsForCardCount(parsed.setCardCount)
                val raw = PokemonCatalog.score(
                    cardName = card.name,
                    cardNumber = card.localId,
                    setId = card.set?.id ?: card.id.substringBefore('-'),
                    printedTotal = card.set?.cardCount?.official,
                    // Noisy ability names must not tank ranking — score by id only.
                    queryName = searchName,
                    queryNumber = number,
                    querySetIds = setIds.distinct(),
                    setCardCount = parsed.setCardCount,
                )
                card.toIdentified().copy(ocrScore = (raw / 160f).coerceIn(0.02f, 1f))
            }
            .sortedByDescending { it.ocrScore ?: 0f }
        // Drop obvious junk when OCR name is a real title (avoid Malamar from "mal di testa").
        // Skip this filter when name is ability noise — id match is enough.
        val filtered = if (!noisyName && !searchName.isNullOrBlank() && searchName.length >= 4) {
            val withName = scored.filter {
                PokemonCatalog.nameMatches(it.name, searchName) || (it.ocrScore ?: 0f) >= 0.35f
            }
            withName.ifEmpty { scored }
        } else {
            scored
        }
        return filtered.take(12)
    }

    private suspend fun searchPokemonLanguage(
        lang: String,
        name: String?,
        number: String?,
        parsed: ParsedCardText,
        idFirst: Boolean = false,
    ): List<TcgdexCard> {
        val setIds = resolvePokemonSetIds(lang, parsed)
        val found = linkedMapOf<String, TcgdexCard>()

        // Prefer set+number (and card-count sets) before any name search.
        if (!number.isNullOrBlank()) {
            for (setId in setIds.take(6)) {
                for (id in PokemonCatalog.cardIds(setId, number)) {
                    val card = runCatching { tcgdex.card(lang, id) }.getOrNull() ?: continue
                    found[card.id] = card
                }
                if (found.isNotEmpty()) break
            }
            if (found.isEmpty()) {
                val localIds = listOf(
                    number,
                    number.trimStart('0').ifBlank { number }.padStart(3, '0'),
                ).distinct()
                for (localId in localIds) {
                    val briefs = runCatching { tcgdex.search(lang, localId = localId) }.getOrDefault(emptyList())
                    if (briefs.isEmpty()) continue
                    fetchBriefs(lang, pickBriefs(briefs, number, setIds), found)
                    if (found.isNotEmpty()) break
                }
            }
        }
        // Solid id hit: name is only a tie-break later via score(), skip name search.
        if (idFirst && found.isNotEmpty()) return found.values.toList()
        if (found.values.any { it.matches(name, number, setIds) }) return found.values.toList()

        if (!name.isNullOrBlank()) {
            for (query in PokemonCatalog.nameQueries(name)) {
                val briefs = runCatching { tcgdex.search(lang, name = query) }.getOrDefault(emptyList())
                fetchBriefs(lang, pickBriefs(briefs, number, setIds), found)
                if (found.values.any { it.matches(name, number, setIds) }) break
            }
        }

        if (found.values.none { it.matches(name, number, setIds) } && !number.isNullOrBlank() && found.isEmpty()) {
            val localIds = listOf(number, number.trimStart('0').ifBlank { number }.padStart(3, '0')).distinct()
            for (localId in localIds) {
                val briefs = runCatching { tcgdex.search(lang, localId = localId) }.getOrDefault(emptyList())
                if (briefs.isEmpty()) continue
                fetchBriefs(lang, pickBriefs(briefs, number, setIds), found)
                break
            }
        }
        return found.values.toList()
    }

    private fun resolvePokemonSetIds(lang: String, parsed: ParsedCardText): List<String> {
        val fromPrinted = if (lang == PokemonCatalog.EN || lang == PokemonCatalog.IT) {
            val mapped = OcrParser.setCodeCandidates(parsed.setCode)
                .flatMap { PokemonSets.recognizedIds(it) }
                .distinct()
            mapped.ifEmpty { PokemonSets.idsFor(parsed.setCode, lang) }
        } else {
            PokemonSets.idsFor(parsed.setCode, lang)
        }
        val fromCount = PokemonSets.idsForCardCount(parsed.setCardCount)
        return (fromPrinted + fromCount).distinct()
    }

    private suspend fun fetchBriefs(
        lang: String,
        briefs: List<TcgdexCardBrief>,
        into: MutableMap<String, TcgdexCard>,
    ) {
        val missing = briefs.filter { it.id.isNotBlank() && it.id !in into }.take(6)
        if (missing.isEmpty()) return
        val detailed = coroutineScope {
            missing.map { brief ->
                async {
                    runCatching { tcgdex.card(lang, brief.id) }.getOrNull()
                        ?: TcgdexCard(brief.id, brief.localId, brief.name, brief.image)
                }
            }.awaitAll()
        }
        detailed.forEach { card ->
            if (card.id.isNotBlank() && card.name.isNotBlank()) into.putIfAbsent(card.id, card)
        }
    }

    private fun pickBriefs(
        briefs: List<TcgdexCardBrief>,
        number: String?,
        setIds: List<String>,
    ): List<TcgdexCardBrief> {
        if (briefs.isEmpty()) return emptyList()
        fun setHit(brief: TcgdexCardBrief) =
            setIds.isEmpty() || setIds.any { brief.id.startsWith("$it-", ignoreCase = true) }
        fun numberHit(brief: TcgdexCardBrief) =
            number.isNullOrBlank() || PokemonCatalog.numberMatches(number, brief.localId)
        val strict = briefs.filter { setHit(it) && numberHit(it) }
        if (strict.isNotEmpty()) return strict
        val byNumber = briefs.filter { numberHit(it) }
        if (byNumber.isNotEmpty()) return byNumber
        return briefs.take(4)
    }

    private fun TcgdexCard.matches(name: String?, number: String?, setIds: List<String>): Boolean {
        val setId = set?.id ?: id.substringBefore('-')
        val setOk = setIds.isEmpty() || setIds.any { it.equals(setId, ignoreCase = true) }
        val numberOk = number.isNullOrBlank() || PokemonCatalog.numberMatches(number, localId)
        val nameOk = name.isNullOrBlank() || PokemonCatalog.nameMatches(this.name, name)
        return setOk && numberOk && nameOk && (setIds.isNotEmpty() || !number.isNullOrBlank() || !name.isNullOrBlank())
    }

    /** Image host path is `/en/`, `/ja/`, `/zh-cn/` or `/zh-tw/`. */
    private fun cardLang(card: TcgdexCard): String {
        val path = card.image.orEmpty()
        return listOf("zh-cn", "zh-tw", "ja", "it", "en").firstOrNull { path.contains("/$it/") } ?: "en"
    }

    private fun ScryfallCard.toIdentified(ocrScore: Float? = null): IdentifiedCard =
        IdentifiedCard(
            game = Game.MTG,
            // Prefer printed (IT) name when Scryfall returns it — matches OCR titles.
            name = printedName?.takeIf { it.isNotBlank() } ?: name,
            setCode = set,
            setName = setName.orEmpty(),
            collectorNumber = collectorNumber,
            scryfallId = id,
            tcgplayerId = tcgplayerId?.toString(),
            imageUrl = bestImageUrl(),
            priceEur = prices?.eur?.takeIf { it.isNotBlank() },
            priceEurFoil = prices?.eurFoil?.takeIf { it.isNotBlank() },
            ocrScore = ocrScore,
        )

    private fun TcgdexCard.toIdentified(): IdentifiedCard {
        val cm = pricing?.cardmarket
        val setId = set?.id?.takeIf { it.isNotBlank() } ?: id.substringBefore('-')
        return IdentifiedCard(
            game = Game.POKEMON,
            name = name,
            setCode = setId,
            setName = set?.name.orEmpty(),
            collectorNumber = localId.ifBlank { id.substringAfter('-', "") },
            tcgdexId = id,
            imageUrl = image?.let { "$it/low.webp" },
            priceEur = formatEur(cm?.avg ?: cm?.trend),
            priceEurFoil = formatEur(cm?.avgHolo ?: cm?.trendHolo),
        )
    }

    private fun formatEur(value: Double?): String? {
        if (value == null || value <= 0.0) return null
        return String.format(Locale.US, "%.2f", value)
    }
}

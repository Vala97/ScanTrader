package com.scantrader.app.data.catalog

import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.network.scryfall.ScryfallApi
import com.scantrader.app.network.scryfall.ScryfallCard
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
    private val scryfall: ScryfallApi,
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
        val results = mutableListOf<IdentifiedCard>()
        val setCandidates = OcrParser.setCodeCandidates(parsed.setCode)
        val numberCandidates = OcrParser.collectorCandidates(parsed.collectorNumber)

        // Strongest signal for MTG: set + collector number (language-independent).
        outer@ for (set in setCandidates) {
            for (number in numberCandidates) {
                val card = runCatching { scryfall.cardBySetAndNumber(set, number) }.getOrNull()
                if (card != null) {
                    results += card.toIdentified(ocrScore = 0.98f)
                    break@outer
                }
            }
        }

        if (results.isEmpty() && !parsed.collectorNumber.isNullOrBlank()) {
            val cn = numberCandidates.first()
            val setPart = setCandidates.firstOrNull()?.let { "s:$it" }.orEmpty()
            val q = listOf("cn:\"$cn\"", setPart).filter { it.isNotBlank() }.joinToString(" ")
            runCatching { scryfall.search(q) }
                .onSuccess { response ->
                    val prior = if (setCandidates.isNotEmpty()) 0.78f else 0.62f
                    results += response.data.map { it.toIdentified(ocrScore = prior) }
                }
        }

        if (!name.isNullOrBlank()) {
            val queries = buildList {
                add("!\"$name\"")
                add("lang:it !\"$name\"")
                add("lang:it name:\"$name\"")
                add(name)
                if (!parsed.collectorNumber.isNullOrBlank()) {
                    add("cn:${numberCandidates.first()} name:\"$name\"")
                }
            }
            for (q in queries) {
                if (results.size >= 8) break
                runCatching { scryfall.search(q) }
                    .onSuccess { response ->
                        results += response.data.map { it.toIdentified(ocrScore = 0.45f) }
                    }
                    .onFailure { if (it !is HttpException) throw it }
            }
        }
        return results
    }

    private suspend fun lookupPokemon(parsed: ParsedCardText, name: String?): List<IdentifiedCard> {
        val number = OcrParser.collectorCandidates(parsed.collectorNumber).firstOrNull()
        val langs = PokemonCatalog.languages(name, parsed.setCode)
        val cards = coroutineScope {
            langs.map { lang ->
                async { searchPokemonLanguage(lang, name, number, parsed) }
            }.awaitAll().flatten()
        }
        return cards
            .distinctBy { "${it.image}-${it.id}" }
            .map { card ->
                val setIds = PokemonSets.idsFor(parsed.setCode, cardLang(card))
                val raw = PokemonCatalog.score(
                    cardName = card.name,
                    cardNumber = card.localId,
                    setId = card.set?.id ?: card.id.substringBefore('-'),
                    printedTotal = card.set?.cardCount?.official,
                    queryName = name,
                    queryNumber = number,
                    querySetIds = setIds,
                    setCardCount = parsed.setCardCount,
                )
                card.toIdentified().copy(ocrScore = (raw / 145f).coerceIn(0.05f, 1f))
            }
            .sortedByDescending { it.ocrScore ?: 0f }
            .take(12)
    }

    private suspend fun searchPokemonLanguage(
        lang: String,
        name: String?,
        number: String?,
        parsed: ParsedCardText,
    ): List<TcgdexCard> {
        val setIds = if (lang == PokemonCatalog.EN || lang == PokemonCatalog.IT) {
            val mapped = OcrParser.setCodeCandidates(parsed.setCode)
                .flatMap { PokemonSets.recognizedIds(it) }
                .distinct()
            mapped.ifEmpty { PokemonSets.idsFor(parsed.setCode, lang) }
        } else {
            PokemonSets.idsFor(parsed.setCode, lang)
        }
        val found = linkedMapOf<String, TcgdexCard>()

        if (!number.isNullOrBlank()) {
            for (setId in setIds.take(4)) {
                for (id in PokemonCatalog.cardIds(setId, number)) {
                    val card = runCatching { tcgdex.card(lang, id) }.getOrNull() ?: continue
                    found[card.id] = card
                }
                if (found.isNotEmpty()) break
            }
        }
        if (found.values.any { it.matches(name, number, setIds) }) return found.values.toList()

        if (!name.isNullOrBlank()) {
            for (query in PokemonCatalog.nameQueries(name)) {
                val briefs = runCatching { tcgdex.search(lang, name = query) }.getOrDefault(emptyList())
                fetchBriefs(lang, pickBriefs(briefs, number, setIds), found)
                if (found.values.any { it.matches(name, number, setIds) }) break
            }
        }

        if (found.values.none { it.matches(name, number, setIds) } && !number.isNullOrBlank()) {
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

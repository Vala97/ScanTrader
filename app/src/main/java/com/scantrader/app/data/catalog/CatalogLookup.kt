package com.scantrader.app.data.catalog

import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.network.scryfall.ScryfallApi
import com.scantrader.app.network.scryfall.ScryfallCard
import com.scantrader.app.network.tcgdex.TcgdexApi
import com.scantrader.app.network.tcgdex.TcgdexCard
import com.scantrader.app.scan.OcrParser
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
        }.distinctBy { it.scryfallId ?: "${it.setCode}-${it.collectorNumber}-${it.name}" }
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
                    results += card.toIdentified()
                    break@outer
                }
            }
        }

        if (results.isEmpty() && !parsed.collectorNumber.isNullOrBlank()) {
            val cn = numberCandidates.first()
            val setPart = setCandidates.firstOrNull()?.let { "s:$it" }.orEmpty()
            val q = listOf("cn:\"$cn\"", setPart).filter { it.isNotBlank() }.joinToString(" ")
            runCatching { scryfall.search(q) }
                .onSuccess { response -> results += response.data.map { it.toIdentified() } }
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
                    .onSuccess { response -> results += response.data.map { it.toIdentified() } }
                    .onFailure { if (it !is HttpException) throw it }
            }
        }
        return results
    }

    private suspend fun lookupPokemon(parsed: ParsedCardText, name: String?): List<IdentifiedCard> {
        val scored = mutableListOf<Pair<IdentifiedCard, Int>>()
        val seen = mutableSetOf<String>()
        val numberCandidates = OcrParser.collectorCandidates(parsed.collectorNumber)
        val setIds = PokemonSets.idCandidates(parsed.setCode)
        val nameTokens = name.orEmpty().lowercase()
            .split(Regex("""[^a-z0-9àèéìòù]+"""))
            .filter { it.length >= 3 && it !in setOf("del", "della", "team", "the", "and") }

        suspend fun addBrief(lang: String, brief: com.scantrader.app.network.tcgdex.TcgdexCardBrief, baseScore: Int) {
            if (!seen.add(brief.id)) return
            val detail = runCatching { tcgdex.card(lang, brief.id) }.getOrNull()
            val card = (detail ?: TcgdexCard(brief.id, brief.localId, brief.name, brief.image, null)).toIdentified()
            var score = baseScore
            if (pokemonNumberMatches(brief.localId, numberCandidates)) score += 50
            if (pokemonSetMatches(brief.id, setIds)) score += 30
            if (nameTokens.any { token -> brief.name.lowercase().contains(token) }) score += 20
            if (parsed.setCardCount != null && detail?.set?.cardCount?.official == parsed.setCardCount) score += 25
            scored += card to score
        }

        // 1) Italian + English name search (Italian cards OCR in Italian).
        for (query in buildPokemonNameQueries(name)) {
            for (lang in listOf("it", "en")) {
                val briefs = runCatching { tcgdex.search(lang = lang, name = query) }.getOrDefault(emptyList())
                for (brief in briefs) {
                    // Soft: if we have a number, prefer matching ones but keep others with lower score.
                    val base = if (pokemonNumberMatches(brief.localId, numberCandidates) || numberCandidates.isEmpty()) 40 else 5
                    addBrief(lang, brief, base)
                }
            }
        }

        // 2) Direct set+number only as a candidate (may be wrong if set OCR misread).
        for (setId in setIds) {
            for (number in numberCandidates) {
                for (lang in listOf("it", "en")) {
                    for (id in listOf("$setId-$number", "$setId-${number.padStart(3, '0')}")) {
                        val card = runCatching { tcgdex.card(lang, id) }.getOrNull() ?: continue
                        if (!seen.add(card.id)) continue
                        var score = 35
                        if (nameTokens.any { token -> card.name.lowercase().contains(token) }) score += 40
                        scored += card.toIdentified() to score
                    }
                }
            }
        }

        // 3) Collector-number search ranked by name overlap / set count.
        if (scored.none { it.second >= 40 } && numberCandidates.isNotEmpty()) {
            val localId = numberCandidates.first()
            for (lang in listOf("it", "en")) {
                val briefs = runCatching { tcgdex.search(lang = lang, localId = localId) }.getOrDefault(emptyList())
                for (brief in briefs) {
                    addBrief(lang, brief, 10)
                }
            }
        }

        return scored
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { it.setCode + "-" + it.collectorNumber + "-" + it.name }
            .take(12)
    }

    private fun buildPokemonNameQueries(name: String?): List<String> {
        val full = name?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val out = linkedSetOf(full)
        // "Muk del Team Rocket" → also try "Muk", "Team Rocket"
        val parts = full.split(Regex("""\s+""")).filter { it.length >= 3 }
        parts.firstOrNull()?.let { out += it }
        if (parts.size >= 2) {
            out += parts.take(2).joinToString(" ")
            out += parts.takeLast(2).joinToString(" ")
        }
        // Drop articles that hurt English search
        out += full.replace(Regex("""(?i)\b(del|della|dei|delle|di|the|of)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return out.filter { it.length >= 3 }.distinct()
    }

    private fun pokemonNumberMatches(localId: String, candidates: List<String>): Boolean {
        if (candidates.isEmpty()) return true
        val normalized = localId.trimStart('0').ifBlank { localId }
        return candidates.any {
            localId.equals(it, ignoreCase = true) ||
                normalized.equals(it.trimStart('0').ifBlank { it }, ignoreCase = true)
        }
    }

    private fun pokemonSetMatches(cardId: String, setIds: List<String>): Boolean {
        if (setIds.isEmpty()) return true
        return setIds.any { setId ->
            cardId.startsWith("$setId-", ignoreCase = true) ||
                cardId.startsWith(setId, ignoreCase = true)
        }
    }

    private fun ScryfallCard.toIdentified(): IdentifiedCard =
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
        )

    private fun TcgdexCard.toIdentified(): IdentifiedCard {
        val cm = pricing?.cardmarket
        val avg = cm?.avg ?: cm?.trend
        val avgHolo = cm?.avgHolo ?: cm?.trendHolo
        return IdentifiedCard(
            game = Game.POKEMON,
            name = name,
            setCode = set?.id.orEmpty(),
            setName = set?.name.orEmpty(),
            collectorNumber = localId,
            tcgplayerId = null,
            imageUrl = image?.let { "$it/low.webp" },
            priceEur = avg?.let { String.format(java.util.Locale.US, "%.2f", it) },
            priceEurFoil = avgHolo?.let { String.format(java.util.Locale.US, "%.2f", it) },
        )
    }
}

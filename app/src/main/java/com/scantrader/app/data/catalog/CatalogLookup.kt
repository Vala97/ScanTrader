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
import com.scantrader.app.scan.CardOrientation
import com.scantrader.app.scan.FrameNameGate
import com.scantrader.app.scan.OcrParser
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import retrofit2.HttpException

class CatalogLookup(
    private val scryfall: ScryfallClient,
    private val tcgdex: TcgdexApi,
    private val localSets: LocalSetCatalog? = null,
    private val riftbound: RiftboundCatalog? = null,
    private val ctGames: Map<Game, CtGameCatalog> = emptyMap(),
) {
    suspend fun identify(game: Game, parsed: ParsedCardText, manualQuery: String? = null): List<IdentifiedCard> {
        val queryName = manualQuery?.trim()?.takeIf { it.isNotBlank() } ?: parsed.nameGuess
        return when (game) {
            Game.MTG -> lookupMtg(parsed, queryName)
            Game.POKEMON -> lookupPokemon(parsed, queryName)
            Game.RIFTBOUND -> riftbound?.identify(parsed, manualQuery).orEmpty()
            Game.ONE_PIECE, Game.DRAGON_BALL, Game.YUGIOH, Game.UNION_ARENA -> ctGames[game]?.identify(parsed, manualQuery).orEmpty()
            Game.OTHER -> emptyList() // solo inventario
        }.distinctBy { it.scryfallId ?: "${it.imageUrl}-${it.setCode}-${it.collectorNumber}-${it.name}" }
            .take(12)
    }

    private suspend fun lookupMtg(parsed: ParsedCardText, name: String?): List<IdentifiedCard> {
        val setCandidates = OcrParser.setCodeCandidates(parsed.setCode)
            .filter { code -> code.any { it.isLetter() } && code.count { it.isDigit() } < 3 }
            .filter { KnownSetCodes.isKnown(Game.MTG, it) }
            .take(2)
        val number = plausibleMtgCollector(parsed.collectorNumber)
        val nameAttempts = OcrParser.mtgNameAttempts(name)
        if (nameAttempts.isEmpty() && number.isNullOrBlank()) return emptyList()
        resolveMtgLocal(parsed, name, setCandidates, number, nameAttempts)?.let { hits ->
            rememberSets(Game.MTG, hits)
            return hits
        }
        if (scryfall.coolingDown()) throw ScryfallBusyException()
        val hits = lookupMtgOnline(parsed, name, setCandidates, number, nameAttempts)
        rememberSets(Game.MTG, hits)
        return hits
    }

    private suspend fun lookupMtgOnline(
        parsed: ParsedCardText,
        name: String?,
        setCandidates: List<String>,
        number: String?,
        nameAttempts: List<String>,
    ): List<IdentifiedCard> {
        // Set + collector only when the returned title agrees with OCR.
        // hob/36 is Elrond when the card reads Ravening Warg — do not keep it.
        var preferNumber = number
        for (set in setCandidates) {
            if (number.isNullOrBlank()) break
            val card = runCatching { scryfall.cardBySetAndNumber(set, number) }.orRethrowBusy()
                ?: continue
            if (mtgIdAgrees(card, name)) return listOf(card.asIdentified(ocrScore = 0.98f))
            // English oracle name missed. Adventure faces keep the Italian title
            // on card_faces.printed_name (`Maestro di Spada Invaghito`).
            val localized = localizedIdHit(card, name, parsed.printedLang)
            if (localized != null) return listOf(localized)
            preferNumber = null
        }

        for (searchName in nameAttempts) {
            val found = lookupMtgByName(
                searchName = searchName,
                number = preferNumber,
                sets = setCandidates,
                nameAndNumber = preferNumber != null && setCandidates.isEmpty(),
            )
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /**
     * Cached set index. Null means "not handled here" — the caller keeps the online path.
     * A confident set+number or an exact title in a complete set is returned with the
     * same scores as the Scryfall calls they replace. Fuzzy and stem stay online.
     */
    private fun resolveMtgLocal(
        parsed: ParsedCardText,
        name: String?,
        setCandidates: List<String>,
        number: String?,
        nameAttempts: List<String>,
    ): List<IdentifiedCard>? {
        val local = localSets ?: return null
        if (setCandidates.isEmpty()) return null
        val started = System.currentTimeMillis()
        val snapshots = ArrayList<MtgSetSnapshot>(setCandidates.size)
        for (set in setCandidates) {
            snapshots += local.mtgSnapshot(set) ?: return null
        }
        var preferNumber = number
        if (!number.isNullOrBlank()) {
            for (snap in snapshots) {
                val card = snap.defaultPrint(number)
                if (card == null) continue
                if (mtgIdAgrees(card, name)) {
                    local.logHit(snap.setCode, System.currentTimeMillis() - started)
                    return listOf(card.asIdentifiedFromCache(snap.cards, 0.98f))
                }
                val localized = localizedFromCache(snap, card, name, parsed.printedLang)
                if (localized != null) {
                    local.logHit(snap.setCode, System.currentTimeMillis() - started)
                    return listOf(localized)
                }
                preferNumber = null
            }
        }
        for (searchName in nameAttempts) {
            val found = nameFromCache(snapshots, searchName, preferNumber) ?: continue
            local.logHit(found.setCode, System.currentTimeMillis() - started)
            return listOf(found)
        }
        return null
    }

    private fun localizedFromCache(
        snap: MtgSetSnapshot,
        english: ScryfallCard,
        ocrName: String?,
        printedLang: String?,
    ): IdentifiedCard? {
        val title = OcrParser.cleanMtgNameGuess(ocrName) ?: return null
        if (CardOrientation.implausibleTitle(title)) return null
        if (FrameNameGate.confidentTitle(title) == null) return null
        val langs = listOfNotNull(
            printedLang?.takeIf { !it.equals("en", ignoreCase = true) },
            "it",
        ).map { it.lowercase(Locale.ROOT) }.distinct()
        for (lang in langs) {
            val local = snap.printsFor(english.collectorNumber).firstOrNull {
                it.lang.equals(lang, ignoreCase = true)
            } ?: continue
            if (!mtgIdAgrees(local, title)) continue
            val printed = local.localizedPrintedName()
            return local.asIdentifiedFromCache(snap.cards, 0.98f).copy(
                name = printed ?: local.name,
                printedName = printed,
                printedLang = lang,
            )
        }
        return null
    }

    private fun nameFromCache(
        snapshots: List<MtgSetSnapshot>,
        searchName: String,
        number: String?,
    ): IdentifiedCard? {
        if (snapshots.any { !it.complete }) return null
        val safe = searchName.replace("\"", "").trim()
        if (safe.isBlank()) return null
        for (snap in snapshots) {
            val hits = snap.cards.filter { card ->
                (oracleEquals(card, safe) || printedEquals(card, safe)) && mtgIdAgrees(card, safe)
            }
            val picked = pickCachedName(hits, number) ?: continue
            val printedOnly = printedEquals(picked, safe) && !oracleEquals(picked, safe)
            val identified = picked.asIdentifiedFromCache(snap.cards, if (printedOnly) 0.88f else 0.96f)
            if (!printedOnly) return identified
            val printed = picked.localizedPrintedName()
            return identified.copy(
                name = printed ?: identified.name,
                printedName = printed,
                printedLang = picked.lang?.trim()?.lowercase(Locale.ROOT),
            )
        }
        return null
    }

    private fun pickCachedName(hits: List<ScryfallCard>, number: String?): ScryfallCard? {
        if (hits.isEmpty()) return null
        val pool = if (number.isNullOrBlank()) {
            val collectors = hits.map { it.collectorNumber.lowercase(Locale.ROOT) }.distinct()
            if (collectors.size != 1) return null
            hits
        } else {
            val numbered = hits.filter {
                it.collectorNumber.equals(number, ignoreCase = true) || collectorEquals(it.collectorNumber, number)
            }
            if (numbered.isEmpty()) return null
            numbered
        }
        return pool.firstOrNull { it.lang.isNullOrBlank() || it.lang.equals("en", ignoreCase = true) }
            ?: pool.first()
    }

    private fun oracleEquals(card: ScryfallCard, query: String): Boolean {
        if (card.name.equals(query, ignoreCase = true)) return true
        return card.cardFaces.orEmpty().any { it.name?.equals(query, ignoreCase = true) == true }
    }

    private fun printedEquals(card: ScryfallCard, query: String): Boolean {
        if (card.localizedPrintedName()?.equals(query, ignoreCase = true) == true) return true
        return card.cardFaces.orEmpty().any { it.printedName?.equals(query, ignoreCase = true) == true }
    }

    private fun ScryfallCard.asIdentifiedFromCache(cards: List<ScryfallCard>, ocrScore: Float): IdentifiedCard {
        val base = toIdentified(ocrScore)
        val language = lang?.trim()?.lowercase(Locale.ROOT)
        if (language.isNullOrEmpty() || language == "en") {
            return base.copy(catalogName = name.takeIf { it.isNotBlank() } ?: base.catalogName)
        }
        val english = cards.firstOrNull { other ->
            (other.lang.isNullOrBlank() || other.lang.equals("en", ignoreCase = true)) &&
                other.set.equals(set, ignoreCase = true) &&
                (other.collectorNumber.equals(collectorNumber, ignoreCase = true) ||
                    collectorEquals(other.collectorNumber, collectorNumber))
        }
        return base.copy(
            catalogName = english?.name?.takeIf { it.isNotBlank() } ?: base.catalogName,
            catalogScryfallId = english?.id?.takeIf { it.isNotBlank() && it != id },
        )
    }

    /**
     * One name: the known set first (`set:hob !"Ravening Warg"`), then collector+name
     * when there is no set, then fuzzy, then the Italian exact title.
     */
    private suspend fun lookupMtgByName(
        searchName: String,
        number: String?,
        sets: List<String> = emptyList(),
        nameAndNumber: Boolean,
    ): List<IdentifiedCard> {
        val safeName = searchName.replace("\"", "").trim()
        if (safeName.isBlank()) return emptyList()
        val inSet = searchMtgInSets(safeName, sets, number)
        if (inSet.isNotEmpty()) return inSet
        // English `name:` field. An Italian printed title misses here.
        if (nameAndNumber && !number.isNullOrBlank()) {
            val byNumber = searchOnce("cn:$number (lang:it name:\"$safeName\" OR name:\"$safeName\")")
            if (byNumber.isNotEmpty()) return byNumber
        }
        if (sets.isNotEmpty()) {
            for (set in sets) {
                val inSet = runCatching { scryfall.namedFuzzy(safeName, set) }.orRethrowBusy()
                if (inSet != null && mtgIdAgrees(inSet, safeName)) {
                    return listOf(inSet.asIdentified(ocrScore = 0.84f))
                }
            }
            val stemmed = searchMtgStem(safeName, sets, number)
            if (stemmed.isNotEmpty()) return stemmed
        }
        val fuzzy = runCatching { scryfall.namedFuzzy(safeName) }.orRethrowBusy()
        if (fuzzy != null && sets.isNotEmpty() && mtgIdAgrees(fuzzy, safeName)) {
            val printing = searchMtgInSets(fuzzy.name, sets, number)
            if (printing.isNotEmpty()) return printing
        }
        val cnOk = number.isNullOrBlank() || collectorEquals(fuzzy?.collectorNumber, number)
        if (fuzzy != null && cnOk && (sets.isEmpty() || mtgIdAgrees(fuzzy, safeName))) {
            return listOf(fuzzy.asIdentified(ocrScore = 0.72f))
        }
        return lookupItalianPrintedName(safeName, number)
    }

    /**
     * Exact name in the set, then a name query in that set.
     * Prefer the collector when it is present; otherwise the set printing.
     */
    private suspend fun searchMtgInSets(
        safeName: String,
        sets: List<String>,
        number: String?,
    ): List<IdentifiedCard> {
        if (sets.isEmpty() || safeName.isBlank()) return emptyList()
        for (set in sets) {
            val exact = pickMtgPrinting(searchCards("set:$set !\"$safeName\""), set, number)
            if (exact != null && mtgIdAgrees(exact, safeName)) {
                return listOf(exact.asIdentified(ocrScore = 0.96f))
            }
        }
        for (set in sets) {
            val named = pickMtgPrinting(searchCards("set:$set name:\"$safeName\""), set, number)
            if (named != null && mtgIdAgrees(named, safeName)) {
                return listOf(named.asIdentified(ocrScore = 0.9f))
            }
        }
        return emptyList()
    }

    /**
     * `Goblir` does not exact-match "Down, Down to Goblin-town".
     * A 5-letter stem (`gobli`) inside the set does, then the collector picks the print.
     */
    private suspend fun searchMtgStem(
        safeName: String,
        sets: List<String>,
        number: String?,
    ): List<IdentifiedCard> {
        val stem = safeName.lowercase().filter { it.isLetter() }.take(5)
        if (stem.length < 4) return emptyList()
        for (set in sets) {
            val agreed = searchCards("set:$set name:/$stem/").filter { mtgIdAgrees(it, safeName) }
            val picked = pickMtgPrinting(agreed, set, number) ?: continue
            return listOf(picked.asIdentified(ocrScore = 0.8f))
        }
        return emptyList()
    }

    private fun pickMtgPrinting(cards: List<ScryfallCard>, set: String, number: String?): ScryfallCard? {
        val inSet = cards.filter { it.set.equals(set, ignoreCase = true) }.ifEmpty { cards }
        if (!number.isNullOrBlank()) {
            inSet.firstOrNull { collectorEquals(it.collectorNumber, number) }?.let { return it }
        }
        return inSet.firstOrNull()
    }

    /** A year or a collector past any real set (`2036`) is not an id lookup. */
    private fun plausibleMtgCollector(raw: String?): String? {
        val first = OcrParser.collectorCandidates(raw).firstOrNull() ?: return null
        val n = first.filter { it.isDigit() }.trimStart('0').toIntOrNull() ?: return null
        if (n !in 1..999) return null
        return first
    }

    /**
     * Set+collector hit whose English name missed. Try the printed language
     * (from `ELDIT…`) and Italian, and keep the card only when a face title agrees.
     * `nchar` is not a confirmation. Ravening Warg still rejects Elrond.
     */
    private suspend fun localizedIdHit(
        card: ScryfallCard,
        ocrName: String?,
        printedLang: String?,
    ): IdentifiedCard? {
        val title = OcrParser.cleanMtgNameGuess(ocrName) ?: return null
        if (CardOrientation.implausibleTitle(title)) return null
        if (FrameNameGate.confidentTitle(title) == null) return null
        val langs = listOfNotNull(
            printedLang?.takeIf { !it.equals("en", ignoreCase = true) },
            "it",
        ).map { it.lowercase() }.distinct()
        for (lang in langs) {
            val local = runCatching {
                scryfall.cardBySetNumberLang(card.set, card.collectorNumber, lang)
            }.orRethrowBusy() ?: continue
            if (!mtgIdAgrees(local, title)) continue
            val printed = local.localizedPrintedName()
            return local.asIdentified(ocrScore = 0.98f).copy(
                name = printed ?: local.name,
                printedName = printed,
                printedLang = lang,
            )
        }
        return null
    }

    private fun mtgIdAgrees(card: ScryfallCard, ocrName: String?): Boolean {
        val title = OcrParser.cleanMtgNameGuess(ocrName) ?: return true
        // `nchar` is not evidence for or against the printing. A real title is.
        if (CardOrientation.implausibleTitle(title)) return false
        if (FrameNameGate.confidentTitle(title) == null) return true
        val printed = card.localizedPrintedName()
        if (FrameNameGate.agreesName(Game.MTG, card.name, printed, title)) return true
        return card.cardFaces.orEmpty().any { face ->
            FrameNameGate.agreesName(Game.MTG, face.name, face.printedName, title)
        }
    }

    /**
     * `lang:it !"…"` matches [ScryfallCard.printedName] (there is no `printed_name:` keyword).
     * The Italian scan is often a different printing than the one OCR numbered: Morningtide
     * prints Frogtosser Banneret as 64/150, while the Italian object on Scryfall is another set.
     * Resolve the English name, then pick the print with the collector number.
     */
    private suspend fun lookupItalianPrintedName(safeName: String, number: String?): List<IdentifiedCard> {
        val printed = searchCards("lang:it !\"$safeName\"")
        val italian = printed.firstOrNull() ?: return emptyList()
        if (number.isNullOrBlank() || collectorEquals(italian.collectorNumber, number)) {
            return listOf(italian.asIdentified(ocrScore = 0.88f))
        }
        val english = italian.name.replace("\"", "").trim()
        if (english.isNotBlank() && !english.equals(safeName, ignoreCase = true)) {
            val sameNumber = searchCards("cn:$number !\"$english\"")
            val print = sameNumber.firstOrNull()
            if (print != null) {
                val title = italian.printedName?.takeIf { it.isNotBlank() } ?: safeName
                return listOf(
                    print.asIdentified(ocrScore = 0.9f).copy(
                        name = title,
                        printedName = title,
                        printedLang = "it",
                    ),
                )
            }
        }
        return listOf(italian.asIdentified(ocrScore = 0.88f))
    }

    private suspend fun searchCards(query: String): List<ScryfallCard> {
        val response = runCatching { scryfall.search(query) }
            .onFailure { if (it !is HttpException && it !is com.scantrader.app.network.scryfall.ScryfallMissException) throw it }
            .getOrNull()
            ?: return emptyList()
        return response.data
    }

    private suspend fun searchOnce(query: String): List<IdentifiedCard> {
        val cards = searchCards(query)
        if (cards.isEmpty()) return emptyList()
        val score = if (query.contains("!\"")) 0.88f else 0.7f
        val identified = ArrayList<IdentifiedCard>(minOf(cards.size, 4))
        for (card in cards.take(4)) {
            identified += card.asIdentified(ocrScore = score)
        }
        return identified
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

    private suspend fun lookupPokemon(parsedInput: ParsedCardText, name: String?): List<IdentifiedCard> {
        val parsed = KnownSetCodes.scrub(Game.POKEMON, parsedInput)
        val number = OcrParser.collectorCandidates(parsed.collectorNumber).firstOrNull()
        val noisyName = OcrParser.looksLikeAbilityOrAttackName(name) ||
            (name != null && com.scantrader.app.data.catalog.CardIdentity.isStageNoise(name))
        // When OCR grabbed an ability/attack or a stage crumb, ignore name — identity is set+number.
        val searchName = name.takeUnless { noisyName }
        // A printed total is not a set. cn-only lookups need a resolved set id.
        val setKnown = PokemonSets.directIds(parsed.setCode, parsed.setCardCount).isNotEmpty()
        val langs = PokemonCatalog.languages(searchName, parsed.setCode)
        resolvePokemonLocal(parsed, searchName, number, noisyName)?.let { hits ->
            rememberSets(Game.POKEMON, hits)
            return hits
        }
        val cards = coroutineScope {
            langs.map { lang ->
                async {
                    searchPokemonLanguage(
                        lang,
                        searchName,
                        number,
                        parsed,
                        idFirst = (setKnown && number != null) || noisyName,
                    )
                }
            }.awaitAll().flatten()
        }
        val ranked = rankPokemon(cards, parsed, searchName, number, noisyName)
        rememberSets(Game.POKEMON, ranked)
        return ranked
    }

    /**
     * Local Pokémon hit only when every set id the online lookup would try is cached.
     * Numbered cards are scored with the same function. A miss falls through.
     */
    private fun resolvePokemonLocal(
        parsed: ParsedCardText,
        searchName: String?,
        number: String?,
        noisyName: Boolean,
    ): List<IdentifiedCard>? {
        val local = localSets ?: return null
        val langs = PokemonCatalog.languages(searchName, parsed.setCode)
        val setIds = langs.flatMap { resolvePokemonSetIds(it, parsed) }.distinct()
        if (setIds.isEmpty()) return null
        val started = System.currentTimeMillis()
        val gathered = ArrayList<TcgdexCard>()
        for (lang in langs) {
            val ids = resolvePokemonSetIds(lang, parsed)
            if (ids.isEmpty()) continue
            for (id in ids) {
                val snap = local.pokemonSnapshot(id, lang) ?: return null
                gathered += if (number.isNullOrBlank()) {
                    snap.cards
                } else {
                    snap.cards.filter { PokemonCatalog.numberMatches(number, it.localId) }
                }
            }
        }
        val pool = if (number.isNullOrBlank()) {
            val named = gathered.filter { card ->
                !searchName.isNullOrBlank() &&
                    CardIdentity.normalizeName(card.name) == CardIdentity.normalizeName(searchName)
            }
            if (named.map { it.id }.distinct().size != 1) return null
            named
        } else {
            if (gathered.isEmpty()) return null
            gathered
        }
        val ranked = rankPokemon(pool, parsed, searchName, number, noisyName)
        if (ranked.isEmpty()) return null
        local.logHit(ranked.first().setCode, System.currentTimeMillis() - started)
        return ranked
    }

    private fun rankPokemon(
        cards: List<TcgdexCard>,
        parsed: ParsedCardText,
        searchName: String?,
        number: String?,
        noisyName: Boolean,
    ): List<IdentifiedCard> {
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
                val englishName = cards.firstOrNull { it.id == card.id && cardLang(it) == "en" }?.name
                card.toIdentified().copy(
                    ocrScore = (raw / 160f).coerceIn(0.02f, 1f),
                    catalogName = englishName?.takeIf { it.isNotBlank() }
                        ?: card.name.takeIf { cardLang(card) == "en" },
                )
            }
            .sortedByDescending { it.ocrScore ?: 0f }
        // Drop obvious junk when OCR name is a real title (avoid Malamar from "mal di testa").
        // Skip this filter when name is ability noise — id match is enough.
        val filtered = if (!noisyName && !searchName.isNullOrBlank() && searchName.length >= 4) {
            scored.filter { PokemonCatalog.nameMatches(it.name, searchName) }
        } else {
            scored
        }
        return filtered.take(12)
    }

    private fun rememberSets(game: Game, cards: List<IdentifiedCard>) {
        val local = localSets ?: return
        if (game == Game.MTG) {
            cards.asSequence()
                .map { it.setCode }
                .filter { it.isNotBlank() }
                .distinct()
                .take(3)
                .forEach { local.prefetchMtg(it) }
            return
        }
        cards.asSequence()
            .filter { it.setCode.isNotBlank() }
            .distinctBy { it.setCode to it.catalogLang }
            .take(3)
            .forEach { card -> local.prefetchPokemon(card.setCode, pokemonWarmLangs(card.catalogLang)) }
    }

    private fun pokemonWarmLangs(catalogLang: String?): List<String> = when (catalogLang?.lowercase(Locale.ROOT)) {
        "ja", "zh-cn", "zh-tw" -> listOf(PokemonCatalog.JA, PokemonCatalog.ZH_TW, PokemonCatalog.ZH_CN)
        else -> listOf(PokemonCatalog.EN, PokemonCatalog.IT)
    }

    private suspend fun searchPokemonLanguage(
        lang: String,
        name: String?,
        number: String?,
        parsed: ParsedCardText,
        idFirst: Boolean = false,
    ): List<TcgdexCard> {
        val setIds = resolvePokemonSetIds(lang, parsed)
        val setKnown = setIds.isNotEmpty()
        val found = linkedMapOf<String, TcgdexCard>()
        val confidentName = !name.isNullOrBlank() && name.length >= 3

        // Direct set+number only. A collector with no resolved set must not search every #6.
        if (!number.isNullOrBlank() && setKnown) {
            for (setId in setIds.take(6)) {
                for (id in PokemonCatalog.cardIds(setId, number)) {
                    val card = runCatching { tcgdex.card(lang, id) }.getOrNull() ?: continue
                    found[card.id] = card
                }
                if (found.isNotEmpty()) break
            }
        }

        // A confident title is always searched, then ranked with the collector and the total.
        // A direct id that already spells that name (Machop, base1-52) still gets the search
        // so a same-number card from another set cannot outrank it.
        if (confidentName) {
            for (query in PokemonCatalog.nameQueries(name)) {
                val briefs = runCatching { tcgdex.search(lang, name = query) }.getOrDefault(emptyList())
                fetchBriefs(lang, pickBriefs(briefs, number, setIds), found)
                if (found.values.any { PokemonCatalog.nameMatches(it.name, name) }) break
            }
        } else if (!(idFirst && found.isNotEmpty())) {
            if (!name.isNullOrBlank()) {
                for (query in PokemonCatalog.nameQueries(name)) {
                    val briefs = runCatching { tcgdex.search(lang, name = query) }.getOrDefault(emptyList())
                    fetchBriefs(lang, pickBriefs(briefs, number, setIds), found)
                    if (found.values.any { it.matches(name, number, setIds) }) break
                }
            }
        }

        if (confidentName) {
            val named = found.filterValues { PokemonCatalog.nameMatches(it.name, name) }
            if (named.isNotEmpty()) return named.values.toList()
            return emptyList()
        }

        if (found.isEmpty() && setKnown && !number.isNullOrBlank()) {
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
        return found.values.toList()
    }

    private fun resolvePokemonSetIds(lang: String, parsed: ParsedCardText): List<String> {
        val direct = PokemonSets.directIds(parsed.setCode, parsed.setCardCount)
        if (lang == PokemonCatalog.EN || lang == PokemonCatalog.IT) return direct
        // Japanese and Chinese print their own set id. Keep that token.
        val raw = PokemonSets.idsFor(parsed.setCode, lang)
        return (raw + PokemonSets.idsForCardCount(parsed.setCardCount)).distinct()
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

    /**
     * Display name stays the printed title. [IdentifiedCard.catalogName] keeps the
     * English Scryfall name, and a non-English print also stores that printing's id.
     */
    private suspend fun ScryfallCard.asIdentified(ocrScore: Float? = null): IdentifiedCard =
        toIdentified(ocrScore).withEnglishBlueprint(this)

    private suspend fun IdentifiedCard.withEnglishBlueprint(source: ScryfallCard): IdentifiedCard {
        val named = copy(catalogName = source.name.takeIf { it.isNotBlank() } ?: catalogName)
        val lang = source.lang?.trim()?.lowercase(Locale.ROOT)
        if (lang.isNullOrEmpty() || lang == "en") return named
        val english = runCatching {
            scryfall.cardBySetNumberLang(source.set, source.collectorNumber, "en")
        }.orRethrowBusy() ?: return named
        return named.copy(
            catalogName = english.name.takeIf { it.isNotBlank() } ?: named.catalogName,
            catalogScryfallId = english.id.takeIf { it.isNotBlank() && it != source.id },
        )
    }

    private fun ScryfallCard.toIdentified(ocrScore: Float? = null): IdentifiedCard =
        IdentifiedCard(
            game = Game.MTG,
            // Prefer printed (IT) name when Scryfall returns it — matches OCR titles.
            name = localizedPrintedName() ?: name,
            catalogName = name.takeIf { it.isNotBlank() },
            setCode = set,
            setName = setName.orEmpty(),
            collectorNumber = collectorNumber,
            scryfallId = id,
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
            printedName = printedName?.takeIf { it.isNotBlank() },
            catalogLang = lang?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() },
            finish = com.scantrader.app.scan.CardFinish.from(finishes, frameEffects, promoTypes),
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
            catalogLang = cardLang(this),
            imageUrl = image?.let { "$it/low.webp" },
            priceEur = formatEur(cm?.avg ?: cm?.trend),
            priceCurrency = if (cm?.avg != null || cm?.trend != null) "EUR" else null,
            priceSource = if (cm?.avg != null || cm?.trend != null) "Cardmarket" else null,
        )
    }

    private fun formatEur(value: Double?): String? {
        if (value == null || value <= 0.0) return null
        return String.format(Locale.US, "%.2f", value)
    }
}

package com.scantrader.app.scan

import com.scantrader.app.data.catalog.PokemonSets
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.ParsedCardText

object OcrParser {
    private val collectorFraction = Regex("""\b(\d{1,4}[A-Za-z]?)\s*/\s*\d{1,4}\b""")

    /** Prefer multi-digit collector (072/217), avoid glued OCR like Uor2/217 → cn=2. */
    private val pokemonFractionStrict = Regex("""\b(\d{2,3}[A-Za-z]?)\s*/\s*(\d{2,3})\b""")

    /**
     * Truncated OCR denominator common on low-res frames: `037/2` instead of `037/217`.
     * Still trust the collector (numerator).
     */
    private val pokemonFractionTruncated = Regex("""\b(\d{2,3}[A-Za-z]?)\s*/\s*(\d{1,2})\b""")

    /**
     * "Si evolve da X" / OCR mangled "Sievalve da X" / "ve da X" — parent species, not the card title.
     */
    private val evolveFromLine = Regex(
        """(?i)(?:si\s*)?e?volv\w{0,4}\s+da\s+.+|(?:^|\b)ve\s+da\s+.+|(?:^|\b)evolves?\s+from\s+.+""",
    )

    /**
     * Modern frame: `0181 • DFT • IT` or `181 - BLB - EN`.
     * Do NOT treat `/` as a set separator — old MTG prints `74/165` (cn/set-size).
     */
    private val mtgBottomLine = Regex(
        """(?i)(\d{1,4}[A-Za-z]?)\s*[•·●\*\.\,\|\-–—]\s*([A-Z0-9]{2,5})\s*(?:[•·●\*\.\,\|\-–—]\s*)?(EN|IT|DE|FR|ES|JP|JA|PH|KO|ZHS|ZHT)?\b""",
    )

    /** Number and set on one line without separators: `0181 DFT IT` */
    private val mtgSpaced = Regex(
        """(?i)\b(\d{1,4}[A-Za-z]?)\s+([A-Z]{2,5})\s+(EN|IT|DE|FR|ES|JP|JA|PH|KO)\b""",
    )

    /** Set near language only: `DFT • IT`, `FRA *EN`, `FRAIT` */
    private val mtgSetNearLang = Regex(
        """(?i)\b([A-Z0-9]{2,5})\s*[•·●\*\.\,/\|\-–—]?\s*(EN|IT|DE|FR|ES|JP|JA|PH|KO)\b""",
    )

    /**
     * OCR often reads leading zero as O: `RO162`, `R O113`, `O162`.
     * Also glues lang: `FRAIT`, `FRA*EN`.
     */
    private val mtgOcrZeroCollector = Regex("""(?i)\bR?\s*O(\d{2,4})\b""")
    /** Number near set across lines (`0028` then `FRA IT`). */
    private val mtgNumberNearSet = Regex(
        """(?i)\b(\d{2,4})\b[\s\S]{0,48}?\b([A-Z]{3})\b|\b([A-Z]{3})\b[\s\S]{0,48}?\b(\d{2,4})\b""",
    )

    /**
     * Pre-modern collector print: `74/165` (number / cards-in-set).
     * Not power/toughness (`+1/+1`, `2/2`) — requires 2+ digit cn and large denominator.
     */
    private val mtgOldCollectorFraction = Regex("""(?<![\d+])(\d{2,4})\s*/\s*(\d{2,3})\b""")

    /**
     * Printed set abbr near number with a real separator or space:
     * `DRI 124/182`, `ASC · 072/217` — not `Uor2/217`.
     */
    private val pokemonBottomPair = Regex(
        """(?i)\b([A-Z]{2,4})\s*(?:[•·●\.\,/\|\-–—]+|\s+)\s*(\d{1,3}[A-Za-z]?)\s*/\s*(\d{2,3})\b""",
    )

    /** Letters glued before a fraction: `ASC072/217` or OCR `Uor072/217`. */
    private val pokemonGluedSetFraction = Regex(
        """(?i)\b([A-Za-z]{2,4})(\d{2,3}[A-Za-z]?)\s*/\s*(\d{2,3})\b""",
    )

    private val italianOwnerName = Regex(
        """(?i)\b([A-ZÀ-ÖØ-öø-ÿ][A-Za-zÀ-ÖØ-öø-ÿ]{2,})\s+di\s+([A-ZÀ-ÖØ-öø-ÿ][A-Za-zÀ-ÖØ-öø-ÿ]{2,})\b""",
    )
    private val englishOwnerName = Regex(
        """(?i)\b([A-Z][A-Za-z]{2,}'s)\s+([A-Z][A-Za-z]{2,})\b""",
    )

    /** Italian Base layout: `60 PV` … `Kadabra` … `FASE I`. */
    private val nameBetweenPvAndFase = Regex(
        """(?i)\b\d{1,3}\s*PV\b[\s\S]{0,80}?\b([A-ZÀ-Ö][A-Za-zÀ-ÖØ-öø-ÿ' \-]{2,28})\b[\s\S]{0,40}?FASE""",
    )
    private val nameBetweenHpAndStage = Regex(
        """(?i)\b\d{1,3}\s*HP\b[\s\S]{0,80}?\b([A-Z][A-Za-z' \-]{2,28})\b[\s\S]{0,40}?(?:STAGE|PHASE)""",
    )

    /** First words that must not form "X di Y" owner titles (rules / attack / flavor). */
    private val falseOwnerFirst = setOf(
        "costo", "segnalini", "carta", "tipo", "attacco", "energia", "danno", "regola",
        "tutti", "tutte", "questo", "questa", "ogni", "una", "un", "il", "la", "lo",
        "metti", "scarta", "togli", "assegna", "emette", "mal", "causa", "causano",
        "abitudine", "olfatto", "capacità", "capacita", "modo", "fatto", "gusto",
        "aspetto", "natura", "corpo", "dotato", "nutre", "nutrono", "ipastic",
        "halabitudine", "hal", "mlo",
        // Attack / ability names often OCR'd as "Trasporto di …", "Azione di …".
        "trasporto", "azione", "carica", "morso", "colpo", "onda", "raggio", "fuoco",
        "foglia", "vento", "acqua", "tuono", "psico", "ombra", "doppio", "mega",
        "iper", "maxi", "super", "ultra", "grande", "piccolo", "raffica", "scarica",
    )

    /** Second words of bogus "X di Y" (e.g. costo di ritirata, mal di testa). */
    private val falseOwnerSecond = setOf(
        "ritirata", "ritir", "testa", "danno", "energia", "attacco", "carta", "volte",
        "turno", "mano", "gioco", "banco", "mazzo", "corpo", "tipo",
        "foglie", "riunirsi", "distinguere", "questo", "tale", "un", "una",
        "cianfrusaglie", "oggetto", "oggetti", "strumento", "carte",
    )

    /** Italian card categories after "Pokémon …" — not the card title. */
    private val pokemonCategoryWords = setOf(
        "base", "oliva", "coniglio", "conigio", "panna", "fregata", "suono", "topo", "seme",
        "uccello", "drago", "fuoco", "acqua", "erba", "psico", "lotta", "buio",
        "acciaio", "folletto", "elettro", "terra", "roccia", "ghiaccio", "coleottero",
        "veleno", "spettro", "normale", "volante", "ondasonora", "aiezza", "cooigio",
    )

    /** Weak OCR "set" guesses that are illustrator / English noise, never printed set codes. */
    private val falseSetCodes = setOf(
        "hp", "gx", "ex", "vmax", "vstar", "gold", "illus", "do", "da", "di", "de",
        "the", "and", "for", "with", "from", "star", "base",
        "story", "spot", "light", "spotlight", "card", "rare", "myth", "common",
        "artist", "copyright", "wizards", "magic",
        // Italian/English rules crumbs mistaken for set codes
        "del", "della", "delle", "dei", "pren", "pre", "fin", "fine", "alla", "all",
        "tur", "cre", "una", "per", "con", "ill", "non", "piu", "più", "che", "come",
    )

    private val skipName = setOf(
        "creature", "instant", "sorcery", "enchantment", "artifact", "land", "planeswalker",
        "battle", "legendary", "basic", "token", "trainer", "supporter", "stadium", "energy",
        "item", "pokemon", "pokémon", "weakness", "resistance", "retreat", "illustrator",
        "magic", "the gathering", "creatura", "stregoneria", "incantesimo", "artefatto",
        "terra", "istantaneo", "leggendario",
        "fase", "stadio", "debolezza", "resistenza", "ritirata", "regola", "speciale",
        "evoluzione", "base", "stage", "ability", "abilità", "attacco", "weakness",
        "sievolve", "evolve", "evolves", "ripresa", "superpsico", "lunghezza", "peso",
        "costo", "illus", "wizards", "nintendo",
        // Common Italian attack / rules OCR noise mistaken for titles.
        "calciobasso", "fuoco continuo", "fuococontinuo", "nirata", "htirata",
        "coniglio", "fregata", "pokermon", "cooigio", "aitezaa", "aiezza",
    )

    /**
     * @param rawText full-card OCR (fallback / context)
     * @param bottomPriorityText bottom strips — source of truth for number + set
     * @param namePriorityText top name strip — preferred source for nameGuess (Pokémon)
     */
    fun parse(
        rawText: String,
        game: Game,
        bottomPriorityText: String? = null,
        namePriorityText: String? = null,
    ): ParsedCardText {
        // Pokémon: bottom first, full card as weak fallback.
        // MTG: when a bottom strip was captured, use ONLY that (rules text invents fake cn).
        // If no bottom strip (unit tests / single blob), fall back to raw.
        val bottomNormalized = when (game) {
            Game.MTG -> bottomPriorityText?.let { normalizeMtgBottomOcr(it) }
            Game.POKEMON -> bottomPriorityText
        }
        val identityMerged = when (game) {
            Game.MTG -> bottomNormalized?.takeIf { it.isNotBlank() }
                ?: normalizeMtgBottomOcr(rawText)
            Game.POKEMON -> listOfNotNull(bottomPriorityText, rawText)
                .filter { it.isNotBlank() }
                .joinToString("\n")
        }
        return ParsedCardText(
            nameGuess = extractName(
                primaryText = namePriorityText?.takeIf { it.isNotBlank() } ?: rawText,
                fallbackText = rawText.takeIf {
                    !namePriorityText.isNullOrBlank() && namePriorityText != rawText
                },
                game = game,
            ),
            collectorNumber = extractCollector(identityMerged, bottomNormalized, game),
            setCode = extractSet(identityMerged, bottomNormalized, game),
            setCardCount = extractPokemonSetCount(identityMerged, bottomPriorityText, game),
        )
    }

    /** Fix common MTG bottom OCR: `RO162`→`0162`, `FRAIT`→`FRA IT`, `FRA*EN`→`FRA • EN`. */
    fun normalizeMtgBottomOcr(text: String): String =
        text
            .replace(mtgOcrZeroCollector) { mr ->
                "0${mr.groupValues[1]}"
            }
            .replace(Regex("""(?i)\bFRAIT\b"""), "FRA IT")
            .replace(Regex("""(?i)\b([A-Z]{3})\s*[^\n\w]{0,3}\s*(EN|IT|DE|FR|ES)\b"""), "$1 • $2")

    /** True when OCR name looks like an attack/ability, not a Pokémon title. */
    fun looksLikeAbilityOrAttackName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val lower = name.trim().lowercase()
        if (lower in skipName || lower in falseOwnerFirst) return true
        if (Regex("""(?i)\b(?:abilità|ability|talent|talento)\b""").containsMatchIn(lower)) return true
        val parts = lower.split(Regex("""\s+di\s+"""))
        if (parts.size == 2) {
            val first = parts[0].trim().replace(Regex("""^[^a-zà-öø-ÿ]+"""), "")
            val second = parts[1].trim()
            if (first in falseOwnerFirst || second in falseOwnerSecond) return true
        }
        return false
    }

    private fun extractCollector(raw: String, bottom: String?, game: Game): String? {
        preferSources(bottom, raw).forEach { source ->
            if (game == Game.POKEMON) {
                // 1) Clean `DRI 124/182`
                pokemonBottomPair.find(source)?.groupValues?.getOrNull(2)?.let {
                    return normalizeCollector(it)
                }
                // 2) Prefer strict multi-digit fraction `072/217` over glued `Uor2/217`
                pokemonFractionStrict.find(source)?.groupValues?.getOrNull(1)?.let {
                    return normalizeCollector(it)
                }
                // 3) Glued set+number: take the digit group before slash
                pokemonGluedSetFraction.find(source)?.groupValues?.getOrNull(2)?.let {
                    if (it.length >= 2) return normalizeCollector(it)
                }
                // 4) Truncated denom `037/2` — still usable collector
                pokemonFractionTruncated.find(source)?.groupValues?.getOrNull(1)?.let {
                    return normalizeCollector(it)
                }
                collectorFraction.find(source)?.groupValues?.getOrNull(1)?.let {
                    return normalizeCollector(it)
                }
            }
            if (game == Game.MTG) {
                // Old-frame `74/165` (cn / set-size). Not +1/+1 power-toughness.
                mtgOldCollectorFraction.find(source)?.let { match ->
                    val cn = match.groupValues[1]
                    val total = match.groupValues[2].toIntOrNull() ?: 0
                    if (total >= 40 && (cn.toIntOrNull() ?: 0) < total) {
                        return normalizeCollector(cn)
                    }
                }
                // Raw OCR `R O028` / `RO162` (zero read as O).
                mtgOcrZeroCollector.find(source)?.groupValues?.getOrNull(1)?.let {
                    return normalizeCollector(it)
                }
                mtgBottomLine.find(source)?.groupValues?.getOrNull(1)?.let {
                    return normalizeCollector(it)
                }
                mtgSpaced.find(source)?.groupValues?.getOrNull(1)?.let {
                    return normalizeCollector(it)
                }
                // Prefer 3–4 digit collectors near a set code (`0028` / `FRA`), not `1` from +1/+1.
                mtgNumberNearSet.findAll(source)
                    .mapNotNull { match ->
                        val n = match.groupValues[1].ifBlank { match.groupValues[4] }
                        val set = match.groupValues[2].ifBlank { match.groupValues[3] }
                        n.takeIf { it.length >= 2 && isPlausibleMtgSet(set) }
                    }
                    .maxByOrNull { it.length }
                    ?.let { return normalizeCollector(it) }
                // Bare padded collector after normalize (`0028` on its own line).
                Regex("""(?m)(?<![\d/+])0+(\d{2,3})\b""").findAll(source)
                    .map { it.groupValues[1] }
                    .filter { it.length >= 2 }
                    .maxByOrNull { it.length }
                    ?.let { return normalizeCollector(it) }
            }
        }
        // Last resort: structured bottom lines (modern set code, or old cn/size on copyright line).
        if (game == Game.MTG && !bottom.isNullOrBlank()) {
            for (line in bottom.lines().map { it.trim() }.filter { it.isNotBlank() }.asReversed()) {
                mtgOldCollectorFraction.find(line)?.let { match ->
                    val cn = match.groupValues[1]
                    val total = match.groupValues[2].toIntOrNull() ?: 0
                    if (total >= 40 && (cn.toIntOrNull() ?: 0) < total) {
                        return normalizeCollector(cn)
                    }
                }
                if (line.contains("©") || line.contains("Wizards", ignoreCase = true)) continue
                // Require set-like token nearby: `181 DFT` / `0181 • DFT`
                Regex("""\b(\d{2,4}[A-Za-z]?)\s*[•·●\*\.\,\|\-–—\s]+\s*([A-Z0-9]{2,5})\b""")
                    .find(line)?.groupValues?.getOrNull(1)?.let {
                        return normalizeCollector(it)
                    }
            }
        }
        return null
    }

    private fun extractSet(raw: String, bottom: String?, game: Game): String? {
        if (game == Game.POKEMON) {
            preferSources(bottom, raw).forEach { source ->
                pokemonBottomPair.find(source)?.groupValues?.getOrNull(1)?.let {
                    if (isPlausiblePokemonSet(it)) return normalizeSet(it)
                }
            }
            preferSources(bottom, raw).forEach { source ->
                pokemonGluedSetFraction.find(source)?.let { match ->
                    val letters = match.groupValues[1]
                    val digits = match.groupValues[2]
                    // Only trust glued letters when collector looks real (2–3 digits).
                    if (digits.length in 2..3 && letters.length in 2..4 && isPlausiblePokemonSet(letters)) {
                        return normalizeSet(letters)
                    }
                }
            }
            preferSources(bottom, raw).forEach { source ->
                val nearFraction = Regex(
                    """(?i)\b([A-Z]{2,4})\b(?=[^\n]{0,12}\d{2,3}\s*/\s*\d{1,3}\b)""",
                ).find(source)?.groupValues?.getOrNull(1)
                // Near-fraction is a weak signal — only accept known printed set codes.
                if (nearFraction != null && isKnownPokemonSet(nearFraction)) {
                    return normalizeSet(nearFraction)
                }
                // Set on the next line after the fraction: `004/165\nMEW`
                Regex("""(?i)\d{2,3}[A-Za-z]?\s*/\s*\d{2,3}\s*[\n|]+\s*([A-Z]{2,5})\b""")
                    .find(source)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.takeIf { isKnownPokemonSet(it) }
                    ?.let { return normalizeSet(it) }
            }
            return null
        }
        preferSources(bottom, raw).forEach { source ->
            mtgBottomLine.find(source)?.groupValues?.getOrNull(2)
                ?.takeIf { isPlausibleMtgSet(it) }
                ?.let { return normalizeSet(it) }
            mtgSpaced.find(source)?.groupValues?.getOrNull(2)
                ?.takeIf { isPlausibleMtgSet(it) }
                ?.let { return normalizeSet(it) }
            // Language-adjacent set is the strongest bottom signal (`FRA • EN`).
            // Prefer ALL-CAPS printed codes; skip Italian crumbs (`prende` → pren).
            mtgSetNearLang.findAll(source)
                .mapNotNull { it.groupValues.getOrNull(1)?.takeIf { set -> isPlausibleMtgSet(set) } }
                .firstOrNull()
                ?.let { return normalizeSet(it) }
            mtgNumberNearSet.findAll(source)
                .mapNotNull { match ->
                    val set = match.groupValues[2].ifBlank { match.groupValues[3] }
                    set.takeIf { isPlausibleMtgSet(it) }
                }
                .firstOrNull()
                ?.let { return normalizeSet(it) }
        }
        val bottomLines = (bottom ?: raw).lines().takeLast(4).joinToString(" ")
        // No `/` here — that is cn/set-size on older cards (`74/165`).
        Regex("""\b(\d{1,4}[A-Za-z]?)\s*[•·●\*\.\,\|\-–— ]+\s*([A-Z]{3,5})\b""")
            .find(bottomLines)
            ?.groupValues
            ?.getOrNull(2)
            ?.takeIf { isPlausibleMtgSet(it) }
            ?.let { return normalizeSet(it) }
        return null
    }

    private fun extractPokemonSetCount(raw: String, bottom: String?, game: Game): Int? {
        if (game != Game.POKEMON) return null
        preferSources(bottom, raw).forEach { source ->
            pokemonBottomPair.find(source)?.groupValues?.getOrNull(3)?.toIntOrNull()?.let { return it }
            pokemonFractionStrict.find(source)?.groupValues?.getOrNull(2)?.toIntOrNull()?.let { return it }
            pokemonGluedSetFraction.find(source)?.groupValues?.getOrNull(3)?.toIntOrNull()?.let { return it }
            Regex("""\b\d{1,3}[A-Za-z]?\s*/\s*(\d{2,3})\b""").find(source)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        // Truncated `037/2` or bare `/217` — recover a plausible 3-digit set total.
        val merged = listOfNotNull(bottom, raw).joinToString("\n")
        Regex("""/\s*([12]\d{2})\b""").find(merged)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in 60..300 }
            ?.let { return it }
        if (pokemonFractionTruncated.containsMatchIn(merged)) {
            Regex("""\b([12]\d{2})\b""").findAll(merged)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .firstOrNull { it in 60..300 }
                ?.let { return it }
        }
        return null
    }

    private fun extractName(
        primaryText: String,
        fallbackText: String?,
        game: Game,
    ): String? {
        extractNameFromSource(primaryText, game)?.let { return it }
        if (!fallbackText.isNullOrBlank()) {
            extractNameFromSource(fallbackText, game)?.let { return it }
        }
        return null
    }

    private fun extractNameFromSource(rawText: String, game: Game): String? {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (game == Game.MTG) {
            return extractMtgTitle(lines)
        }
        val nameLines = lines.filterNot { isEvolveFromLine(it) }
        val haystack = nameLines.joinToString("\n")
        // Stop before ability / attack / retreat so those never become the title.
        val header = headerRegion(haystack)
        nameBetweenPvAndFase.find(header)?.groupValues?.getOrNull(1)
            ?.let { cleanPokemonLatinName(it) }
            ?.takeIf { it.split(" ").size <= 4 && !isNoisePokemonName(it) }
            ?.let { return it }
        nameBetweenHpAndStage.find(header)?.groupValues?.getOrNull(1)
            ?.let { cleanPokemonLatinName(it) }
            ?.takeIf { it.split(" ").size <= 4 && !isNoisePokemonName(it) }
            ?.let { return it }
        // Vintage / Base: `40 PV … Weedle` without FASE marker.
        extractNameAfterPvOrHp(header)?.let { return it }
        // Prefer Title-Case "X di Y" in the header (Kilowattrel di Kissara), not attacks/flavor.
        italianOwnerName.findAll(header)
            .map { it.value }
            .filter { isPlausibleOwnerTitle(it) }
            .maxByOrNull { it.length }
            ?.let { return cleanPokemonLatinName(it) }
        // Single Title-case name near the top (Smoliv, Noibat) when no owner title.
        extractEarlyPokemonTitle(header)?.let { return it }
        // Vintage: title repeats in body ("Kadabra") after filtering bogus "mal di testa".
        mostRepeatedTitle(header)?.takeIf { !isNoisePokemonName(it) }?.let { return it }
        englishOwnerName.findAll(header)
            .map { "${it.groupValues[1]} ${it.groupValues[2]}" }
            .filter { !isNoisePokemonName(it) && !looksLikeAbilityOrAttackName(it) }
            .maxByOrNull { it.length }
            ?.let { return cleanPokemonLatinName(it) }
        val latinDominant = isLatinDominant(haystack.ifBlank { rawText })
        val cleanedLines = nameLines.mapNotNull { cleanPokemonLatinName(it) }
            .filter { !isNoisePokemonName(it) && !looksLikeAbilityOrAttackName(it) }
        if (latinDominant) {
            return cleanedLines.firstOrNull { it.length >= 3 && it.lowercase() !in skipName }
                ?: extractLatinLineName(nameLines)?.let { cleanPokemonLatinName(it) }
                    ?.takeIf { !isNoisePokemonName(it) && !looksLikeAbilityOrAttackName(it) }
        }
        // True CJK cards: keep CJK name preference.
        val cjkLine = nameLines.firstOrNull { line ->
            line.any { it.code > 0x2E80 } &&
                line.length in 1..48 &&
                skipName.none { line.lowercase().contains(it) }
        }
        if (cjkLine != null) return cjkLine.trim()
        return cleanedLines.firstOrNull()
            ?: extractLatinLineName(nameLines)?.let { cleanPokemonLatinName(it) }
                ?.takeIf { !isNoisePokemonName(it) && !looksLikeAbilityOrAttackName(it) }
    }

    /** Cut off before ability / attack / retreat / weakness blocks. */
    private fun headerRegion(text: String): String {
        val cut = Regex(
            """(?i)\b(?:abilità|abilita|ability|talent|talento|regola\s+speciale|""" +
                """ritirata|retreat|resistenza|resistance|debolezza|weakness|illus\.?)\b""",
        ).find(text)?.range?.first ?: text.length
        return text.take(cut).ifBlank { text.take(200) }
    }

    private fun extractNameAfterPvOrHp(header: String): String? {
        // Italian modern cards print PS (Punti Salute) instead of HP/PV.
        val anchor = Regex("""(?i)\b\d{1,3}\s*(?:PV|HP|PS)\b|\b(?:PV|HP|PS)\b""")
            .find(header) ?: return null
        val window = header.substring(anchor.range.last + 1, minOf(header.length, anchor.range.last + 160))
        val skip = skipName + pokemonCategoryWords +
            setOf("base", "lunghezza", "peso", "altezza", "bruco", "peloso", "emon", "ps")
        // Prefer multi-word owner titles in the window first.
        italianOwnerName.findAll(window)
            .map { it.value }
            .filter { isPlausibleOwnerTitle(it) }
            .maxByOrNull { it.length }
            ?.let { return cleanPokemonLatinName(it) }
        return Regex("""\b([A-ZÀ-Ö][a-zà-öø-ÿ']{2,22})\b""").findAll(window)
            .map { it.value }
            .firstOrNull { token ->
                val lower = token.lowercase()
                lower !in skip && !isNoisePokemonName(token)
            }
            ?.let { cleanPokemonLatinName(it) }
    }

    /**
     * First Title-case token in the card body that looks like a Pokémon name
     * (Smoliv, Noibat) — avoids latching onto attack phrases lower on the card.
     * Uses only the first lines of the main OCR (not the bottom strip with set codes).
     */
    private fun extractEarlyPokemonTitle(header: String): String? {
        // Drop trailing bottom-strip lines that are mostly numbers / set codes.
        val top = header.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { line ->
                collectorFraction.containsMatchIn(line) ||
                    pokemonFractionStrict.containsMatchIn(line) ||
                    pokemonBottomPair.containsMatchIn(line) ||
                    line.matches(Regex("""^[A-Z0-9\s/•·\-–—]{2,12}$"""))
            }
            .take(8)
        val skip = skipName + pokemonCategoryWords + falseOwnerFirst +
            setOf("altezza", "peso", "lunghezza", "emon", "kemon", "skemon")
        top.forEach { line ->
            if (isEvolveFromLine(line) || isNoisePokemonName(line)) return@forEach
            val cleaned = cleanPokemonLatinName(line) ?: return@forEach
            val tokens = cleaned.split(Regex("""\s+"""))
            if (tokens.size == 1) {
                val token = tokens[0]
                val lower = token.lowercase()
                // Skip ALL-CAPS set codes (DRI, ASC) and categories.
                if (token.length <= 4 && token.all { it.isUpperCase() }) return@forEach
                if (isKnownPokemonSet(token)) return@forEach
                if (lower !in skip && token.first().isUpperCase()) return cleaned
            }
            // "Muk del Team Rocket" / multi-word titles on their own line.
            if (tokens.size in 2..5 &&
                tokens[0].first().isUpperCase() &&
                lowerTokensOk(tokens, skip)
            ) {
                return cleaned
            }
        }
        return null
    }

    private fun lowerTokensOk(tokens: List<String>, skip: Set<String>): Boolean =
        tokens.none { it.lowercase() in skip || isNoisePokemonName(it) }

    private fun isPlausibleOwnerTitle(candidate: String): Boolean {
        val parts = candidate.split(Regex("""\s+di\s+""", RegexOption.IGNORE_CASE))
        if (parts.size != 2) return false
        val firstRaw = parts[0].trim().trimStart('\'', '`', '‘', '’')
        val secondRaw = parts[1].trim()
        if (firstRaw.length < 3 || secondRaw.length < 3) return false
        // Both sides must look Title-Case (real trainer Pokemon), not lowercase flavor.
        if (!firstRaw.first().isUpperCase() || !secondRaw.first().isUpperCase()) return false
        val first = firstRaw.lowercase().replace(Regex("""^[^a-zà-öø-ÿ]+"""), "")
        val second = secondRaw.lowercase()
        if (first in falseOwnerFirst || first.startsWith("costo") || first.startsWith("hal")) return false
        if (second in falseOwnerSecond) return false
        return !isNoisePokemonName(candidate)
    }

    private fun isPlausiblePokemonSet(raw: String): Boolean {
        val key = raw.trim().lowercase()
        if (key.length !in 2..5) return false
        if (key in falseSetCodes) return false
        return true
    }

    private fun isPlausibleMtgSet(raw: String): Boolean {
        val trimmed = raw.trim()
        val key = trimmed.lowercase()
        if (key.length !in 3..5) return false
        if (key in falseSetCodes) return false
        if (!key.all { it.isLetterOrDigit() }) return false
        // Set codes always include a letter (ISD, DFT, 2X2). Pure digits are set-size (`165`).
        if (!key.any { it.isLetter() }) return false
        // Printed set codes are ALL-CAPS on the card (FRA, DFT) — reject Title/lower crumbs.
        if (trimmed != trimmed.uppercase()) return false
        return true
    }

    private fun isKnownPokemonSet(raw: String): Boolean {
        val key = raw.trim().lowercase()
        if (key in falseSetCodes) return false
        return com.scantrader.app.data.catalog.PokemonSets.recognizedIds(key).isNotEmpty() ||
            com.scantrader.app.data.catalog.PokemonSets.isInternationalPrinted(key)
    }

    private fun extractLatinLineName(lines: List<String>): String? {
        val candidates = lines.filter { line ->
            val lower = line.lowercase()
            val minLength = if (line.any { it.code > 0x2E80 }) 1 else 3
            line.length in minLength..48 &&
                line.any { it.isLetter() } &&
                !isEvolveFromLine(line) &&
                !isNoisePokemonName(line) &&
                skipName.none { lower == it || lower.startsWith("$it ") || lower.contains(" $it") } &&
                !Regex("""(?i)^fase\s*\d""").containsMatchIn(line) &&
                !Regex("""(?i)^\d+\s*(?:hp|pv)\b""").containsMatchIn(line) &&
                !line.matches(Regex("""[\d\s/•·\-A-Z]{3,}""")) &&
                collectorFraction.find(line) == null &&
                mtgBottomLine.find(line) == null &&
                pokemonBottomPair.find(line) == null
        }
        return candidates.firstOrNull { !it.contains("HP", ignoreCase = true) }
            ?: candidates.firstOrNull()
    }

    /** MTG title lines — reject rules-text crumbs like `er. If` / `Exile target`. */
    private fun extractMtgTitle(lines: List<String>): String? {
        val noise = Regex(
            """(?i)\b(?:if|when|whenever|exile|target|creature|planeswalker|battlefield|""" +
                """return|under|control|mana|value|less|more|draw|discard|destroy|""" +
                """permanent|spell|ability|wizards|mtgstory|beginning|end|step|phase)\b""",
        )
        val candidates = lines.filter { line ->
            val lower = line.lowercase().trim()
            line.length in 3..48 &&
                line.any { it.isLetter() } &&
                !noise.containsMatchIn(line) &&
                !lower.startsWith("er.") &&
                !lower.startsWith("or ") &&
                skipName.none { lower == it || lower.startsWith("$it ") } &&
                mtgBottomLine.find(line) == null &&
                collectorFraction.find(line) == null &&
                line.count { it.isLetter() } >= 3
        }
        return cleanMtgNameGuess(candidates.firstOrNull()?.trim())
    }

    private fun isEvolveFromLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return false
        if (evolveFromLine.containsMatchIn(trimmed)) return true
        // OCR often drops the "Si" and glues: "Sievalveda Scorbunny" / "ve da Scorbunny"
        val lower = trimmed.lowercase()
        return lower.contains("evolve da") ||
            lower.contains("evolves from") ||
            lower.contains("sievolve") ||
            Regex("""(?i)\bve\s+da\b""").containsMatchIn(lower) ||
            Regex("""(?i)sie?valve\s*da""").containsMatchIn(lower)
    }

    private fun isNoisePokemonName(name: String): Boolean {
        val lower = name.trim().lowercase()
        if (lower in skipName) return true
        if (isEvolveFromLine(name)) return true
        // "ve da Scorbunny" / leftover evolve crumbs used as the whole name.
        if (Regex("""(?i)^(?:si\s*)?(?:e?volv\w*|ve)\s+da\b""").containsMatchIn(lower)) return true
        if (Regex("""(?i)\bda\s+[a-zà-öø-ÿ]{3,}$""").containsMatchIn(lower) &&
            lower.split(Regex("""\s+""")).size <= 4 &&
            (lower.contains("evolve") || lower.contains("sievolve") || lower.startsWith("ve "))
        ) {
            return true
        }
        // Category / dex lines: "N 0814 Pokemon Coniglio", "Pokemon Fregata".
        if (Regex("""(?i)^n[°.]?\s*\d""").containsMatchIn(lower)) return true
        if (Regex("""(?i)\bpok[eé]mon\s+[a-zà-ö]""").containsMatchIn(lower) &&
            !Regex("""(?i)\bpok[eé]mon\s+(center|breeder|fan|ranger|catcher|trainer)""").containsMatchIn(lower)
        ) {
            return true
        }
        return false
    }

    /**
     * Strip CJK noise / HP digits / collector / set codes stuck to Latin names
     * (e.g. `KilowattrelaKiasarn 120ヶ`, `Smoliv21`, `Noibat106`, `DRI Noibat`).
     */
    fun cleanPokemonLatinName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        if (isEvolveFromLine(raw) || isNoisePokemonName(raw)) return null
        var text = raw
            // Drop evolve-from crumbs glued into the title line.
            .replace(Regex("""(?i)^(?:si\s*)?(?:e?volv\w*|sievalve|ve)\s+da\s+"""), "")
            .replace(Regex("""[\u3040-\u30ff\u3400-\u9fff\uf900-\ufaff々ーヶ]+"""), " ")
            // Fractions and collector glued to name: Noibat106/217, Smoliv 21/182
            .replace(Regex("""\d{1,3}\s*/\s*\d{2,3}"""), " ")
            .replace(Regex("""(?i)\b(?:hp|pv)\s*\d{2,3}\b"""), " ")
            .replace(Regex("""(?i)\b\d{2,3}\s*(?:hp|pv)\b"""), " ")
            // Trailing/leading collector digits glued without slash: Smoliv21, 106Noibat
            .replace(Regex("""(?<=[A-Za-zÀ-ÖØ-öø-ÿ])\d{2,3}\b"""), " ")
            .replace(Regex("""\b\d{2,3}(?=[A-Za-zÀ-ÖØ-öø-ÿ])"""), " ")
            .replace(Regex("""\b\d{2,4}\b"""), " ")
            .replace(Regex("""[|•·]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (text.isBlank()) return null
        // Keep only Latin letters/spaces/apostrophe for Italian/English names.
        var latinOnly = text.replace(Regex("""[^A-Za-zÀ-ÖØ-öø-ÿ'\s-]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (latinOnly.length < 3) return null
        // Drop known set codes (DRI, ASC, …) and other ALL-CAPS 2–4 letter tokens.
        latinOnly = latinOnly.split(Regex("""\s+"""))
            .filter { token ->
                val upper = token.uppercase()
                when {
                    token.length in 2..4 && token.all { it.isLetter() } &&
                        token == upper && PokemonSets.recognizedIds(upper).isNotEmpty() -> false
                    token.length in 2..3 && token.all { it.isLetter() } &&
                        token == upper && upper in setOf("HP", "PV", "GX", "EX", "V", "VMAX", "VSTAR") -> false
                    else -> true
                }
            }
            .joinToString(" ")
            .trim()
        if (latinOnly.length < 3) return null
        val lower = latinOnly.lowercase()
        if (skipName.any { lower == it || lower.startsWith("$it ") }) return null
        if (isNoisePokemonName(latinOnly)) return null
        if (looksLikeAbilityOrAttackName(latinOnly)) return null
        // Drop tiny garbage tokens.
        val tokens = latinOnly.split(Regex("""\s+""")).filter { it.length >= 2 }
        if (tokens.isEmpty()) return null
        val cleaned = tokens.joinToString(" ").trim().takeIf { it.length >= 3 } ?: return null
        return cleaned.takeUnless { looksLikeAbilityOrAttackName(it) }
    }

    fun isLatinDominant(text: String): Boolean {
        val latin = text.count { it.isLetter() && it.code < 0x250 }
        val cjk = text.count { it.code > 0x2E80 }
        return latin >= 8 && latin >= cjk * 2
    }

    /** Pick the most repeated Title-case token (e.g. Kadabra on Italian Base). */
    fun mostRepeatedTitle(text: String): String? {
        // Ignore evolve-from lines so the parent species does not win the title vote.
        val filtered = text.lines().filterNot { isEvolveFromLine(it) }.joinToString("\n")
        val counts = linkedMapOf<String, Int>()
        Regex("""\b([A-ZÀ-Ö][a-zà-öø-ÿ]{3,})\b""").findAll(filtered).forEach { match ->
            val token = match.value
            val lower = token.lowercase()
            if (lower in skipName || lower in falseOwnerFirst || isNoisePokemonName(token)) return@forEach
            counts[token] = (counts[token] ?: 0) + 1
        }
        return counts.entries
            .filter { it.value >= 2 }
            .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key.length })
            ?.key
    }

    private fun preferSources(bottom: String?, raw: String): List<String> =
        listOfNotNull(bottom?.takeIf { it.isNotBlank() }, raw)

    private fun normalizeCollector(value: String): String {
        val trimmed = value.trim()
        val stripped = trimmed.trimStart('0')
        return stripped.ifBlank { trimmed }
    }

    fun normalizeSet(value: String): String = value.trim().lowercase()

    /** OCR often confuses O/0 and I/1/l in set codes — return lookup variants. */
    fun setCodeCandidates(raw: String?): List<String> {
        val base = raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val out = linkedSetOf(base)
        out += base.replace('0', 'o')
        out += base.replace('o', '0')
        out += base.replace('1', 'i')
        out += base.replace('i', '1')
        out += base.replace('l', 'i')
        out += base.replace('5', 's')
        out += base.replace('s', '5')
        // Common ASC misreads (Ascended Heroes).
        if (base in setOf("uor", "asc", "aso", "as0", "a5c")) {
            out += "asc"
        }
        return out.filter { it.length in 2..5 }.distinct()
    }

    fun collectorCandidates(raw: String?): List<String> {
        val base = raw?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val stripped = base.trimStart('0').ifBlank { base }
        return listOf(base, stripped).distinct()
    }

    /**
     * Enough identity from strips to skip full-card OCR (and heavy gallery) on auto-scan.
     * Both games: collector + set; name is optional.
     * MTG set must look like a real code (has a letter) — never `165` from `74/165`.
     */
    fun identityReadyForFastPath(parsed: ParsedCardText): Boolean {
        val cn = parsed.collectorNumber?.trim().orEmpty()
        val set = parsed.setCode?.trim().orEmpty()
        if (cn.isEmpty() || set.isEmpty()) return false
        if (!set.any { it.isLetter() }) return false
        return true
    }

    /** Drop OCR trailing mana/noise digits: `Trasformazione Raccapricciante 2` → title only. */
    fun cleanMtgNameGuess(name: String?): String? {
        val trimmed = name?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return trimmed
            .replace(Regex("""\s+\d{1,3}$"""), "")
            .trim()
            .takeIf { it.length >= 3 } ?: trimmed
    }
}

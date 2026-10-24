package com.scantrader.app.scan

import com.scantrader.app.data.catalog.CardIdentity
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
        """(?i)\b([A-Z0-9]{2,5})\s*[•·●\*+\\.\,/\|\-–—]?\s*(EN|IT|DE|FR|ES|JP|JA|PH|KO)\b""",
    )

    /**
     * OCR often reads leading zero as O: `RO162`, `R O113`, `O162`.
     * Also glues lang: `FRAIT`, `FRA*EN`.
     */
    /** `RO162` / `R O65`. Horizontal space only — a newline must not glue `HOB*EN` to `O65`. */
    private val mtgOcrZeroCollector = Regex("""(?i)\bR?[^\S\n]*O(\d{2,4})\b""")
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

    /**
     * Collector fraction with an optional set/regulation prefix glued on:
     * `006/128`, `o06/128`, `30C006/128`, `D30C006/128`.
     * The prefix may include digits (`30C`), so it is not only `[A-Za-z]`.
     */
    private val pokemonPrefixedFraction = Regex(
        """(?i)([A-Za-z0-9]{0,8}?)(\d{2,3})\s*/\s*(\d{2,3})\b""",
    )

    /** Printed alphanumeric set code standing on its own: `30C`, `D 30C`. */
    private val pokemonAlnumSet = Regex(
        """(?i)(?<![A-Za-z0-9])([0-9]{2}[A-Za-z])(?![A-Za-z0-9])""",
    )

    private val italianOwnerName = Regex(
        """(?i)\b([A-ZÀ-ÖØ-öø-ÿ][A-Za-zÀ-ÖØ-öø-ÿ]{2,})\s+di\s+([A-ZÀ-ÖØ-öø-ÿ][A-Za-zÀ-ÖØ-öø-ÿ]{2,})\b""",
    )
    private val englishOwnerName = Regex(
        """(?i)\b([A-Z][A-Za-z]{2,}'s)\s+([A-Z][A-Za-z]{2,})\b""",
    )

    /**
     * Multi-word trainer / Team Rocket titles: "Maxus del Team Rocket", "Muk della Squadra".
     * The particle is del/della/dello/dei/delle — not the owner "di" used on creature cards.
     */
    private val italianGroupTitle = Regex(
        """(?i)\b([A-ZÀ-ÖØ-öø-ÿ][\p{L}'’-]{2,})\s+(del|della|dello|dei|delle)\s+""" +
            """((?:Team\s+|Squadra\s+)?[A-ZÀ-ÖØ-öø-ÿ][\p{L}'’-]{2,}(?:\s+[A-ZÀ-ÖØ-öø-ÿ][\p{L}'’-]{2,})?)\b""",
    )

    /** Printed category label on Trainer, Supporter, Item and Stadium cards. Not a title. */
    private val trainerLayoutWords = setOf(
        "aiuto", "strumento", "oggetto", "stadio", "allenatore",
        "supporter", "trainer", "stadium", "item",
        "unterstutzer", "stadion", "dresseur", "objet", "stade",
        "partidario", "apoyo", "objeto", "estadio", "apoiador",
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
        // Illustrator crumbs next to the fraction: "Kurata So", "Karute Se".
        "se", "so",
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
        // Trainer / Supporter / Item / Stadium category labels, not the card title.
        // Italian "Aiuto" is Supporter; OCR often reads it as AIUtO.
        "aiuto", "strumento", "oggetto", "stadio", "allenatore",
        "unterstutzer", "stadion", "dresseur", "objet", "stade",
        "partidario", "apoyo", "objeto", "estadio", "apoiador",
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
        if (game == Game.MTG) {
            return parseMtg(rawText, bottomPriorityText, namePriorityText)
        }
        // Pokémon: bottom first, full card as weak fallback.
        val bottomNormalized = bottomPriorityText
        val identityMerged = listOfNotNull(bottomPriorityText, rawText)
            .filter { it.isNotBlank() }
            .joinToString("\n")
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

    /**
     * MTG identity comes from the bottom band only.
     * Copyright year ranges are removed before any set/number guess, and a scored
     * hit (modern line or `NN/NNN`) is used as-is — a null set does not fall through.
     */
    private fun parseMtg(
        rawText: String,
        bottomPriorityText: String?,
        namePriorityText: String?,
    ): ParsedCardText {
        val top = namePriorityText?.takeIf { it.isNotBlank() }
        val bottom = bottomPriorityText?.takeIf { it.isNotBlank() }
        // A landscape crop puts the collector line (`O65`, `HOB*EN`) in the title band.
        val source = if (top != null && CardOrientation.collectorInTitleBand(top)) {
            listOfNotNull(top, bottom, rawText.takeIf { it.isNotBlank() }).joinToString("\n")
        } else {
            bottom ?: rawText
        }
        val prepared = prepareMtgBottom(source)
        val name = correctMtgName(
            extractName(
                primaryText = namePriorityText?.takeIf { it.isNotBlank() } ?: rawText,
                fallbackText = rawText.takeIf {
                    !namePriorityText.isNullOrBlank() && namePriorityText != rawText
                },
                game = Game.MTG,
            ),
            rawText,
        )
        val hit = bestMtgBottom(prepared)
        if (hit != null) {
            return ParsedCardText(
                nameGuess = name,
                collectorNumber = hit.collector,
                setCode = hit.setCode,
            )
        }
        return ParsedCardText(
            nameGuess = name,
            collectorNumber = extractCollector(prepared, prepared, Game.MTG),
            setCode = extractSet(prepared, prepared, Game.MTG),
        )
    }

    /** `1993-2008` / `1993-20O8`, including when OCR glues a digit in front (`01993-20O8`). */
    private val copyrightYearRange = Regex(
        """(?:19|20)\d{2}\s*[-–—]\s*[0-9OolI]{4}""",
    )

    /**
     * Copyright / illustrator line. `©2026 Wizards of the Coast`, OCR
     * `wat 036 Waarda of the Coast`, `&2026 Waards`. Not a collector.
     * A line that also has `74/165` or `N3/150` is an old frame and is kept.
     */
    private val copyrightCue = Regex(
        """(?i)wizards|wa+r?da?s|coast|coas[et]|©|(?:wat|wiz)\s+\d{2,4}\b|(?:^|[\s&])(?:19|20)\d{2}\b""",
    )

    /**
     * Rarity plus a zero-padded collector: `C0080`, OCR `CO08O` / `COO8O`.
     * The digit run must be four characters and start with 0/O so `2026` is not a collector.
     */
    private val mtgPaddedCollector = Regex(
        """(?i)(?<![A-Za-z0-9])[CURM]?([0OolI][0-9OolI]{3})(?![A-Za-z0-9])""",
    )

    /** Fraction tokens only — digit fixes must not rewrite set words. */
    private val ocrFractionToken = Regex(
        """(?<![\d+])([0-9OolINn|]{1,4})\s*/\s*([0-9OolINn|]{2,4})""",
    )

    fun prepareMtgBottom(text: String): String {
        val normalized = normalizeMtgBottomOcr(stripMtgCopyright(text))
        val withoutYears = copyrightYearRange.replace(normalized, " ")
        return ocrFractionToken.replace(withoutYears) { match ->
            "${fixOcrDigits(match.groupValues[1])}/${fixOcrDigits(match.groupValues[2])}"
        }
    }

    /**
     * Drop copyright and illustrator lines (`Wizards of the Coast`, `©2026`).
     * An old-frame `74/165` or OCR `N3/150` on that same line stays.
     */
    fun stripMtgCopyright(text: String): String =
        text.lines().filterNot { line ->
            copyrightCue.containsMatchIn(line) && !hasCollectorFraction(line)
        }.joinToString("\n")

    private fun hasCollectorFraction(line: String): Boolean =
        Regex("""(?<![+\d])[0-9OolINn|]{1,4}\s*/\s*[0-9OolINn|]{2,4}""").containsMatchIn(line)

    /** O/o→0, l/I/|→1, N/n→8. Used inside `NN/NNN` tokens (`N3/150` → `83/150`). */
    fun fixOcrDigits(token: String): String = buildString(token.length) {
        token.forEach { ch ->
            append(
                when (ch) {
                    'O', 'o' -> '0'
                    'l', 'I', '|' -> '1'
                    'N', 'n' -> '8'
                    else -> ch
                },
            )
        }
    }

    private data class MtgBottomHit(
        val collector: String,
        val setCode: String?,
        val score: Int,
    )

    /**
     * Every collector pattern in the bottom band, best score wins.
     * Modern `0181 • DFT • IT` outranks an old `83/150` when both are present.
     */
    private fun bestMtgBottom(prepared: String): MtgBottomHit? {
        if (prepared.isBlank()) return null
        val hits = mutableListOf<MtgBottomHit>()
        mtgBottomLine.findAll(prepared).forEach { match ->
            val cn = normalizeCollector(match.groupValues[1])
            val setRaw = match.groupValues[2]
            if (!isPlausibleCollector(cn) || !isPlausibleMtgSet(setRaw)) return@forEach
            val lang = match.groupValues[3]
            hits += MtgBottomHit(cn, normalizeSet(setRaw), if (lang.isNotBlank()) 120 else 100)
        }
        mtgSpaced.findAll(prepared).forEach { match ->
            val cn = normalizeCollector(match.groupValues[1])
            val setRaw = match.groupValues[2]
            if (!isPlausibleCollector(cn) || !isPlausibleMtgSet(setRaw)) return@forEach
            hits += MtgBottomHit(cn, normalizeSet(setRaw), 110)
        }
        mtgNumberNearSet.findAll(prepared).forEach { match ->
            val cn = normalizeCollector(match.groupValues[1].ifBlank { match.groupValues[4] })
            val setRaw = match.groupValues[2].ifBlank { match.groupValues[3] }
            if (cn.length < 2 || !isPlausibleCollector(cn) || !isPlausibleMtgSet(setRaw)) return@forEach
            hits += MtgBottomHit(cn, normalizeSet(setRaw), 90)
        }
        mtgOldCollectorFraction.findAll(prepared).forEach { match ->
            val cnRaw = match.groupValues[1]
            val cn = cnRaw.toIntOrNull() ?: return@forEach
            val total = match.groupValues[2].toIntOrNull() ?: return@forEach
            if (total < 40 || cn !in 1 until total || cn >= 1900) return@forEach
            val bonus = if (total in 50..450) 15 else 0
            hits += MtgBottomHit(normalizeCollector(cnRaw), null, 80 + bonus)
        }
        val paddedSet = mtgSetNearLang.findAll(prepared)
            .mapNotNull { it.groupValues.getOrNull(1)?.takeIf { set -> isPlausibleMtgSet(set) } }
            .firstOrNull()
            ?.let { normalizeSet(it) }
        mtgPaddedCollector.findAll(prepared).forEach { match ->
            val fixed = fixOcrDigits(match.groupValues[1])
            if (fixed.length != 4 || fixed[0] != '0' || !fixed.all { it.isDigit() }) return@forEach
            val cn = normalizeCollector(fixed)
            if (!isPlausibleCollector(cn)) return@forEach
            hits += MtgBottomHit(cn, paddedSet, if (paddedSet != null) 115 else 75)
        }
        // `O65` is normalized to `065` before this runs. A 3-digit pad is still
        // the collector; copyright `036` was already stripped.
        Regex("""(?m)(?<![\d/+])(0\d{2})\b""").findAll(prepared).forEach { match ->
            val cn = normalizeCollector(match.groupValues[1])
            if (!isPlausibleCollector(cn)) return@forEach
            hits += MtgBottomHit(cn, paddedSet, if (paddedSet != null) 85 else 70)
        }
        return hits.maxByOrNull { it.score }
    }

    /**
     * Top-line OCR often misses one letter (`Tagliaerhacce`) while the rules text
     * has the real title. Prefer that window when the edit distance is small.
     * Distance 0 keeps the top line (fast path, where the full text is the title).
     */
    fun correctMtgName(name: String?, fullText: String): String? {
        val base = cleanMtgNameGuess(name) ?: return null
        if (fullText.isBlank()) return base
        val limit = when {
            base.length >= 16 -> 3
            base.length >= 8 -> 2
            else -> 1
        }
        val words = base.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.isEmpty()) return base
        val tokens = Regex("""[A-Za-zÀ-ÖØ-öø-ÿ']{3,}""")
            .findAll(fullText)
            .map { it.value }
            .toList()
        if (tokens.size < words.size) return base
        var best: String? = null
        var bestDist = limit + 1
        val baseKey = base.lowercase()
        for (i in 0..tokens.size - words.size) {
            val window = tokens.subList(i, i + words.size).joinToString(" ")
            val dist = editDistance(baseKey, window.lowercase())
            if (dist !in 1..limit || dist >= bestDist) continue
            val cleaned = cleanMtgNameGuess(window) ?: continue
            if (cleaned.lowercase() in skipName) continue
            best = cleaned
            bestDist = dist
        }
        return best ?: base
    }

    internal fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > 4) return 5
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > 4) return rowMin
            val swap = prev
            prev = curr
            curr = swap
        }
        return prev[b.length]
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
                // `D30C006/128` / `o06/128` — leading zeros and a glued set code.
                bestPokemonNumberLine(source)?.let { return it.collector }
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
                        n.takeIf { it.length >= 2 && isPlausibleCollector(it) && isPlausibleMtgSet(set) }
                    }
                    .maxByOrNull { it.length }
                    ?.let { return normalizeCollector(it) }
                mtgPaddedCollector.findAll(source)
                    .mapNotNull { match ->
                        val fixed = fixOcrDigits(match.groupValues[1])
                        fixed.takeIf { it.length == 4 && it[0] == '0' && it.all { ch -> ch.isDigit() } }
                    }
                    .map { normalizeCollector(it) }
                    .firstOrNull { isPlausibleCollector(it) }
                    ?.let { return it }
                // Bare 4-digit padded collector (`0028`, `0080`). A 3-digit `036` on the
                // copyright line is not a collector.
                Regex("""(?m)(?<![\d/+])(0\d{3})\b""").findAll(source)
                    .map { normalizeCollector(it.groupValues[1]) }
                    .firstOrNull { isPlausibleCollector(it) }
                    ?.let { return it }
                Regex("""(?m)(?<![\d/+])(0\d{2})\b""").findAll(source)
                    .map { normalizeCollector(it.groupValues[1]) }
                    .firstOrNull { isPlausibleCollector(it) }
                    ?.let { return it }
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
                    pokemonSetOrNull(it)?.let { code -> return code }
                }
                bestPokemonNumberLine(source)?.setCode?.let { return it }
                pokemonAlnumSet.find(source)?.groupValues?.getOrNull(1)?.let { token ->
                    pokemonSetOrNull(token)?.takeIf { isKnownPokemonSet(it) }?.let { return it }
                }
            }
            preferSources(bottom, raw).forEach { source ->
                pokemonGluedSetFraction.find(source)?.let { match ->
                    val letters = match.groupValues[1]
                    val digits = match.groupValues[2]
                    // Only trust glued letters when collector looks real (2–3 digits).
                    if (digits.length in 2..3 && letters.length in 2..4) {
                        pokemonSetOrNull(letters)?.let { return it }
                    }
                }
            }
            preferSources(bottom, raw).forEach { source ->
                val nearFraction = Regex(
                    """(?i)\b([A-Z]{2,5})\b(?=[^\n]{0,12}\d{2,3}\s*/\s*\d{1,3}\b)""",
                ).find(source)?.groupValues?.getOrNull(1)
                // Near-fraction is a weak signal — only accept known printed set codes.
                if (nearFraction != null && (isKnownPokemonSet(nearFraction) ||
                        PokemonSets.canonicalizePrinted(nearFraction) != null)
                ) {
                    pokemonSetOrNull(nearFraction)?.let { return it }
                }
                // Set on the next line after the fraction: `004/165\nMEW`
                Regex("""(?i)\d{2,3}[A-Za-z]?\s*/\s*\d{2,3}\s*[\n|]+\s*([A-Z]{2,5})\b""")
                    .find(source)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.takeIf { isKnownPokemonSet(it) || PokemonSets.canonicalizePrinted(it) != null }
                    ?.let { token -> pokemonSetOrNull(token)?.let { return it } }
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
            bestPokemonNumberLine(source)?.let { return it.count }
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
        val nameLines = lines
            .filterNot { isEvolveFromLine(it) || isStageNoiseLine(it) }
            .map { it.replace(Regex("""(?<=\p{L})\d+\b"""), "") }
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
        italianGroupTitle.findAll(header)
            .map { it.value }
            .filter { isPlausibleGroupTitle(it) }
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

    private fun isPlausibleGroupTitle(candidate: String): Boolean {
        val parts = candidate.split(Regex("""\s+(?:del|della|dello|dei|delle)\s+""", RegexOption.IGNORE_CASE))
        if (parts.size != 2) return false
        val first = parts[0].trim()
        val second = parts[1].trim()
        if (first.length < 3 || second.length < 3) return false
        if (!first.first().isUpperCase() || !second.first().isUpperCase()) return false
        if (isTrainerLayoutWord(first) || isTrainerLayoutWord(second)) return false
        if (first.lowercase() in falseOwnerFirst) return false
        return !isNoisePokemonName(candidate)
    }

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
        // `20O8` is a copyright year, not a set. 2X2 and 10E stay valid (two digits).
        val digitLike = key.map { ch ->
            when (ch) {
                'o' -> '0'
                'i', 'l' -> '1'
                else -> ch
            }
        }.joinToString("")
        if (digitLike.count { it.isDigit() } >= 3) return false
        return true
    }

    /** Collector 1..999. Years (`1993`) are not collector numbers. */
    private fun isPlausibleCollector(raw: String): Boolean {
        val digits = raw.trim().trimStart('0').takeWhile { it.isDigit() }
        val n = digits.toIntOrNull() ?: return false
        return n in 1..999
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

    /** `HOB*EN` / `DFT • IT` is the collector line, not the card title. */
    private fun isSetAndLangLine(line: String): Boolean {
        val flat = line.trim().replace(Regex("""[•·●*+.,/|\\-–—]"""), " ")
            .replace(Regex("""\s+"""), " ")
        if (Regex("""(?i)^[A-Z0-9]{2,5}\s+(?:EN|IT|DE|FR|ES|JP|JA|PH|KO)$""").matches(flat)) {
            return true
        }
        if (!mtgSetNearLang.containsMatchIn(line)) return false
        val rest = mtgSetNearLang.replace(line, " ")
        return !Regex("""[A-Za-z]{4,}""").containsMatchIn(rest)
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
            val cleaned = cleanMtgNameGuess(line)
            line.length in 3..48 &&
                line.any { it.isLetter() } &&
                !noise.containsMatchIn(line) &&
                !lower.startsWith("er.") &&
                !lower.startsWith("or ") &&
                skipName.none { lower == it || lower.startsWith("$it ") } &&
                mtgBottomLine.find(line) == null &&
                collectorFraction.find(line) == null &&
                line.count { it.isLetter() } >= 3 &&
                !isSetAndLangLine(line) &&
                !CardOrientation.implausibleTitle(cleaned)
        }
        return cleanMtgNameGuess(candidates.firstOrNull()?.trim())
    }

    private fun isStageNoiseLine(line: String): Boolean = CardIdentity.isStageNoise(line)

    private fun isEvolveFromLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return false
        if (evolveFromLine.containsMatchIn(trimmed)) return true
        // OCR often drops the "Si" and glues: "Sievalveda Scorbunny" / "ve da Scorbunny"
        // or mangles the verb: Sievoheda, Slevolve, Stevolve, Sierolve, Sieoleda.
        val lower = trimmed.lowercase()
        return lower.contains("evolve da") ||
            lower.contains("evolves from") ||
            lower.contains("sievolve") ||
            Regex("""(?i)\bve\s+da\b""").containsMatchIn(lower) ||
            Regex("""(?i)sie?valve\s*da""").containsMatchIn(lower) ||
            Regex(
                """(?i)\b(?:s[ilt]?e?vol\w{0,8}|sievohe\w{0,6}|sierol\w{0,6}|sieole\w{0,6})\s*da\b""",
            ).containsMatchIn(lower)
    }

    private fun isNoisePokemonName(name: String): Boolean {
        val lower = name.trim().lowercase()
        if (isTrainerLayoutWord(name)) return true
        if (lower in skipName) return true
        if (isStageNoiseLine(name)) return true
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
            // "KilowattreldiKissara" → "Kilowattrel di Kissara"
            .replace(Regex("""(?i)(?<=[A-Za-zÀ-ÖØ-öø-ÿ])di(?=[A-ZÀ-Ö])"""), " di ")
            // Drop evolve-from crumbs glued into the title line.
            .replace(Regex("""(?i)^(?:si\s*)?(?:e?volv\w*|sievalve|ve)\s+da\s+"""), "")
            .replace(Regex("""[\u3040-\u30ff\u3400-\u9fff\uf900-\ufaff々ーヶ]+"""), " ")
            // Fractions and collector glued to name: Noibat106/217, Smoliv 21/182
            .replace(Regex("""\d{1,3}\s*/\s*\d{2,3}"""), " ")
            .replace(Regex("""(?i)\b(?:hp|pv|ps)\s*\d{1,3}\b"""), " ")
            .replace(Regex("""(?i)\b\d{1,3}\s*(?:hp|pv|ps)\b"""), " ")
            .replace(Regex("""(?i)(?<=\p{L})(?:hp|pv|ps)\s*\d{1,3}\b"""), " ")
            // Trailing/leading collector digits glued without slash: Smoliv21, Kissara2, 106Noibat
            .replace(Regex("""(?<=[A-Za-zÀ-ÖØ-öø-ÿ])\d{1,3}\b"""), " ")
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
                        token == upper && upper in setOf("HP", "PV", "PS", "GX", "EX", "V", "VMAX", "VSTAR") -> false
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
        // Drop tiny garbage tokens and stage crumbs (FASE, FASEl) stuck on the title.
        val tokens = latinOnly.split(Regex("""\s+"""))
            .filter { it.length >= 2 && !isStageNoiseLine(it) }
        if (tokens.isEmpty()) return null
        val cleaned = CardIdentity.repairOwnerName(tokens.joinToString(" "))
            .trim()
            .takeIf { it.length >= 3 } ?: return null
        return cleaned.takeUnless { looksLikeAbilityOrAttackName(it) || isStageNoiseLine(it) }
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

    private data class PokemonNumberLine(
        val setCode: String?,
        val collector: String,
        val count: Int,
    )

    private fun pokemonNumberLines(source: String): List<PokemonNumberLine> {
        val out = mutableListOf<PokemonNumberLine>()
        for (match in pokemonPrefixedFraction.findAll(source)) {
            val count = match.groupValues[3].toIntOrNull() ?: continue
            if (count !in 40..300) continue
            val collector = normalizeCollector(match.groupValues[2])
            val cn = collector.toIntOrNull() ?: continue
            if (cn !in 1 until count) continue
            out += PokemonNumberLine(
                setCode = setCodeFromFractionPrefix(match.groupValues[1]),
                collector = collector,
                count = count,
            )
        }
        return out
    }

    private fun bestPokemonNumberLine(source: String): PokemonNumberLine? =
        pokemonNumberLines(source).maxWithOrNull(
            compareBy<PokemonNumberLine>({ it.setCode != null }, { it.collector.length }),
        )

    /**
     * `D30C` / `J30C` keep the set `30C`. A single leading letter is the regulation
     * mark or a set-symbol misread. `ASC` stays `asc`. A lone `o` or `Se` is not a set.
     */
    private fun setCodeFromFractionPrefix(prefix: String): String? {
        val compact = prefix.filter { it.isLetterOrDigit() }
        if (compact.isEmpty()) return null
        val candidates = linkedSetOf(compact)
        if (compact.length >= 3 && compact.first().isLetter() && compact[1].isDigit()) {
            candidates += compact.drop(1)
        }
        Regex("""(?i)(\d{2}[a-z]{1,2})""").find(compact)?.value?.let { candidates += it }
        for (token in candidates) {
            val known = PokemonSets.canonicalizePrinted(token)
                ?: token.takeIf { isKnownPokemonSet(it) }
            if (known != null && known.lowercase() !in falseSetCodes) return normalizeSet(known)
        }
        return null
    }

    private fun preferSources(bottom: String?, raw: String): List<String> =
        listOfNotNull(bottom?.takeIf { it.isNotBlank() }, raw)

    private fun normalizeCollector(value: String): String {
        val trimmed = value.trim()
        val stripped = trimmed.trimStart('0')
        return stripped.ifBlank { trimmed }
    }

    fun normalizeSet(value: String): String = value.trim().lowercase()

    /**
     * Known printed code when OCR glued a crumb (`asch`/`dasc`/`oasc` → `asc`).
     * Falls back to the raw token when it is a plausible set but not in the list.
     */
    private fun pokemonSetOrNull(raw: String): String? {
        PokemonSets.canonicalizePrinted(raw)?.let { return it }
        if (isPlausiblePokemonSet(raw)) return normalizeSet(raw)
        return null
    }

    /** OCR often confuses O/0 and I/1/l in set codes — return lookup variants. */
    fun setCodeCandidates(raw: String?): List<String> {
        val base = raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val out = linkedSetOf(base)
        PokemonSets.canonicalizePrinted(base)?.let { out += it }
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
     * True when [name] is only the category label (Aiuto, Supporter, Item, Stadium, …).
     * OCR case such as `AIUtO` still counts.
     */
    fun isTrainerLayoutWord(name: String?): Boolean {
        val folded = foldLetters(name) ?: return false
        return folded in trainerLayoutWords
    }

    /**
     * Trainer / Supporter / Item / Stadium cards print a category word in the title band.
     * Their rules and collector line are not in the creature-card strips.
     */
    fun looksLikeTrainerLayout(text: String): Boolean {
        if (text.isBlank()) return false
        return text.split(Regex("""[^\p{L}]+""")).any { isTrainerLayoutWord(it) }
    }

    /**
     * Stable autoscan key for a trainer title when the collector number was not read.
     * "Maxus del Team Rocket" counts; a one-word crumb or "Aiuto" does not.
     */
    fun trainerStabilityName(name: String?): String? {
        val raw = name?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (isTrainerLayoutWord(raw) || CardIdentity.isStageNoise(raw)) return null
        if (!Regex("""(?i)\b(?:del|della|dello|dei|delle)\b""").containsMatchIn(raw)) return null
        val tokens = CardIdentity.normalizeName(raw).split(" ").filter { it.length >= 3 }
        if (tokens.size < 2) return null
        return tokens.joinToString(" ")
    }

    /**
     * Enough identity from strips to skip full-card OCR (and heavy gallery) on auto-scan.
     * Both games: collector + set; name is optional.
     * MTG set must look like a real code (has a letter) — never `165` from `74/165`.
     * Trainer layouts are not ready: the category word, rules, and bottom number still need a pass.
     * [sourceText] is the strip OCR. A parsed title can already be the real name while the
     * strip still contains "Aiuto".
     */
    fun identityReadyForFastPath(parsed: ParsedCardText, sourceText: String = ""): Boolean {
        if (looksLikeTrainerLayout(sourceText) || isTrainerLayoutWord(parsed.nameGuess)) return false
        val cn = parsed.collectorNumber?.trim().orEmpty()
        val set = parsed.setCode?.trim().orEmpty()
        if (cn.isEmpty() || set.isEmpty()) return false
        if (!set.any { it.isLetter() }) return false
        return true
    }

    private fun foldLetters(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val decomposed = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
        return decomposed.lowercase().replace(Regex("""[^a-z]"""), "").takeIf { it.isNotEmpty() }
    }

    /**
     * Names Scryfall should try, best first.
     * The cleaned title comes before a copy with one more trailing mana token removed,
     * so a failed exact/fuzzy query can be retried shorter.
     */
    fun mtgNameAttempts(name: String?): List<String> {
        val cleaned = cleanMtgNameGuess(name) ?: return emptyList()
        if (CardOrientation.implausibleTitle(cleaned)) return emptyList()
        val attempts = linkedSetOf(cleaned)
        val tokens = cleaned.split(Regex("""\s+"""))
        if (tokens.size >= 2 && isManaCostToken(tokens.last())) {
            val shorter = tokens.dropLast(1).joinToString(" ").trim()
            if (shorter.length >= 3) attempts += shorter
        }
        return attempts.toList()
    }

    private const val NAME_EDGE = "\"'“”‘’«»`´.,;:!?¡¿*_•·●★☆|/\\[]()<>"

    /**
     * Drop mana-cost OCR stuck to the title bar:
     * `Llanowar Elves G`, `Shivan Dragon 4RR`, `Counterspell UU`, `Pioppo Tagliaerbacce 2G`,
     * `{2}{G}`, leading digits.
     */
    fun cleanMtgNameGuess(name: String?): String? {
        var text = name?.trim()?.takeIf { it.isNotBlank() } ?: return null
        text = text.replace(Regex("""[`´‘’ʼ]"""), "'")
        text = text.trim { it.isWhitespace() || it in NAME_EDGE }
        // Border OCR glues a quote or "l'" / "1'" onto the title.
        text = text.replace(Regex("""(?i)^(?:[ld]|1|i)'+(?=\p{L})"""), "")
        text = text.trim { it.isWhitespace() || it in NAME_EDGE }
        text = text.replace(Regex("""\{[^}]{0,8}\}"""), " ")
        text = text.replace(Regex("""[•·●★☆*|/\\\[\]()<>]+"""), " ")
        text = text.replace(Regex("""\s+"""), " ").trim()
        if (text.isEmpty()) return null
        val tokens = text.split(Regex("""\s+""")).toMutableList()
        while (tokens.size > 1 && isManaCostToken(tokens.last())) tokens.removeAt(tokens.lastIndex)
        while (tokens.size > 1 && isManaCostToken(tokens.first())) tokens.removeAt(0)
        while (tokens.size > 1 && isBorderJunkToken(tokens.first())) tokens.removeAt(0)
        if (tokens.isEmpty() || tokens.all { isManaCostToken(it) }) return null
        tokens[tokens.lastIndex] = stripGluedManaSuffix(tokens.last())
        if (tokens.last().isEmpty()) tokens.removeAt(tokens.lastIndex)
        while (tokens.size > 1 && (tokens.last().isEmpty() || isManaCostToken(tokens.last()))) {
            tokens.removeAt(tokens.lastIndex)
        }
        val cleaned = tokens.joinToString(" ").trim()
        return cleaned.takeIf { it.length >= 3 && it.any { ch -> ch.isLetter() } }
    }

    /**
     * `{G}`, `4RR`, `UU`, `2G`, `G`, `X`, `12`.
     * A normal word such as `Bug` or `Ring` is not a cost: letter-only costs are
     * a single pip or an ALL-CAPS run (`UU`, `WUBRG`).
     */
    /**
     * A 1–2 character crumb from the card border: `l`, `l'`, `1'`, `'`.
     * A real two-letter word (`Go`, `Of`) stays.
     */
    internal fun isBorderJunkToken(token: String): Boolean {
        val core = token.trim { it.isWhitespace() || it in "'\".,;:" }
        if (core.isEmpty()) return true
        if (core.length <= 2 && core.any { !it.isLetter() }) return true
        return core.matches(Regex("""(?i)^[ldi]'?$"""))
    }

    internal fun isManaCostToken(token: String): Boolean {
        val core = token.trim().trim('{', '}', '.', ',', ';', ':', '*')
        if (core.isEmpty()) return false
        if (core.all { it.isDigit() }) return core.length in 1..4
        if (core.matches(Regex("""\d{1,2}[WUBRGCwubrgcXx]+"""))) return true
        if (core.length == 1 && core[0] in "WUBRGCwubrgcXx") return true
        return core.length in 2..6 && core.all { it in "WUBRGCX" }
    }

    /** `ElvesG`, `Dragon4RR`, `Tagliaerbacce2G`, `CounterspellUU`, `Name2`. */
    private fun stripGluedManaSuffix(token: String): String {
        val stripped = token.replace(
            Regex("""(?<=\p{L})(?:\d{1,2}[WUBRGCwubrgc]{1,4}|[WUBRGC]{2,4}|(?<=[a-zà-öø-ÿ])[WUBRGC]|\d{1,3})$"""),
            "",
        )
        return stripped.ifBlank { token }.trim()
    }
}

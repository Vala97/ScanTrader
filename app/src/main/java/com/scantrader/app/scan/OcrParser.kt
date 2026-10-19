package com.scantrader.app.scan

import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.ParsedCardText

object OcrParser {
    private val collectorFraction = Regex("""\b(\d{1,4}[A-Za-z]?)\s*/\s*\d{1,4}\b""")

    /** Modern frame: `0181 • DFT • IT` or `181 - BLB - EN` */
    private val mtgBottomLine = Regex(
        """(?i)(\d{1,4}[A-Za-z]?)\s*[•·●\.\,/\|\-–—]\s*([A-Z0-9]{2,5})\s*(?:[•·●\.\,/\|\-–—]\s*)?(EN|IT|DE|FR|ES|JP|JA|PH|KO|ZHS|ZHT)?\b""",
    )

    /** Number and set on one line without separators: `0181 DFT IT` */
    private val mtgSpaced = Regex(
        """(?i)\b(\d{1,4}[A-Za-z]?)\s+([A-Z]{2,5})\s+(EN|IT|DE|FR|ES|JP|JA|PH|KO)\b""",
    )

    /** Set near language only: `DFT • IT` */
    private val mtgSetNearLang = Regex(
        """\b([A-Z0-9]{2,5})\s*[•·●\.\,/\|\-–—]\s*(EN|IT|DE|FR|ES|JP|JA|PH|KO)\b""",
    )

    /** Printed set abbr near number: `DRI 124/182` or `OBF · 124/197` */
    private val pokemonBottomPair = Regex(
        """(?i)\b([A-Z]{2,4})\s*[•·●\.\,/\|\-–— ]*\s*(\d{1,3}[A-Za-z]?)\s*/\s*(\d{2,3})\b""",
    )
    private val pokemonSetHint = Regex("""\b([A-Z]{3}|[A-Z]{2}\d|[A-Z]{2,4}\d{0,2})\b""")

    private val skipName = setOf(
        "creature", "instant", "sorcery", "enchantment", "artifact", "land", "planeswalker",
        "battle", "legendary", "basic", "token", "trainer", "supporter", "stadium", "energy",
        "item", "pokemon", "pokémon", "weakness", "resistance", "retreat", "illustrator",
        "magic", "the gathering", "creatura", "stregoneria", "incantesimo", "artefatto",
        "terra", "istantaneo", "leggendario",
        "fase", "stadio", "debolezza", "resistenza", "ritirata", "regola", "speciale",
        "evoluzione", "base", "stage", "ability", "abilità", "attacco", "weakness",
    )

    fun parse(rawText: String, game: Game, bottomPriorityText: String? = null): ParsedCardText {
        val merged = listOfNotNull(bottomPriorityText, rawText)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        return ParsedCardText(
            nameGuess = extractName(rawText.lines().map { it.trim() }.filter { it.isNotBlank() }, game),
            collectorNumber = extractCollector(merged, bottomPriorityText, game),
            setCode = extractSet(merged, bottomPriorityText, game),
            setCardCount = extractPokemonSetCount(merged, bottomPriorityText, game),
        )
    }

    private fun extractCollector(raw: String, bottom: String?, game: Game): String? {
        preferSources(bottom, raw).forEach { source ->
            if (game == Game.POKEMON) {
                pokemonBottomPair.find(source)?.groupValues?.getOrNull(2)?.let {
                    return normalizeCollector(it)
                }
            }
            collectorFraction.find(source)?.groupValues?.getOrNull(1)?.let {
                return normalizeCollector(it)
            }
            if (game == Game.MTG) {
                mtgBottomLine.find(source)?.groupValues?.getOrNull(1)?.let {
                    return normalizeCollector(it)
                }
                mtgSpaced.find(source)?.groupValues?.getOrNull(1)?.let {
                    return normalizeCollector(it)
                }
            }
        }
        if (game == Game.MTG) {
            val bottomLines = (bottom ?: raw).lines().map { it.trim() }.filter { it.isNotBlank() }.takeLast(5)
            for (line in bottomLines.asReversed()) {
                Regex("""\b(\d{2,4}[A-Za-z]?)\b""").find(line)?.groupValues?.getOrNull(1)?.let {
                    if (!line.contains("©") && !line.contains("Wizards", ignoreCase = true)) {
                        return normalizeCollector(it)
                    }
                }
            }
        }
        return null
    }

    private fun extractSet(raw: String, bottom: String?, game: Game): String? {
        if (game == Game.POKEMON) {
            preferSources(bottom, raw).forEach { source ->
                pokemonBottomPair.find(source)?.groupValues?.getOrNull(1)?.let {
                    return normalizeSet(it)
                }
            }
            preferSources(bottom, raw).forEach { source ->
                // Prefer 3-letter codes near the collector fraction.
                val nearFraction = Regex(
                    """(?i)\b([A-Z]{2,4})\b(?=[^\n]{0,12}\d{1,3}\s*/\s*\d{2,3})""",
                ).find(source)?.groupValues?.getOrNull(1)
                if (nearFraction != null && nearFraction !in setOf("HP", "GX", "EX", "VMAX", "VSTAR")) {
                    return normalizeSet(nearFraction)
                }
            }
            return null
        }
        preferSources(bottom, raw).forEach { source ->
            mtgBottomLine.find(source)?.groupValues?.getOrNull(2)?.let { return normalizeSet(it) }
            mtgSpaced.find(source)?.groupValues?.getOrNull(2)?.let { return normalizeSet(it) }
            mtgSetNearLang.find(source)?.groupValues?.getOrNull(1)?.let { return normalizeSet(it) }
        }
        val bottomLines = (bottom ?: raw).lines().takeLast(4).joinToString(" ")
        Regex("""\b(\d{1,4}[A-Za-z]?)\s*[•·●\.\,/\|\-–— ]+\s*([A-Z]{2,5})\b""")
            .find(bottomLines)
            ?.groupValues
            ?.getOrNull(2)
            ?.let { return normalizeSet(it) }
        return null
    }

    private fun extractPokemonSetCount(raw: String, bottom: String?, game: Game): Int? {
        if (game != Game.POKEMON) return null
        preferSources(bottom, raw).forEach { source ->
            pokemonBottomPair.find(source)?.groupValues?.getOrNull(3)?.toIntOrNull()?.let { return it }
            Regex("""\b\d{1,3}[A-Za-z]?\s*/\s*(\d{2,3})\b""").find(source)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun extractName(lines: List<String>, game: Game): String? {
        val candidates = lines.filter { line ->
            val lower = line.lowercase()
            val minLength = if (line.any { it.code > 0x2E80 }) 1 else 3
            line.length in minLength..48 &&
                line.any { it.isLetter() } &&
                skipName.none { lower == it || lower.startsWith("$it ") || lower.contains(" $it") } &&
                !Regex("""(?i)^fase\s*\d""").containsMatchIn(line) &&
                !Regex("""(?i)^\d+\s*hp""").containsMatchIn(line) &&
                !line.matches(Regex("""[\d\s/•·\-A-Z]{3,}""")) &&
                collectorFraction.find(line) == null &&
                mtgBottomLine.find(line) == null &&
                pokemonBottomPair.find(line) == null
        }
        return when (game) {
            Game.MTG -> candidates.firstOrNull()
            Game.POKEMON -> candidates.firstOrNull { line -> line.any { it.code > 0x2E80 } }
                ?: candidates.firstOrNull { !it.contains("HP", ignoreCase = true) }
                ?: candidates.firstOrNull()
        }
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
        return out.filter { it.length in 2..5 }.distinct()
    }

    fun collectorCandidates(raw: String?): List<String> {
        val base = raw?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val stripped = base.trimStart('0').ifBlank { base }
        return listOf(base, stripped).distinct()
    }
}

package com.scantrader.app.data.catalog

import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.scan.OcrParser

/**
 * A parsed set code is kept only when the catalog actually has that set.
 * `div` from the Italian word "diventare" is not a Scryfall set; sending it
 * as `set:div` makes the name search walk a string of empty queries.
 */
object KnownSetCodes {
    fun scrub(game: Game, parsed: ParsedCardText): ParsedCardText {
        val raw = parsed.setCode?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return parsed
        if (isKnown(game, raw)) return parsed
        val promoted = OcrParser.setCodeCandidates(raw).firstOrNull { candidate ->
            candidate != raw && isKnown(game, candidate)
        }
        return parsed.copy(setCode = promoted)
    }

    fun isKnown(game: Game, code: String?): Boolean {
        val key = code?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
        return when (game) {
            Game.MTG -> MtgSets.isKnown(key)
            Game.POKEMON -> pokemonKnown(key)
        }
    }

    /**
     * International printed codes and TCGdex ids, plus Japanese / Chinese ids
     * (`sv2a`, `s12a`, `cs4ac`). A digit planted by an OCR swap (`wird` → `w1rd`)
     * is not one of those ids.
     */
    private fun pokemonKnown(code: String): Boolean {
        if (PokemonSets.recognizedIds(code).isNotEmpty()) return true
        if (PokemonSets.isInternationalPrinted(code)) return true
        if (PokemonSets.isCatalogId(code)) return true
        if (PokemonSets.canonicalizePrinted(code) != null) return true
        return regionalSetId(code)
    }

    private fun regionalSetId(code: String): Boolean {
        val key = code.trim().lowercase()
        if (key.length !in 3..8 || !key.any { it.isLetter() }) return false
        if (key.startsWith("cs")) return true
        if (!key.any { it.isDigit() } || !key.first().isLetter()) return false
        return key.startsWith("sv") || key.startsWith("sm") || key.startsWith("xy") ||
            key.startsWith("s") || key.startsWith("m")
    }
}

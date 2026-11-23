package com.scantrader.app.data.catalog.language

import com.scantrader.app.data.catalog.CardLanguageDetector
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.Game

/** What a scan read about one card, for language detection. */
data class LanguageInput(
    val ocrName: String?,
    val englishName: String?,
    val printed: Map<CardLanguage, String>,
    val ocrText: String?,
    val collectorLang: String?,
)

/**
 * Language detection differs per game. Magic reads the collector code and the
 * printed words ([MagicLanguageStrategy]); Pokémon and the other games keep the
 * rules they had before the split.
 */
interface LanguageStrategy {
    fun explain(input: LanguageInput): CardLanguageDetector.Detection

    /**
     * True when the printed names of every language (Scryfall, TCGdex) may be
     * fetched after a local miss. Magic does not: the name never decides there.
     */
    val usesPrintedNameLookup: Boolean
}

object LanguageStrategies {
    fun forGame(game: Game): LanguageStrategy = when (game) {
        Game.MTG -> MagicLanguageStrategy
        Game.POKEMON -> PokemonLanguageStrategy
        Game.RIFTBOUND, Game.ONE_PIECE, Game.DRAGON_BALL, Game.YUGIOH, Game.UNION_ARENA, Game.OTHER ->
            DefaultLanguageStrategy
    }
}

/** Unchanged Pokémon rules: title, rules words, script. */
object PokemonLanguageStrategy : LanguageStrategy {
    override val usesPrintedNameLookup: Boolean = true

    override fun explain(input: LanguageInput): CardLanguageDetector.Detection =
        CardLanguageDetector.explain(
            ocrName = input.ocrName,
            englishName = input.englishName,
            printed = input.printed,
            ocrText = input.ocrText,
            pokemonSignals = true,
            collectorLang = input.collectorLang,
        )
}

/** Unchanged rules for the games without their own strategy. */
object DefaultLanguageStrategy : LanguageStrategy {
    override val usesPrintedNameLookup: Boolean = true

    override fun explain(input: LanguageInput): CardLanguageDetector.Detection =
        CardLanguageDetector.explain(
            ocrName = input.ocrName,
            englishName = input.englishName,
            printed = input.printed,
            ocrText = input.ocrText,
            pokemonSignals = false,
            collectorLang = input.collectorLang,
        )
}

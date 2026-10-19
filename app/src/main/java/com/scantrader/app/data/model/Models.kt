package com.scantrader.app.data.model

enum class Game(val csvLabel: String) {
    MTG("mtg"),
    POKEMON("pokemon"),
}

enum class CardCondition(val csvValue: String, val label: String) {
    NEAR_MINT("Near Mint", "Near Mint"),
    SLIGHTLY_PLAYED("Slightly Played", "Slightly Played"),
    MODERATELY_PLAYED("Moderately Played", "Moderately Played"),
    HEAVILY_PLAYED("Heavily Played", "Heavily Played"),
    DAMAGED("Damaged", "Damaged"),
}

enum class CardLanguage(val csvValue: String, val label: String) {
    ENGLISH("English", "English"),
    ITALIAN("Italian", "Italiano"),
    FRENCH("French", "Français"),
    GERMAN("German", "Deutsch"),
    SPANISH("Spanish", "Español"),
    PORTUGUESE("Portuguese", "Português"),
    JAPANESE("Japanese", "日本語"),
    KOREAN("Korean", "한국어"),
    CHINESE_S("Chinese", "简体中文"),
}

data class ParsedCardText(
    val nameGuess: String?,
    val collectorNumber: String?,
    val setCode: String?,
    /** Denominator from Pokémon `124/182` — helps disambiguate the set. */
    val setCardCount: Int? = null,
)

data class IdentifiedCard(
    val game: Game,
    val name: String,
    val setCode: String,
    val setName: String,
    val collectorNumber: String,
    val scryfallId: String? = null,
    val tcgplayerId: String? = null,
    val imageUrl: String? = null,
    /** Cardmarket EUR from Scryfall (non-foil). */
    val priceEur: String? = null,
    /** Cardmarket EUR foil from Scryfall. */
    val priceEurFoil: String? = null,
) {
    fun priceForExport(foil: Boolean): String? =
        if (foil) priceEurFoil?.takeIf { it.isNotBlank() } ?: priceEur else priceEur
}

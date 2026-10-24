package com.scantrader.app.data.model

enum class Game(val csvLabel: String, val shareLabel: String) {
    MTG("mtg", "Magic"),
    POKEMON("pokemon", "Pokémon"),
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
    ;

    /** Scryfall language code (`it`, `zhs`, …). */
    fun scryfallLang(): String = when (this) {
        ENGLISH -> "en"
        ITALIAN -> "it"
        FRENCH -> "fr"
        GERMAN -> "de"
        SPANISH -> "es"
        PORTUGUESE -> "pt"
        JAPANESE -> "ja"
        KOREAN -> "ko"
        CHINESE_S -> "zhs"
    }

    /** CardTrader `mtg_language` (`jp`, `zh-CN`, …). */
    fun cardTraderLang(): String = when (this) {
        JAPANESE -> "jp"
        KOREAN -> "kr"
        CHINESE_S -> "zh-CN"
        else -> scryfallLang()
    }

    companion object {
        fun fromCatalogCode(code: String): CardLanguage? = when (code.trim().lowercase()) {
            "en", "english" -> ENGLISH
            "it", "italian" -> ITALIAN
            "fr", "french" -> FRENCH
            "de", "german" -> GERMAN
            "es", "spanish" -> SPANISH
            "pt", "portuguese" -> PORTUGUESE
            "ja", "jp", "japanese" -> JAPANESE
            "ko", "kr", "korean" -> KOREAN
            "zhs", "zh", "zh-cn", "zh_cn", "chinese" -> CHINESE_S
            else -> null
        }
    }
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
    /** TCGdex card id (e.g. `base1-32`, `sv10-072`). */
    val tcgdexId: String? = null,
    val tcgplayerId: String? = null,
    val imageUrl: String? = null,
    /** Cardmarket EUR from Scryfall (non-foil). */
    val priceEur: String? = null,
    /** Cardmarket EUR foil from Scryfall. */
    val priceEurFoil: String? = null,
    val priceUsd: String? = null,
    val priceUsdFoil: String? = null,
    /** `EUR` or `USD` for the amount shown as the proposed price. */
    val priceCurrency: String? = null,
    /** `CardTrader`, `Scryfall`, or `Cardmarket`. */
    val priceSource: String? = null,
    /** Catalog/OCR prior in 0..1 (set+number strong, name weak). */
    val ocrScore: Float? = null,
    /** On-device art similarity vs scan crop in 0..1. */
    val visualScore: Float? = null,
    /** Fused confidence used for ranking and auto-scan. */
    val matchConfidence: Float? = null,
    /**
     * Localized title (Scryfall `printed_name`) when [name] is the English oracle name.
     * Autoscan compares OCR against this so an Italian printing is not a name conflict.
     */
    val printedName: String? = null,
    /** Scryfall/TCGdex code of [printedName], when it was resolved (`it`, `ja`, …). */
    val printedLang: String? = null,
) {
    fun priceForExport(foil: Boolean): String? =
        if (foil) priceEurFoil?.takeIf { it.isNotBlank() } ?: priceEur else priceEur

    fun confidenceLabel(): String? {
        val c = matchConfidence ?: return null
        val pct = (c * 100f).toInt().coerceIn(0, 100)
        return "$pct%"
    }

    /** Stable id of the print evaluateAutoMatch scored, shared with the same-card lock. */
    fun identityKey(): String =
        scryfallId
            ?: tcgdexId
            ?: "$game|$setCode|$collectorNumber|$name"
}

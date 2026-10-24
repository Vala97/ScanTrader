package com.scantrader.app.data.catalog

import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.network.scryfall.ScryfallBusyException
import com.scantrader.app.network.scryfall.ScryfallClient
import com.scantrader.app.network.tcgdex.TcgdexApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Compares the OCR title with printed names. An exact hit on a name we already
 * have does not wait on the network. Otherwise each scan language is fetched
 * once (Scryfall and TCGdex cache their own misses).
 */
class CardLanguageResolver(
    private val scryfall: ScryfallClient,
    private val tcgdex: TcgdexApi,
) {
    suspend fun detect(card: IdentifiedCard, ocrName: String?): CardLanguage? {
        val quick = quickDetect(card, ocrName)
        if (quick != null) return quick
        val (english, printed) = when (card.game) {
            Game.MTG -> mtgNames(card)
            Game.POKEMON -> pokemonNames(card)
        }
        return CardLanguageDetector.detect(ocrName, english, printed)
    }

    private fun quickDetect(card: IdentifiedCard, ocrName: String?): CardLanguage? {
        val displayIsPrinted = !card.printedName.isNullOrBlank() &&
            card.name.equals(card.printedName, ignoreCase = true)
        val english = card.name.takeUnless { displayIsPrinted }
        val printed = linkedMapOf<CardLanguage, String>()
        val lang = card.printedLang?.let { CardLanguage.fromCatalogCode(it) }
        if (lang != null && !card.printedName.isNullOrBlank()) printed[lang] = card.printedName
        return CardLanguageDetector.detect(ocrName, english, printed)
    }

    private suspend fun mtgNames(card: IdentifiedCard): Pair<String?, Map<CardLanguage, String>> {
        if (card.setCode.isBlank() || card.collectorNumber.isBlank()) {
            return card.name to emptyMap()
        }
        val english = runCatching { scryfall.cardBySetAndNumber(card.setCode, card.collectorNumber) }
            .getOrNull()
            ?.name
        val printed = linkedMapOf<CardLanguage, String>()
        for (language in CardLanguage.entries) {
            if (language == CardLanguage.ENGLISH) continue
            val local = try {
                scryfall.cardBySetNumberLang(card.setCode, card.collectorNumber, language.scryfallLang())
            } catch (_: ScryfallBusyException) {
                break
            }
            val title = local?.printedName?.takeIf { it.isNotBlank() } ?: continue
            printed[language] = title
        }
        return (english ?: card.name) to printed
    }

    private suspend fun pokemonNames(card: IdentifiedCard): Pair<String?, Map<CardLanguage, String>> {
        val id = card.tcgdexId?.takeIf { it.isNotBlank() } ?: return card.name to emptyMap()
        val names = coroutineScope {
            CardLanguage.entries.mapNotNull { language ->
                val code = language.tcgdexLang() ?: return@mapNotNull null
                async {
                    val name = runCatching { tcgdex.card(code, id).name }.getOrNull()
                    if (name.isNullOrBlank()) null else language to name
                }
            }.awaitAll().filterNotNull()
        }
        val english = names.firstOrNull { it.first == CardLanguage.ENGLISH }?.second ?: card.name
        val printed = names.filter { it.first != CardLanguage.ENGLISH }.toMap()
        return english to printed
    }
}

private fun CardLanguage.tcgdexLang(): String? = when (this) {
    CardLanguage.ENGLISH -> "en"
    CardLanguage.ITALIAN -> "it"
    CardLanguage.FRENCH -> "fr"
    CardLanguage.GERMAN -> "de"
    CardLanguage.SPANISH -> "es"
    CardLanguage.PORTUGUESE -> "pt"
    CardLanguage.JAPANESE -> "ja"
    CardLanguage.KOREAN -> null
    CardLanguage.CHINESE_S -> "zh-cn"
}

package com.scantrader.app.data.catalog

import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.network.scryfall.ScryfallBusyException
import com.scantrader.app.network.scryfall.ScryfallClient
import com.scantrader.app.network.tcgdex.TcgdexApi
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Compares the OCR title with printed names. An exact hit on a name we already
 * have does not wait on the network. Otherwise each scan language is fetched
 * once per card id and reused. A failed fetch is not cached and must not be
 * treated as a language result by the caller.
 */
class CardLanguageResolver(
    private val scryfall: ScryfallClient,
    private val tcgdex: TcgdexApi,
) {
    private val pokemonNameCache = ConcurrentHashMap<String, Pair<String?, Map<CardLanguage, String>>>()
    private val pokemonNameGate = Mutex()
    suspend fun detect(card: IdentifiedCard, ocrName: String?, ocrText: String? = null): CardLanguage? =
        explain(card, ocrName, ocrText).language

    suspend fun explain(card: IdentifiedCard, ocrName: String?, ocrText: String? = null): CardLanguageDetector.Detection {
        val pokemon = card.game == Game.POKEMON
        val local = localNames(card)
        val fromLocal = CardLanguageDetector.explain(ocrName, local.first, local.second, ocrText, pokemon)
        if (fromLocal.language != null) return fromLocal
        val (english, printed) = when (card.game) {
            Game.MTG -> mtgNames(card)
            Game.POKEMON -> pokemonNames(card)
        }
        return CardLanguageDetector.explain(ocrName, english, printed, ocrText, pokemon)
    }

    private fun localNames(card: IdentifiedCard): Pair<String?, Map<CardLanguage, String>> {
        val displayIsPrinted = !card.printedName.isNullOrBlank() &&
            card.name.equals(card.printedName, ignoreCase = true)
        val english = card.catalogName?.takeIf { it.isNotBlank() }
            ?: card.name.takeUnless { displayIsPrinted }
        val printed = linkedMapOf<CardLanguage, String>()
        val lang = card.printedLang?.let { CardLanguage.fromCatalogCode(it) }
        if (lang != null && !card.printedName.isNullOrBlank()) printed[lang] = card.printedName
        return english to printed
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
        pokemonNameCache[id]?.let { return it }
        return pokemonNameGate.withLock {
            pokemonNameCache[id]?.let { return@withLock it }
            val fetched = fetchPokemonNames(id, card.name)
            if (fetched.third) pokemonNameCache[id] = fetched.first to fetched.second
            fetched.first to fetched.second
        }
    }

    private suspend fun fetchPokemonNames(
        id: String,
        fallbackEnglish: String,
    ): Triple<String?, Map<CardLanguage, String>, Boolean> {
        val names = coroutineScope {
            CardLanguage.entries.mapNotNull { language ->
                val code = language.tcgdexLang() ?: return@mapNotNull null
                async {
                    val name = runCatching { tcgdex.card(code, id).name }.getOrNull()
                    if (name.isNullOrBlank()) null else language to name
                }
            }.awaitAll().filterNotNull()
        }
        val english = names.firstOrNull { it.first == CardLanguage.ENGLISH }?.second ?: fallbackEnglish
        val printed = names.filter { it.first != CardLanguage.ENGLISH }.toMap()
        return Triple(english, printed, names.isNotEmpty())
    }
}

internal fun pokemonTcgdexLanguages(): List<String> =
    CardLanguage.entries.mapNotNull { it.tcgdexLang() }

private fun CardLanguage.tcgdexLang(): String? = when (this) {
    CardLanguage.ENGLISH -> "en"
    CardLanguage.ITALIAN -> "it"
    CardLanguage.FRENCH -> "fr"
    CardLanguage.GERMAN -> "de"
    CardLanguage.SPANISH -> "es"
    CardLanguage.PORTUGUESE -> "pt"
    CardLanguage.JAPANESE -> "ja"
    CardLanguage.KOREAN -> "ko"
    CardLanguage.CHINESE_S -> "zh-cn"
    CardLanguage.CHINESE_T -> "zh-tw"
}

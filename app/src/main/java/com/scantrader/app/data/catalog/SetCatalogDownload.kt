package com.scantrader.app.data.catalog

import com.scantrader.app.network.scryfall.ScryfallCard
import com.scantrader.app.network.scryfall.ScryfallGate
import com.scantrader.app.network.scryfall.ScryfallSetApi
import com.scantrader.app.network.tcgdex.TcgdexSetApi
import java.util.Locale
import kotlinx.coroutines.delay
import retrofit2.HttpException

data class DownloadedMtgSet(
    val cards: List<ScryfallCard>,
    /** False when the page cap stopped the download before Scryfall said it was done. */
    val complete: Boolean,
)

data class StoredPkmCard(
    val id: String,
    val localId: String,
    val name: String,
    val image: String? = null,
)

data class DownloadedPokemonSet(
    val setName: String,
    val officialCount: Int?,
    val cards: List<StoredPkmCard>,
)

interface MtgSetSource {
    suspend fun download(setCode: String): DownloadedMtgSet
}

interface PokemonSetSource {
    suspend fun download(setId: String, lang: String): DownloadedPokemonSet
}

/**
 * Downloads every printing of one Scryfall set, including non-English cards
 * (`include_multilingual`). Uses [gate] for spacing but not the scan client's mutex,
 * so a page fetch does not hold the next card lookup.
 */
class ScryfallSetDownloader(
    private val api: ScryfallSetApi,
    private val gate: ScryfallGate,
    private val maxPages: Int = MAX_PAGES,
) : MtgSetSource {
    override suspend fun download(setCode: String): DownloadedMtgSet {
        val code = setCode.trim().lowercase(Locale.ROOT)
        val cards = ArrayList<ScryfallCard>()
        var page = 1
        while (page <= maxPages) {
            awaitSlot()
            val response = try {
                api.search(query = "set:$code", page = page)
            } catch (error: HttpException) {
                if (error.code() == 429) {
                    gate.onRateLimited(error.response()?.headers()?.get("Retry-After"), 0)
                }
                throw error
            }
            cards += response.data
            if (!response.hasMore || response.data.isEmpty()) {
                return DownloadedMtgSet(cards, complete = true)
            }
            page++
        }
        return DownloadedMtgSet(cards, complete = false)
    }

    private suspend fun awaitSlot() {
        while (true) {
            val wait = maxOf(gate.millisUntilAllowed(), if (gate.coolingDown()) 250L else 0L)
            if (wait > 0) {
                delay(wait)
                continue
            }
            gate.markStarted()
            return
        }
    }

    companion object {
        const val MAX_PAGES = 40
    }
}

class TcgdexSetDownloader(
    private val api: TcgdexSetApi,
) : PokemonSetSource {
    override suspend fun download(setId: String, lang: String): DownloadedPokemonSet {
        try {
            val details = api.set(lang, setId)
            return DownloadedPokemonSet(
                setName = details.name,
                officialCount = details.cardCount?.official,
                cards = details.cards.map { brief ->
                    StoredPkmCard(
                        id = brief.id,
                        localId = brief.localId.ifBlank { brief.id.substringAfter('-', "") },
                        name = brief.name,
                        image = brief.image,
                    )
                },
            )
        } catch (error: HttpException) {
            if (error.code() == 404) {
                return DownloadedPokemonSet(setName = "", officialCount = null, cards = emptyList())
            }
            throw error
        }
    }
}

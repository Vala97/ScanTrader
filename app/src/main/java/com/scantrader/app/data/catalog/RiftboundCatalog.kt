package com.scantrader.app.data.catalog

import com.google.gson.Gson
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.network.cardtrader.CardTraderApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale

/**
 * Riftbound catalog. There is no free catalog API like Scryfall or TCGdex, so the cards
 * come from CardTrader (`expansions` + `blueprints/export`), which needs the API token.
 * The list (~2100 blueprints) is saved on the phone and refreshed after [MAX_AGE_MS].
 *
 * A Riftbound card prints its identity bottom-left: `OGN • 001/298`. Alternate art adds
 * `a` (`007a/298`), overnumbered showcases go past the set size (`299/298`), signed
 * showcases add `s` (`299s`). Set code + number is one blueprint, so lookup is exact.
 */
class RiftboundCatalog(
    private val api: CardTraderApi,
    private val token: () -> String?,
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Entry(
        val id: Int,
        val name: String,
        val code: String,
        val setName: String,
        val number: String,
        val rarity: String?,
        val image: String?,
    )

    private data class Stored(val at: Long = 0, val entries: List<Entry> = emptyList())

    class NoTokenException : IllegalStateException(
        "Per Riftbound serve il token API di CardTrader: inseriscilo in Impostazioni.",
    )

    private val gson = Gson()
    private val mutex = Mutex()

    @Volatile private var stored: Stored? = null

    @Volatile private var byKey: Map<String, List<Entry>> = emptyMap()

    /** Why the last [ensure] had nothing to use (no token, download failed), for the confirm screen. */
    @Volatile var lastError: String? = null
        private set

    /** Cards in memory (0 until the first [ensure]). */
    val size: Int get() = stored?.entries?.size ?: 0

    /** Loads from disk, downloads when missing or old. Throws [NoTokenException] with nothing to use. */
    suspend fun ensure(force: Boolean = false): Int = mutex.withLock {
        val cur = stored ?: readFile()?.also { index(it) }
        val fresh = cur != null && cur.entries.isNotEmpty() && now() - cur.at < MAX_AGE_MS
        if (fresh && !force) return@withLock cur!!.entries.size
        if (token()?.isBlank() != false) {
            if (cur != null && cur.entries.isNotEmpty()) return@withLock cur.entries.size
            throw NoTokenException().also { lastError = it.message }
        }
        val attempt = runCatching { download() }
            .onFailure { com.scantrader.app.scan.ScanDebug.w("Riftbound catalog download failed", it) }
        val downloaded = attempt.getOrNull()
        if (downloaded.isNullOrEmpty()) {
            if (cur != null && cur.entries.isNotEmpty()) return@withLock cur.entries.size
            val why = attempt.exceptionOrNull()?.let { e ->
                if (e is retrofit2.HttpException && (e.code() == 401 || e.code() == 403)) "token CardTrader non valido (${e.code()}): ricontrollalo in Impostazioni"
                else e.message ?: e.javaClass.simpleName
            } ?: "nessuna carta ricevuta"
            throw IllegalStateException("Catalogo Riftbound non scaricato da CardTrader: $why.").also { lastError = it.message }
        }
        lastError = null
        val next = Stored(now(), downloaded)
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(gson.toJson(next))
            tmp.renameTo(file)
        }
        index(next)
        downloaded.size
    }

    suspend fun identify(parsed: ParsedCardText, query: String? = null): List<IdentifiedCard> {
        ensure()
        val q = query?.trim().orEmpty()
        val byCode = lookup(parsed.setCode, parsed.collectorNumber, parsed.setCardCount)
        // "Cerca per nome": il nome scritto vince quando il codice non trova niente o non c'entra
        if (q.isNotEmpty() && (byCode.isEmpty() || byCode.none { nameKey(it.name).contains(nameKey(q)) })) {
            return searchByName(q).ifEmpty { byCode }
        }
        return byCode
    }

    /**
     * Name search for the confirm screen: every word typed must appear in the CardTrader
     * name (`Kai'Sa - Daughter of the Void` is found by `daughter void` or `kaisa`).
     * Exact title first, then the shortest names.
     */
    fun searchByName(query: String, limit: Int = 12): List<IdentifiedCard> {
        val words = query.lowercase(Locale.ROOT).split(Regex("""[^\p{L}\p{N}]+""")).map(::nameKey).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val whole = nameKey(query)
        return stored?.entries.orEmpty()
            .filter { e -> val k = nameKey(e.name); words.all { k.contains(it) } }
            .sortedWith(
                compareByDescending<Entry> { e -> e.name.split(" - ").any { nameKey(it) == whole } }
                    .thenBy { it.name.length }
                    .thenBy { it.code }
                    .thenBy { it.number },
            )
            .take(limit)
            .map { it.toIdentified(NUMBER_SCORE) }
    }

    /**
     * Exact set + number first. Without a set code, every set that has that number
     * (a confirmation is needed). The printed set size narrows it when it is readable.
     */
    fun lookup(code: String?, number: String?, total: Int?): List<IdentifiedCard> {
        val n = numberKey(number) ?: return emptyList()
        val c = code?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        if (c != null) {
            val hit = byKey["$c|$n"].orEmpty()
            if (hit.isNotEmpty()) return hit.map { it.toIdentified(EXACT_SCORE) }
        }
        val all = byKey.filterKeys { it.endsWith("|$n") }.values.flatten()
        val sized = if (total != null) all.filter { setSize(it.code) == total } else emptyList()
        return (sized.ifEmpty { all }).map { it.toIdentified(if (sized.size == 1) SIZE_SCORE else NUMBER_SCORE) }
    }

    /** Highest plain number of a set, the `298` printed after the slash. */
    private fun setSize(code: String): Int? =
        stored?.entries.orEmpty()
            .filter { it.code == code }
            .mapNotNull { e -> e.number.takeIf { n -> n.all(Char::isDigit) }?.toIntOrNull() }
            .let { nums -> if (nums.isEmpty()) null else sizes.getOrPut(code) { printedSize(nums) } }

    private val sizes = mutableMapOf<String, Int>()

    private suspend fun download(): List<Entry> {
        val sets = api.expansions().filter { it.gameId == GAME_ID }
        val out = ArrayList<Entry>(2200)
        for (set in sets) {
            val prints = runCatching { api.blueprints(set.id) }.getOrElse { emptyList() }
            for (b in prints) {
                if (b.gameId != null && b.gameId != GAME_ID) continue
                val fixed = b.fixedProperties.orEmpty()
                val number = (fixed["collector_number"] as? String)?.trim().orEmpty()
                if (number.isEmpty() || b.name.isBlank()) continue
                out += Entry(
                    id = b.id,
                    name = b.name.trim(),
                    code = set.code.trim().lowercase(Locale.ROOT),
                    setName = set.name.trim(),
                    number = number,
                    rarity = (fixed["riftbound_rarity"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                    image = b.imageUrl?.takeIf { it.isNotBlank() },
                )
            }
        }
        return out
    }

    private fun readFile(): Stored? = runCatching {
        if (!file.exists()) null else gson.fromJson(file.readText(), Stored::class.java)
    }.getOrNull()

    private fun index(s: Stored) {
        stored = s
        sizes.clear()
        byKey = s.entries.groupBy { "${it.code}|${numberKey(it.number)}" }
    }

    private fun Entry.toIdentified(score: Float) = IdentifiedCard(
        game = Game.RIFTBOUND,
        name = name,
        setCode = code,
        setName = setName,
        collectorNumber = number,
        imageUrl = image?.let { if (it.startsWith("http")) it else "https://www.cardtrader.com$it" },
        ocrScore = score,
        catalogName = name,
        ctBlueprintId = id,
        rarity = rarity,
        catalogLang = "en",
    )

    companion object {
        const val GAME_ID = 22
        const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
        const val EXACT_SCORE = 0.97f
        const val SIZE_SCORE = 0.85f
        const val NUMBER_SCORE = 0.6f

        fun nameKey(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "").lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]"""), "")

        /** Rarities that only exist foil: the confirm screen starts with foil on. */
        private val ALWAYS_FOIL = setOf("rare", "epic", "legendary", "showcase", "alternate art")

        fun alwaysFoil(rarity: String?): Boolean = rarity?.trim()?.lowercase(Locale.ROOT) in ALWAYS_FOIL

        /** `001` → `1`, `007a` → `7a`, `299S` → `299s`. Null when it is not a Riftbound number. */
        fun numberKey(raw: String?): String? {
            val m = Regex("""^0*(\d{1,3})([as]?)$""").find(raw?.trim()?.lowercase(Locale.ROOT).orEmpty()) ?: return null
            return m.groupValues[1].ifEmpty { "0" } + m.groupValues[2]
        }

        /**
         * Printed set size from the plain numbers: overnumbered showcases sit past it,
         * so take the largest number followed by no gap wider than 3.
         */
        fun printedSize(nums: List<Int>): Int {
            val sorted = nums.distinct().sorted()
            var size = sorted.first()
            for (i in 1 until sorted.size) {
                if (sorted[i] - sorted[i - 1] > 3) break
                size = sorted[i]
            }
            return size
        }
    }
}

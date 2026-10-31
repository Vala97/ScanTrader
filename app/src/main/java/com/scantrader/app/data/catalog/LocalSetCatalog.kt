package com.scantrader.app.data.catalog

import com.google.gson.Gson
import com.scantrader.app.data.model.Game
import com.scantrader.app.network.scryfall.ScryfallCard
import com.scantrader.app.network.tcgdex.TcgdexCard
import com.scantrader.app.network.tcgdex.TcgdexCardCount
import com.scantrader.app.network.tcgdex.TcgdexSet
import com.scantrader.app.scan.ScanDebug
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Local card list for one expansion.
 * The first matched card starts a background download. Later scans of that set
 * read this index and skip Scryfall / TCGdex. A miss still uses the online lookup.
 */
class LocalSetCatalog(
    private val dir: File,
    private val mtgSource: MtgSetSource,
    private val pokemonSource: PokemonSetSource,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { ScanDebug.d(it) },
) {
    private val gson = Gson()
    private val memoryMtg = ConcurrentHashMap<String, MtgSetSnapshot>()
    private val memoryPokemon = ConcurrentHashMap<String, PokemonSetSnapshot>()
    private val inflight = ConcurrentHashMap<String, Job>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val nameCache = ConcurrentHashMap<String, Pair<Long, List<String>>>()

    fun mtgSnapshot(setCode: String): MtgSetSnapshot? {
        val code = canonicalMtg(setCode) ?: return null
        val snap = memoryMtg[code] ?: readMtg(code)?.also { memoryMtg[code] = it } ?: return null
        val age = now() - snap.fetchedAtMs
        if (age > HARD_MS) {
            memoryMtg.remove(code, snap)
            mtgFile(code).delete()
            prefetchMtg(code)
            return null
        }
        if (age > SOFT_MS) prefetchMtg(code)
        return snap
    }

    fun pokemonSnapshot(setId: String, lang: String): PokemonSetSnapshot? {
        val id = canonicalPokemon(setId) ?: return null
        val language = canonicalLang(lang) ?: return null
        val key = pokemonKey(id, language)
        val snap = memoryPokemon[key] ?: readPokemon(id, language)?.also { memoryPokemon[key] = it } ?: return null
        val age = now() - snap.fetchedAtMs
        if (age > HARD_MS) {
            memoryPokemon.remove(key, snap)
            pokemonFile(id, language).delete()
            prefetchPokemon(id, listOf(language))
            return null
        }
        if (age > SOFT_MS) prefetchPokemon(id, listOf(language))
        return snap
    }

    fun prefetchMtg(setCode: String) {
        val code = canonicalMtg(setCode) ?: return
        val key = "mtg:$code"
        if (freshMtg(code) || backedOff(key)) return
        launch(key) {
            val started = now()
            log("catalog prefetch start set=$code")
            val downloaded = mtgSource.download(code)
            val snap = MtgSetSnapshot(code, downloaded.cards, downloaded.complete, now())
            writeMtg(snap)
            memoryMtg[code] = snap
            val truncated = if (downloaded.complete) "" else " truncated"
            log(
                "catalog prefetch done set=$code cards=${downloaded.cards.size}" +
                    "$truncated ms=${now() - started}",
            )
        }
    }

    fun prefetchPokemon(setId: String, langs: List<String>) {
        val id = canonicalPokemon(setId) ?: return
        val wanted = langs.mapNotNull { canonicalLang(it) }.distinct()
        if (wanted.isEmpty()) return
        val key = "pkm:$id:${wanted.joinToString(",")}"
        if (wanted.all { freshPokemon(id, it) } || backedOff(key)) return
        launch(key) {
            val started = now()
            log("catalog prefetch start set=$id langs=${wanted.joinToString(",")}")
            var count = 0
            for (language in wanted) {
                val langKey = "pkm:$id:$language"
                if (freshPokemon(id, language) || backedOff(langKey)) continue
                try {
                    val downloaded = pokemonSource.download(id, language)
                    val snap = pokemonSnapshotFrom(id, language, downloaded, now())
                    writePokemon(snap)
                    memoryPokemon[pokemonKey(id, language)] = snap
                    count += downloaded.cards.size
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    failedAt[langKey] = now()
                    log("catalog prefetch failed set=$id lang=$language ${error.javaClass.simpleName}: ${error.message}")
                }
            }
            log("catalog prefetch done set=$id cards=$count ms=${now() - started}")
        }
    }

    fun installMtg(
        setCode: String,
        cards: List<ScryfallCard>,
        complete: Boolean = true,
        fetchedAtMs: Long = now(),
    ) {
        val code = canonicalMtg(setCode) ?: return
        val snap = MtgSetSnapshot(code, cards, complete, fetchedAtMs)
        writeMtg(snap)
        memoryMtg[code] = snap
    }

    fun installPokemon(
        setId: String,
        lang: String,
        cards: List<StoredPkmCard>,
        setName: String = "",
        officialCount: Int? = null,
        fetchedAtMs: Long = now(),
    ) {
        val id = canonicalPokemon(setId) ?: return
        val language = canonicalLang(lang) ?: return
        val snap = pokemonSnapshotFrom(
            id,
            language,
            DownloadedPokemonSet(setName, officialCount, cards),
            fetchedAtMs,
        )
        writePokemon(snap)
        memoryPokemon[pokemonKey(id, language)] = snap
    }

    /**
     * English and localized set names already on disk for [setCode].
     * Reads the cache only: no download, no refresh, and an old file is kept.
     */
    fun cachedSetNames(game: Game, setCode: String): List<String> = when (game) {
        Game.MTG -> cachedMtgSetNames(setCode)
        Game.POKEMON -> cachedPokemonSetNames(setCode)
        // giochi con il catalogo CardTrader: il nome del set e' gia' salvato sulla carta
        else -> emptyList()
    }

    fun logHit(setCode: String, elapsedMs: Long) {
        log("catalog local hit set=${setCode.lowercase(Locale.ROOT)} ms=$elapsedMs")
    }

    suspend fun awaitIdle() {
        while (true) {
            val jobs = inflight.values.filter { it.isActive }
            if (jobs.isEmpty()) return
            jobs.forEach { it.join() }
        }
    }

    private fun freshMtg(code: String): Boolean {
        val snap = memoryMtg[code] ?: readMtg(code)?.also { memoryMtg[code] = it } ?: return false
        return now() - snap.fetchedAtMs < SOFT_MS
    }

    private fun freshPokemon(setId: String, lang: String): Boolean {
        val key = pokemonKey(setId, lang)
        val snap = memoryPokemon[key] ?: readPokemon(setId, lang)?.also { memoryPokemon[key] = it } ?: return false
        return now() - snap.fetchedAtMs < SOFT_MS
    }

    private fun backedOff(key: String): Boolean {
        val at = failedAt[key] ?: return false
        if (now() - at < FAIL_BACKOFF_MS) return true
        failedAt.remove(key, at)
        return false
    }

    private fun launch(key: String, block: suspend () -> Unit) {
        val job = scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failedAt[key] = now()
                log("catalog prefetch failed key=$key ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                inflight.remove(key, coroutineContext[Job])
            }
        }
        while (true) {
            val previous = inflight.putIfAbsent(key, job)
            if (previous == null) {
                if (job.isCompleted) inflight.remove(key, job)
                return
            }
            if (previous.isActive) {
                job.cancel()
                return
            }
            if (inflight.replace(key, previous, job)) {
                if (job.isCompleted) inflight.remove(key, job)
                return
            }
        }
    }

    private fun writeMtg(snap: MtgSetSnapshot) {
        val stored = StoredMtgFile(
            version = VERSION,
            setCode = snap.setCode,
            fetchedAtMs = snap.fetchedAtMs,
            complete = snap.complete,
            cards = snap.cards,
        )
        writeAtomic(mtgFile(snap.setCode), gson.toJson(stored))
    }

    private fun readMtg(code: String): MtgSetSnapshot? {
        val file = mtgFile(code)
        if (!file.isFile) return null
        val stored = runCatching { gson.fromJson(file.readText(), StoredMtgFile::class.java) }.getOrNull()
        if (stored == null || stored.version != VERSION || stored.cards == null) {
            file.delete()
            return null
        }
        return MtgSetSnapshot(
            setCode = code,
            cards = stored.cards,
            complete = stored.complete,
            fetchedAtMs = stored.fetchedAtMs,
        )
    }

    private fun writePokemon(snap: PokemonSetSnapshot) {
        val stored = StoredPokemonFile(
            version = VERSION,
            setId = snap.setId,
            lang = snap.lang,
            setName = snap.setName,
            officialCount = snap.officialCount,
            fetchedAtMs = snap.fetchedAtMs,
            cards = snap.cards.map { card ->
                StoredPkmCard(card.id, card.localId, card.name, card.image)
            },
        )
        writeAtomic(pokemonFile(snap.setId, snap.lang), gson.toJson(stored))
    }

    private fun readPokemon(setId: String, lang: String): PokemonSetSnapshot? {
        val file = pokemonFile(setId, lang)
        if (!file.isFile) return null
        val stored = runCatching { gson.fromJson(file.readText(), StoredPokemonFile::class.java) }.getOrNull()
        if (stored == null || stored.version != VERSION || stored.cards == null) {
            file.delete()
            return null
        }
        return pokemonSnapshotFrom(
            setId,
            lang,
            DownloadedPokemonSet(
                setName = stored.setName.orEmpty(),
                officialCount = stored.officialCount,
                cards = stored.cards,
            ),
            stored.fetchedAtMs,
        )
    }

    private fun pokemonSnapshotFrom(
        setId: String,
        lang: String,
        downloaded: DownloadedPokemonSet,
        fetchedAtMs: Long,
    ): PokemonSetSnapshot {
        val set = TcgdexSet(
            id = setId,
            name = downloaded.setName,
            cardCount = TcgdexCardCount(official = downloaded.officialCount),
        )
        val cards = downloaded.cards.map { card ->
            TcgdexCard(
                id = card.id,
                localId = card.localId,
                name = card.name,
                image = imageFor(lang, card.id, card.image),
                set = set,
            )
        }
        return PokemonSetSnapshot(setId, lang, downloaded.setName, downloaded.officialCount, cards, fetchedAtMs)
    }

    private fun imageFor(lang: String, id: String, image: String?): String {
        if (!image.isNullOrBlank() && image.contains("/$lang/")) return image
        if (!image.isNullOrBlank()) return image
        return "https://assets.tcgdex.net/$lang/local/$id"
    }

    private fun writeAtomic(file: File, text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }.onFailure { error ->
            log("catalog cache write failed ${file.name}: ${error.message}")
        }
    }

    private fun cachedMtgSetNames(setCode: String): List<String> {
        val code = canonicalMtg(setCode) ?: return emptyList()
        val file = mtgFile(code)
        val stamp = if (file.isFile) file.lastModified() else 0L
        val key = "mtg:$code"
        nameCache[key]?.takeIf { it.first == stamp }?.let { return it.second }
        val names = (memoryMtg[code] ?: readMtg(code))
            ?.cards
            .orEmpty()
            .mapNotNull { it.setName?.trim()?.takeIf { name -> name.isNotEmpty() } }
            .distinct()
        nameCache[key] = stamp to names
        return names
    }

    private fun cachedPokemonSetNames(setCode: String): List<String> {
        val id = canonicalPokemon(setCode) ?: return emptyList()
        val prefix = "pkm-${sanitize(id)}-"
        val files = dir.listFiles().orEmpty().filter { file ->
            file.isFile && file.name.startsWith(prefix) && file.name.endsWith(".json")
        }
        val stamp = files.maxOfOrNull { it.lastModified() } ?: 0L
        val key = "pkm:$id"
        nameCache[key]?.takeIf { it.first == stamp }?.let { return it.second }
        val fromMemory = memoryPokemon.values
            .filter { it.setId == id }
            .map { it.setName }
        val fromDisk = files.mapNotNull { file ->
            val lang = file.name.removePrefix(prefix).removeSuffix(".json")
            readPokemon(id, lang)?.setName
        }
        val names = (fromMemory + fromDisk).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        nameCache[key] = stamp to names
        return names
    }

    private fun mtgFile(code: String) = File(dir, "mtg-$code.json")

    private fun pokemonFile(setId: String, lang: String) = File(dir, "pkm-${sanitize(setId)}-$lang.json")

    private fun pokemonKey(setId: String, lang: String) = "$setId|$lang"

    private fun sanitize(raw: String): String =
        raw.map { ch ->
            if (ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_') ch else '_'
        }.joinToString("").take(48)

    companion object {
        const val SOFT_MS = 14L * 24 * 60 * 60 * 1000
        const val HARD_MS = 30L * 24 * 60 * 60 * 1000
        private const val FAIL_BACKOFF_MS = 60_000L
        private const val VERSION = 1

        fun canonicalMtg(raw: String?): String? {
            val code = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
            if (code.length !in 2..10 || code.any { !it.isLetterOrDigit() }) return null
            return code
        }

        fun canonicalPokemon(raw: String?): String? {
            val code = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
            if (code.length !in 2..24) return null
            if (code.any { !it.isLetterOrDigit() && it != '.' && it != '-' }) return null
            return code
        }

        fun canonicalLang(raw: String?): String? {
            val lang = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
            if (lang.length !in 2..8 || lang.any { !it.isLetterOrDigit() && it != '-' }) return null
            return lang
        }
    }
}

class MtgSetSnapshot(
    val setCode: String,
    val cards: List<ScryfallCard>,
    val complete: Boolean,
    val fetchedAtMs: Long,
) {
    private val byExact: Map<String, List<ScryfallCard>> =
        cards.groupBy { it.collectorNumber.lowercase(Locale.ROOT) }

    /** English (or unmarked) printing first, matching `/cards/{set}/{number}`. */
    fun defaultPrint(number: String): ScryfallCard? {
        val prints = printsFor(number)
        return prints.firstOrNull { it.lang.isNullOrBlank() || it.lang.equals("en", ignoreCase = true) }
            ?: prints.firstOrNull()
    }

    fun printsFor(number: String): List<ScryfallCard> {
        val exact = byExact[number.lowercase(Locale.ROOT)]
        if (!exact.isNullOrEmpty()) return exact
        val digits = number.filter { it.isDigit() }.trimStart('0').ifEmpty { return emptyList() }
        return cards.filter { card ->
            val raw = card.collectorNumber
            if (raw.any { it.isLetter() }) return@filter false
            raw.filter { it.isDigit() }.trimStart('0').ifEmpty { "0" } == digits
        }
    }
}

class PokemonSetSnapshot(
    val setId: String,
    val lang: String,
    val setName: String,
    val officialCount: Int?,
    val cards: List<TcgdexCard>,
    val fetchedAtMs: Long,
)

internal data class StoredMtgFile(
    val version: Int = 0,
    val setCode: String = "",
    val fetchedAtMs: Long = 0L,
    val complete: Boolean = false,
    val cards: List<ScryfallCard>? = null,
)

internal data class StoredPokemonFile(
    val version: Int = 0,
    val setId: String = "",
    val lang: String = "",
    val setName: String? = null,
    val officialCount: Int? = null,
    val fetchedAtMs: Long = 0L,
    val cards: List<StoredPkmCard>? = null,
)

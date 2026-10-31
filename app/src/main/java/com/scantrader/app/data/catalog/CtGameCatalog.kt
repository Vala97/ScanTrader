package com.scantrader.app.data.catalog

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.network.cardtrader.CardTraderApi
import com.scantrader.app.network.cardtrader.CtBlueprint
import com.scantrader.app.network.cardtrader.CtExpansion
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale

/**
 * Catalogo da CardTrader per i giochi senza un catalogo gratuito (One Piece, Dragon Ball Super Fusion World,
 * Yu-Gi-Oh!, Union Arena). Serve il token. Le carte si scaricano UN'ESPANSIONE ALLA VOLTA, quando lo scanner
 * legge un codice di quell'espansione, e restano sul telefono (rinnovate dopo [MAX_AGE_MS]).
 *
 * Il codice stampato porta all'espansione e al numero (vedi [com.scantrader.app.scan.OcrParser.ctCode]).
 * Su CardTrader lo stesso codice stampato puo' avere piu' versioni (One Piece `OP02-013` e `OP02-013a`
 * Alternate Art, promo dei tornei `ST01-004P6`, Dragon Ball `FB02-075a`, Union Arena `UE20BT-001 ★`):
 * in quel caso tornano tutte, con un punteggio che non salva da solo, e si sceglie dalle immagini.
 */
class CtGameCatalog(
    val game: Game,
    private val gameId: Int,
    private val api: CardTraderApi,
    private val token: () -> String?,
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Stored<T>(val at: Long = 0, val items: List<T> = emptyList())
    private val gson = Gson()
    private val mutex = Mutex()
    private var expansions: List<CtExpansion>? = null
    private val prints = mutableMapOf<Int, List<CtBlueprint>>()
    @Volatile var lastError: String? = null
        private set

    private fun file(name: String) = File(dir, name)
    private inline fun <reified T> read(name: String): Stored<T>? = runCatching {
        val f = file(name); if (!f.exists()) return null
        gson.fromJson<Stored<T>>(f.readText(), object : TypeToken<Stored<T>>() {}.type)
    }.getOrNull()
    private fun write(name: String, value: Any) = runCatching {
        dir.mkdirs(); val t = File(dir, "$name.tmp"); t.writeText(gson.toJson(value)); t.renameTo(file(name))
    }

    private fun needToken() {
        if (token()?.isBlank() != false) throw RiftboundCatalog.NoTokenException().also { lastError = it.message }
    }

    private suspend fun loadExpansions(): List<CtExpansion> {
        expansions?.let { return it }
        val cached = read<CtExpansion>("espansioni.json")
        if (cached != null && cached.items.isNotEmpty() && now() - cached.at < MAX_AGE_MS) return cached.items.also { expansions = it }
        if (token()?.isBlank() != false) { if (cached != null && cached.items.isNotEmpty()) return cached.items.also { expansions = it }; needToken() }
        val list = api.expansions().filter { it.gameId == gameId }
        write("espansioni.json", Stored(now(), list))
        expansions = list
        return list
    }

    private suspend fun loadPrints(exp: CtExpansion): List<CtBlueprint> {
        prints[exp.id]?.let { return it }
        val name = "carte-${exp.id}.json"
        val cached = read<CtBlueprint>(name)
        if (cached != null && cached.items.isNotEmpty() && now() - cached.at < MAX_AGE_MS) return cached.items.also { prints[exp.id] = it }
        if (token()?.isBlank() != false) { if (cached != null) return cached.items.also { prints[exp.id] = it }; needToken() }
        val list = api.blueprints(exp.id).filter { collector(it).isNotEmpty() }
        write(name, Stored(now(), list))
        prints[exp.id] = list
        return list
    }

    suspend fun identify(parsed: ParsedCardText, query: String? = null): List<IdentifiedCard> = mutex.withLock {
        try {
            val byCode = lookup(parsed)
            lastError = null
            val q = query?.trim().orEmpty()
            if (q.isNotEmpty() && (byCode.isEmpty() || byCode.none { key(it.name).contains(key(q)) })) {
                return@withLock searchByName(q).ifEmpty { byCode }
            }
            byCode
        } catch (e: RiftboundCatalog.NoTokenException) {
            throw e
        } catch (e: Exception) {
            lastError = "Catalogo ${game.shareLabel} non letto da CardTrader: ${e.message ?: e.javaClass.simpleName}."
            throw IllegalStateException(lastError, e)
        }
    }

    private suspend fun lookup(parsed: ParsedCardText): List<IdentifiedCard> {
        val code = parsed.setCode?.takeIf { it.isNotBlank() } ?: return emptyList()
        val number = parsed.collectorNumber?.takeIf { it.isNotBlank() } ?: return emptyList()
        val exps = loadExpansions()
        // l'espansione dal codice (senza punteggiatura: eb-01 = EB01), poi le sue versioni speciali (op02p, fb02p)
        val main = exps.filter { key(it.code) == key(code) }
        // Yu-Gi-Oh!: CardTrader a volte allunga il codice (YGLD -> ygld-2020)
        val slack = if (game == Game.YUGIOH) 8 else 3
        val extra = exps.filter { e -> main.none { it.id == e.id } && key(e.code).startsWith(key(code)) && key(e.code).length <= key(code).length + slack }
        val wantBase = base(number)
        val out = mutableListOf<Pair<CtExpansion, CtBlueprint>>()
        for (e in main + extra) for (b in loadPrints(e)) {
            val n = collector(b)
            if (n.equals(number, ignoreCase = true) || base(n) == wantBase) out += e to b
        }
        if (out.isEmpty()) return emptyList()
        // Union Arena: piu' carte con lo stesso numero (es. UE20BT-001 Diablo e Geld): decide il nome letto
        val narrowed = parsed.nameGuess?.let { nm ->
            val k = key(nm)
            out.filter { (_, b) -> k.length >= 3 && (key(b.name).contains(k) || k.contains(key(b.name))) }.ifEmpty { null }
        } ?: out
        // esatto (stesso numero, senza versioni): unico -> si puo' salvare da solo
        val exact = narrowed.filter { (_, b) -> collector(b).equals(number, ignoreCase = true) }
        val score = if (narrowed.size == 1) EXACT_SCORE else CHOOSE_SCORE
        val ordered = exact + narrowed.filter { it !in exact }
        // con piu' versioni il numero esatto (senza suffisso: la versione normale) resta il primo proposto
        return ordered.map { (e, b) -> b.toIdentified(e, if (narrowed.size > 1 && (e to b) in exact) CHOOSE_SCORE + 0.01f else score) }
    }

    /** Ricerca per nome nelle espansioni gia' scaricate di questo gioco (Cerca per nome della conferma). */
    fun searchByName(query: String, limit: Int = 12): List<IdentifiedCard> {
        val words = query.lowercase(Locale.ROOT).split(Regex("""[^\p{L}\p{N}]+""")).map(::key).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val exps = expansions.orEmpty().associateBy { it.id }
        return prints.flatMap { (id, list) -> list.map { exps[id] to it } }
            .filter { (e, b) -> e != null && words.all { key(b.name).contains(it) } }
            .sortedBy { it.second.name.length }
            .take(limit)
            .map { (e, b) -> b.toIdentified(e!!, CHOOSE_SCORE) }
    }

    private fun CtBlueprint.toIdentified(e: CtExpansion, score: Float): IdentifiedCard {
        val fixed = fixedProperties.orEmpty()
        val rarity = fixed.entries.firstOrNull { it.key.endsWith("_rarity") }?.value?.toString()?.takeIf { it.isNotBlank() }
        return IdentifiedCard(
            game = game,
            name = name.trim() + (version?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""),
            setCode = e.code.lowercase(Locale.ROOT),
            setName = e.name,
            collectorNumber = collector(this),
            imageUrl = imageUrl?.let { if (it.startsWith("http")) it else "https://www.cardtrader.com$it" },
            ocrScore = score,
            catalogName = name.trim(),
            ctBlueprintId = id,
            rarity = rarity,
            catalogLang = "en",
        )
    }

    companion object {
        const val MAX_AGE_MS = 14L * 24 * 3600 * 1000
        const val EXACT_SCORE = 0.97f
        /** Piu' versioni con lo stesso codice: niente salvataggio automatico, si sceglie. */
        const val CHOOSE_SCORE = 0.9f

        /** Gioco dell'app -> id del gioco su CardTrader. */
        val GAME_IDS = mapOf(Game.ONE_PIECE to 15, Game.DRAGON_BALL to 9, Game.YUGIOH to 4, Game.UNION_ARENA to 21)

        fun key(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "").lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]"""), "")

        fun collector(b: CtBlueprint): String = (b.fixedProperties?.get("collector_number") as? String)?.trim().orEmpty()

        /**
         * Numero stampato senza il suffisso delle versioni CardTrader: `OP02-013a` -> `op02013`,
         * `ST01-004P6` -> `st01004`, `FB02-075a` -> `fb02075`, `UE20BT-001 ★` -> `ue20bt001`, `003` -> `003`.
         */
        fun base(number: String): String {
            val n = number.trim().replace("★", "").trim()
            val m = Regex("""^(.*?\d{2,3})(?:[a-zA-Z]{1,3}\d?)?$""").find(n)
            return key(m?.groupValues?.get(1) ?: n)
        }
    }
}

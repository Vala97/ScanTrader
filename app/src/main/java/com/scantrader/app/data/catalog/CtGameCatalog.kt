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
        val list = api.blueprints(exp.id).filter { collector(it).isNotEmpty() || isDonName(it.name) }
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
        if (game == Game.ONE_PIECE && isDonQuery(number)) return lookupDon(code, number)
        val exps = loadExpansions()
        // l'espansione dal codice (senza punteggiatura: eb-01 = EB01), poi le sue versioni speciali (op02p, fb02p)
        val main = exps.filter { key(it.code) == key(code) }
        // Yu-Gi-Oh!: CardTrader a volte allunga il codice (YGLD -> ygld-2020)
        val slack = if (game == Game.YUGIOH) 8 else 3
        val extra = exps.filter { e -> main.none { it.id == e.id } && key(e.code).startsWith(key(code)) && key(e.code).length <= key(code).length + slack }
        // EB-01, EB-02, EB-03 e EB-05 hanno le single nella propria espansione.
        // EB-04 su CardTrader è solo sigillato: 011-041 stanno in OP-14, il resto in OP-15.
        val homeKeys = if (game == Game.ONE_PIECE) printedHomes(code, number).map(::key).toSet() else emptySet()
        val hosted = exps.filter { key(it.code) in homeKeys && main.none { m -> m.id == it.id } && extra.none { m -> m.id == it.id } }
        val wantBase = base(number)
        val out = mutableListOf<Pair<CtExpansion, CtBlueprint>>()
        for (e in main + extra + hosted) for (b in loadPrints(e)) {
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

    /**
     * DON!!: lo stesso set ne ha tanti (normale, gold, personaggio, maid, double pack) e ognuno ha il suo prezzo.
     * `OP-05` / `EB-05` / `EB-03` restituiscono tutti i tipi di quel set; `EB-03-NAMI` solo Nami e la sua gold.
     */
    private suspend fun lookupDon(code: String, number: String): List<IdentifiedCard> {
        if (number.equals("DON", ignoreCase = true)) return emptyList()
        val exps = loadExpansions()
        val homeKeys = donHomes(code, number).map(::key).toSet()
        val main = exps.filter { key(it.code) == key(code) }
        val extra = exps.filter { e -> main.none { it.id == e.id } && key(e.code) in homeKeys }
        val out = mutableListOf<Pair<CtExpansion, CtBlueprint>>()
        for (e in main + extra) for (b in loadPrints(e)) {
            if (!isDonName(b.name)) continue
            if (donCollectorMatches(collector(b), number)) out += e to b
        }
        if (out.isEmpty()) return emptyList()
        val exact = out.filter { (_, b) -> key(collector(b)) == key(number) }
        val score = if (out.size == 1) EXACT_SCORE else CHOOSE_SCORE
        val ordered = exact + out.filter { it !in exact }
        return ordered.map { (e, b) ->
            b.toIdentified(e, if (out.size > 1 && (e to b) in exact) CHOOSE_SCORE + 0.01f else score)
        }
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

        /** `DON!!` e `Don!!`: le carte personaggio (Donquixote, Gordon) non lo sono. */
        fun isDonName(name: String?): Boolean {
            val head = name?.substringBefore('(')?.trim().orEmpty()
            return head.isNotEmpty() && key(head) == "don"
        }

        /** Numero letto che non è una single (`OP02-013`): è una DON!!. */
        fun isDonQuery(number: String): Boolean {
            val n = number.trim()
            if (n.equals("DON", ignoreCase = true)) return true
            if (Regex("""^(OP|ST|EB|PRB)\d{2}-\d{3}[A-Za-z0-9]*$""", RegexOption.IGNORE_CASE).matches(n.replace(" ", ""))) return false
            return Regex("""^(PRB|DP|DF|TS|OP|ST|EB)[-A-Za-z0-9]*$""", RegexOption.IGNORE_CASE).matches(n)
        }

        /**
         * Stesso tipo, oppure tutti i tipi del set quando il codice letto è solo il set (`EB-05` → `EB-05g`, `EB-05m`).
         * `EB-03-NAMI` prende anche la gold `EB-03-NAMI-G`, non Robin.
         */
        fun donCollectorMatches(collector: String, wanted: String): Boolean {
            val c = key(collector)
            val w = key(wanted)
            if (c.isEmpty() || w.isEmpty()) return false
            if (c == w || c == w + "g") return true
            val setOnly = Regex("""^(op|st|eb|dp|df|ts)\d{2}$""").matches(w)
            return setOnly && c.startsWith(w)
        }

        /**
         * Dove sta una DON!! quando il codice stampato non è l'espansione
         * (`DP-09` in OP-14, `DP-01` nelle promo, `PRB-LUFFY` in PRB-01/02, `OP-11` nei championship).
         */
        fun donHomes(setCode: String, number: String): List<String> {
            val k = key(if (number.equals("DON", ignoreCase = true)) setCode else number)
            val vol = Regex("""^(?:dp|df|ts)(\d{2})""").find(k)?.groupValues?.get(1)?.toIntOrNull()
            if (vol != null) {
                return when (vol) {
                    1, 2 -> listOf("oppr")
                    3 -> if (k.startsWith("df")) listOf("oppr") else listOf("op06")
                    4 -> listOf("op07")
                    5 -> listOf("op08")
                    7 -> listOf("op11")
                    8 -> listOf("op12", "op12p")
                    9 -> listOf("op14", "op14p")
                    10 -> listOf("op15", "op15p")
                    11 -> listOf("op16", "op16p")
                    12 -> listOf("op17", "op17p")
                    else -> listOf("oppr")
                }
            }
            if (k.startsWith("prb") || key(setCode) == "prb") return listOf("prb01", "prb02")
            if (k == "op10") return listOf("oppr", "op10")
            if (k == "op11") return listOf("op-chp", "op11")
            return emptyList()
        }

        /**
         * Dove CardTrader ha messo le single quando il codice stampato non è l'espansione.
         * EB04-011…041 (Igaram, Bluegrass) sono in OP-14; EB04-001…010 e EB04-042…061 in OP-15.
         */
        fun printedHomes(setCode: String, collectorNumber: String): List<String> {
            if (key(setCode) != "eb04") return emptyList()
            val slot = Regex("""(\d{3})""").find(collectorNumber)?.groupValues?.get(1)?.toIntOrNull()
            return when {
                slot == null -> listOf("op14", "op15")
                slot in 11..41 -> listOf("op14", "op14p")
                else -> listOf("op15", "op15p")
            }
        }

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

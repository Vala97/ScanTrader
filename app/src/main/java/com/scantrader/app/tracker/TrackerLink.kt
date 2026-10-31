package com.scantrader.app.tracker

import android.content.Context
import com.scantrader.app.data.catalog.ExpansionCodes
import com.scantrader.app.data.db.stamped
import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.prefs.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Collegamento con il DLS Tracker (solo dentro Tailscale).
 *
 * Ogni carta salvata nello scanner va in un'uscita sul telefono con un codice unico e viene mandata al tracker
 * (`scan:queue`). Il tracker la mette su Cardtrader dopo 10 minuti con le Regole prezzi e la aggiunge al magazzino:
 * lo scanner non scrive mai su Cardtrader. Se la rete manca la carta resta nell'uscita e riparte da sola; se arriva
 * due volte il tracker la conta una volta sola (stesso codice). L'inventario mostrato e' quello vero di Cardtrader,
 * letto dal tracker (`scan:inventory`), con i soli annunci dell'utente.
 */
class TrackerLink(
    context: Context,
    private val secrets: SecretStore,
    private val scope: CoroutineScope,
    /** Database delle collezioni: "Manda al tracker" e le carte vendute da scalare. */
    private val database: com.scantrader.app.data.db.AppDatabase? = null,
) {
    private suspend fun cardsOf(collectionId: Long) =
        database?.cards()?.observeByCollection(collectionId)?.first().orEmpty()
    private val prefs = context.getSharedPreferences("dls_tracker", Context.MODE_PRIVATE)
    private val outboxFile = File(context.filesDir, "tracker/uscita.json")
    private val outboxLock = Mutex()
    private val cookies = mutableMapOf<String, Cookie>()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                synchronized(this@TrackerLink.cookies) { for (c in cookies) this@TrackerLink.cookies[c.name] = c }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                synchronized(this@TrackerLink.cookies) { this@TrackerLink.cookies.values.toList() }
        })
        .build()

    data class State(
        val linked: Boolean,
        val user: String,
        val sending: Boolean,
        val pending: Int,
        val lastError: String?,
        val lastSentAt: Long?,
    )

    private val _state = MutableStateFlow(readState(pending = 0))
    val state: StateFlow<State> = _state.asStateFlow()

    val url: String get() = prefs.getString("url", null) ?: DEFAULT_URL
    val user: String get() = prefs.getString("user", null).orEmpty()
    val linked: Boolean get() = user.isNotEmpty() && secrets.getTrackerPassword() != null
    var sending: Boolean
        get() = prefs.getBoolean("on", true)
        set(v) { prefs.edit().putBoolean("on", v).apply(); refresh(); if (v) flushSoon() }

    init {
        scope.launch {
            refresh(); var n = 0
            while (true) {
                runCatching { flush() }; runCatching { syncSold() }
                if (linked && n++ % 5 == 0) runCatching { inventory() } // collezione "Cardtrader" ogni 5 minuti
                delay(60_000)
            }
        }
    }

    private fun readState(pending: Int, error: String? = null) = State(
        linked = linked, user = user, sending = sending, pending = pending,
        lastError = error ?: prefs.getString("err", null), lastSentAt = prefs.getLong("sentAt", 0L).takeIf { it > 0 },
    )

    private fun refresh(error: String? = null) {
        if (error != null) prefs.edit().putString("err", error).apply()
        scope.launch { _state.value = readState(outbox().length(), error) }
    }

    // ---- accesso ----
    suspend fun link(user: String, password: String, url: String = this.url) {
        val u = user.trim().lowercase()
        prefs.edit().putString("user", u).putString("url", url.trim().trimEnd('/') + "/").apply()
        synchronized(cookies) { cookies.clear() }
        call("auth:login", JSONArray().put(u).put(password).put(true), retry = false)
        secrets.setTrackerPassword(password)
        prefs.edit().remove("err").apply()
        refresh()
        flushSoon()
    }

    fun unlink() {
        secrets.setTrackerPassword(null)
        prefs.edit().remove("user").remove("err").apply()
        synchronized(cookies) { cookies.clear() }
        refresh()
    }

    private suspend fun login() {
        val pw = secrets.getTrackerPassword() ?: throw IllegalStateException("Collega lo scanner al DLS Tracker.")
        call("auth:login", JSONArray().put(user).put(pw).put(true), retry = false)
    }

    /** Una chiamata all'API del tracker. Sessione scaduta (riavvio del sito): rientra da solo e riprova una volta. */
    private suspend fun call(name: String, args: JSONArray, retry: Boolean = true): Any? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("args", args).toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url + "api/" + name).post(body).header("X-DLS", "1").build()
        val json = http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("Il tracker ha risposto ${res.code}.")
            JSONObject(res.body?.string().orEmpty())
        }
        if (json.optBoolean("ok")) return@withContext json.opt("value")
        if (retry && json.optBoolean("expired")) { login(); return@withContext call(name, args, retry = false) }
        throw IllegalStateException(json.optString("error").ifBlank { "Errore del tracker." })
    }

    // ---- uscita: carte salvate da mandare al tracker ----
    private fun outbox(): JSONArray = runCatching { JSONArray(outboxFile.readText()) }.getOrElse { JSONArray() }
    private fun writeOutbox(a: JSONArray) {
        outboxFile.parentFile?.mkdirs()
        val tmp = File(outboxFile.path + ".tmp"); tmp.writeText(a.toString()); tmp.renameTo(outboxFile)
    }

    // Copie gia' mandate al tracker per ogni riga della collezione (id della riga -> copie): il pulsante
    // "Manda al tracker" manda solo quelle in piu', una volta sola.
    private val sentPrefs = context.getSharedPreferences("dls_tracker_mandate", Context.MODE_PRIVATE)
    fun sentFor(rowId: Long): Int = sentPrefs.getInt(rowId.toString(), 0)
    private fun markSent(rowId: Long, total: Int) { sentPrefs.edit().putInt(rowId.toString(), total).apply() }

    /** Chiamato quando una carta viene salvata in collezione (automatico o a mano). */
    fun cardSaved(card: IdentifiedCard, quantity: Int, language: CardLanguage, condition: CardCondition, foil: Boolean, rowId: Long? = null) {
        if (!linked || !sending) return
        val item = itemFor(card, quantity, language, condition, foil)
        if (rowId != null) { markSent(rowId, sentFor(rowId) + quantity); remember(item.getString("uid"), rowId, quantity) }
        scope.launch {
            outboxLock.withLock { val a = outbox(); a.put(item); writeOutbox(a) }
            refresh()
            runCatching { flush() }
        }
    }

    private fun flushSoon() { scope.launch { runCatching { flush() } } }

    /** Copie della collezione non ancora mandate al tracker (per il pulsante e la sua conferma). */
    suspend fun unsent(collectionId: Long): List<Pair<com.scantrader.app.data.db.CardEntryEntity, Int>> =
        if (collectionId == mirrorId) emptyList() // la collezione "Cardtrader" e' gia' su Cardtrader
        else cardsOf(collectionId).mapNotNull { e -> (e.quantity - sentFor(e.id)).takeIf { it > 0 }?.let { e to it } }

    /**
     * "Manda al tracker": manda le copie in piu' di ogni riga. Il codice di ogni invio e' fisso
     * (riga + copie dalla N alla M): premuto due volte, il tracker lo conta una volta sola.
     */
    suspend fun sendCollection(collectionId: Long): Int {
        if (!linked) throw IllegalStateException("Collega prima lo scanner al DLS Tracker.")
        val todo = unsent(collectionId)
        if (todo.isEmpty()) return 0
        outboxLock.withLock {
            val a = outbox()
            for ((e, extra) in todo) {
                val from = sentFor(e.id) + 1
                val uid = "coll-${e.id}-$from-${e.quantity}"
                a.put(itemForEntry(e, extra).put("uid", uid))
                remember(uid, e.id, extra)
            }
            writeOutbox(a)
            for ((e, _) in todo) markSent(e.id, e.quantity)
        }
        refresh()
        flushSoon()
        return todo.sumOf { it.second }
    }

    /** Manda l'uscita al tracker a gruppi di 50. Toglie solo le carte che il tracker ha accettato (o aveva gia'). */
    suspend fun flush() {
        if (!linked || !sending) return
        outboxLock.withLock {
            var a = outbox()
            if (a.length() == 0) return
            while (a.length() > 0) {
                val chunk = JSONArray(); for (i in 0 until minOf(50, a.length())) chunk.put(a.get(i))
                val res = try {
                    call("scan:queue", JSONArray().put(chunk)) as JSONObject
                } catch (e: Exception) {
                    refresh(e.message ?: "Tracker non raggiungibile (Tailscale acceso?)")
                    return
                }
                val done = mutableSetOf<String>()
                for (k in listOf("accepted", "duplicates")) res.optJSONArray(k)?.let { arr -> for (i in 0 until arr.length()) done += arr.getString(i) }
                val rest = JSONArray()
                for (i in 0 until a.length()) { val it = a.getJSONObject(i); if (it.getString("uid") !in done) rest.put(it) }
                if (rest.length() == a.length()) { refresh("Il tracker non ha accettato le carte."); return }
                a = rest; writeOutbox(a)
                prefs.edit().putLong("sentAt", System.currentTimeMillis()).remove("err").apply()
            }
        }
        refresh()
    }

    // ---- carte vendute: scalate dalle collezioni ----
    // collegamenti.json: codice dell'invio -> riga della collezione, copie e (quando il tracker l'ha caricata) annuncio.
    // vendite.json: per ogni vendita+annuncio quante copie sono gia' state tolte e da quali righe (per rimetterle se annullata).
    private val linksFile = File(context.filesDir, "tracker/collegamenti.json")
    private val appliedFile = File(context.filesDir, "tracker/vendite.json")
    private val soldLock = Mutex()
    private fun readObj(f: File) = runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() }
    private fun writeObj(f: File, o: JSONObject) { f.parentFile?.mkdirs(); val t = File(f.path + ".tmp"); t.writeText(o.toString()); t.renameTo(f) }
    @Synchronized private fun remember(uid: String, rowId: Long, qty: Int) {
        val o = readObj(linksFile)
        o.put(uid, JSONObject().put("row", rowId).put("qty", qty).put("at", System.currentTimeMillis()))
        writeObj(linksFile, o)
    }
    private val gson = com.google.gson.Gson()

    /**
     * Le vendite registrate nel tracker (Cardtrader, Cardmarket, a mano dal magazzino) tolgono le copie dalle righe
     * da cui erano partite. Ogni vendita si applica una volta sola (codice fisso); annullata, le copie tornano.
     * Solo le righe mandate dallo scanner: delle altre non si conosce l'annuncio.
     */
    suspend fun syncSold() {
        val db = database ?: return
        if (!linked) return
        soldLock.withLock {
            val links = readObj(linksFile)
            if (links.length() == 0) return
            // annunci delle carte caricate (dallo stato della coda nel tracker)
            if (links.keys().asSequence().any { !links.getJSONObject(it).has("pid") }) {
                val st = call("scan:status", JSONArray()) as? JSONArray ?: JSONArray()
                for (i in 0 until st.length()) {
                    val q = st.getJSONObject(i); val uid = q.optString("uid")
                    if (links.has(uid) && !q.isNull("pid") && q.optString("pid").isNotBlank()) links.getJSONObject(uid).put("pid", q.optString("pid"))
                }
                writeObj(linksFile, links)
            }
            val byPid = mutableMapOf<String, MutableList<JSONObject>>()
            for (k in links.keys()) { val l = links.getJSONObject(k); if (l.has("pid")) byPid.getOrPut(l.getString("pid")) { mutableListOf() } += l }
            if (byPid.isEmpty()) return
            for (rows in byPid.values) rows.sortBy { it.optLong("at") }
            val events = call("scan:sold", JSONArray()) as? JSONArray ?: JSONArray()
            val applied = readObj(appliedFile)
            for (i in 0 until events.length()) {
                val ev = events.getJSONObject(i)
                val rows = byPid[ev.optString("pid")] ?: continue
                val key = ev.getString("key")
                val rec = applied.optJSONObject(key) ?: JSONObject().put("q", 0).put("rows", JSONObject())
                val target = minOf(ev.optInt("q"), rows.sumOf { it.optInt("qty") })
                var delta = target - rec.optInt("q")
                if (delta == 0) continue
                val taken = rec.getJSONObject("rows")
                if (delta > 0) {
                    for (l in rows) {
                        if (delta <= 0) break
                        val id = l.getLong("row"); val e = db.cards().byId(id) ?: continue
                        val n = minOf(delta, e.quantity); if (n <= 0) continue
                        if (e.quantity - n <= 0) db.cards().delete(e) else db.cards().update(e.copy(quantity = e.quantity - n))
                        markSent(id, (sentFor(id) - n).coerceAtLeast(0))
                        val t = taken.optJSONObject(id.toString()) ?: JSONObject().put("n", 0).put("snap", gson.toJson(e))
                        t.put("n", t.optInt("n") + n); taken.put(id.toString(), t)
                        delta -= n
                    }
                    rec.put("q", target - delta)
                } else {
                    // vendita annullata: rimetto le copie nelle righe da cui le avevo tolte (ricreo la riga se non c'e' piu')
                    var back = -delta
                    for (id in taken.keys().asSequence().toList().reversed()) {
                        if (back <= 0) break
                        val t = taken.getJSONObject(id); val n = minOf(back, t.optInt("n")); if (n <= 0) continue
                        val e = db.cards().byId(id.toLong())
                        if (e != null) db.cards().update(e.copy(quantity = e.quantity + n))
                        else {
                            val snap = gson.fromJson(t.getString("snap"), com.scantrader.app.data.db.CardEntryEntity::class.java)
                            val same = db.cards().findByCopyKey(snap.collectionId, snap.copyKey)
                            if (same != null) db.cards().update(same.copy(quantity = same.quantity + n))
                            else db.cards().insert(snap.copy(quantity = n))
                        }
                        markSent(id.toLong(), sentFor(id.toLong()) + n)
                        t.put("n", t.optInt("n") - n); back -= n
                    }
                    rec.put("q", target + back)
                }
                applied.put(key, rec)
            }
            writeObj(appliedFile, applied)
        }
    }

    // ---- collezione "Cardtrader": i miei annunci, allineata da sola ----
    // Ogni riga ha variant = "cardtrader:<annuncio>": nuovi annunci entrano, venduti/tolti escono, quantita' e prezzo
    // si aggiornano. Non si manda mai al tracker (sono gia' su Cardtrader) e non e' una destinazione delle scansioni.
    val mirrorId: Long? get() = prefs.getLong("mirror", 0L).takeIf { it > 0 }
    private val mirrorLock = Mutex()
    private val CT_COND = mapOf("M" to "Mint", "NM" to "Near Mint", "SP" to "Slightly Played", "MP" to "Moderately Played", "PL" to "Played", "HP" to "Heavily Played", "PO" to "Poor")

    private suspend fun ensureMirror(db: com.scantrader.app.data.db.AppDatabase): Long {
        mirrorId?.let { id -> if (collectionExists(db, id)) return id }
        val name = if (db.collections().idWithName("Cardtrader") == null) "Cardtrader" else "Cardtrader (dal tracker)"
        val id = db.collections().idWithName(name) ?: db.collections().insert(com.scantrader.app.data.db.CollectionEntity(name = name))
        prefs.edit().putLong("mirror", id).apply()
        return id
    }
    private suspend fun collectionExists(db: com.scantrader.app.data.db.AppDatabase, id: Long) =
        db.collections().observeAll().first().any { it.id == id }

    /** Allinea la collezione "Cardtrader" all'inventario letto dal tracker. */
    suspend fun syncMirror(inv: Inventory) {
        val db = database ?: return
        mirrorLock.withLock {
            val cid = ensureMirror(db)
            val rows = db.cards().observeByCollection(cid).first()
            val byPid = rows.filter { it.variant?.startsWith("cardtrader:") == true }.associateBy { it.variant!!.removePrefix("cardtrader:") }
            if (inv.items.isEmpty() && byPid.isNotEmpty()) return // elenco vuoto: non svuoto la collezione per un errore
            val live = inv.items.associateBy { it.id }
            for ((pid, row) in byPid) if (pid !in live) db.cards().delete(row) // venduto o tolto da Cardtrader
            for (it in inv.items) {
                val want = com.scantrader.app.data.db.CardEntryEntity(
                    collectionId = cid,
                    game = Game.fromCardTrader(it.game),
                    name = it.name,
                    setCode = it.expCode,
                    setName = it.exp,
                    collectorNumber = it.no,
                    scryfallId = null,
                    tcgplayerId = null,
                    imageUrl = null,
                    quantity = it.qty,
                    language = it.lang.ifBlank { "English" },
                    condition = CT_COND[it.cond] ?: it.cond.ifBlank { "Near Mint" },
                    foil = it.foil,
                    priceEur = String.format(java.util.Locale.ROOT, "%.2f", it.price),
                    priceCurrency = "EUR",
                    priceSource = "CardTrader",
                    catalogName = it.name,
                    variant = "cardtrader:" + it.id,
                ).stamped()
                val cur = byPid[it.id]
                if (cur == null) runCatching { db.cards().insert(want) }
                else if (cur.quantity != want.quantity || cur.priceEur != want.priceEur || cur.condition != want.condition ||
                    cur.language != want.language || cur.foil != want.foil || cur.name != want.name || cur.setName != want.setName
                ) runCatching { db.cards().update(want.copy(id = cur.id, createdAt = cur.createdAt, manualPriceEur = cur.manualPriceEur)) }
            }
        }
    }

    // ---- inventario ----
    data class InvItem(
        val id: String, val game: String, val exp: String, val expCode: String, val name: String,
        val no: String, val qty: Int, val price: Double, val cond: String, val lang: String, val foil: Boolean,
    )
    data class QueueItem(
        val uid: String, val name: String, val exp: String, val no: String, val qty: Int, val foil: Boolean,
        val cond: String, val state: String, val due: Long, val err: String, val price: Double?,
    )
    data class Inventory(val items: List<InvItem>, val queue: List<QueueItem>, val at: String)

    suspend fun inventory(): Inventory {
        runCatching { flush() }
        runCatching { syncSold() }
        val v = call("scan:inventory", JSONArray()) as JSONObject
        val items = v.optJSONArray("items") ?: JSONArray()
        val queue = v.optJSONArray("queue") ?: JSONArray()
        return Inventory(
            items = (0 until items.length()).map { i ->
                val o = items.getJSONObject(i)
                InvItem(o.optString("id"), o.optString("game"), o.optString("exp"), o.optString("expCode"), o.optString("name"),
                    o.optString("no"), o.optInt("q"), o.optDouble("price", 0.0), o.optString("cond"), o.optString("lang"), o.optBoolean("foil"))
            },
            queue = (0 until queue.length()).map { i ->
                val o = queue.getJSONObject(i)
                QueueItem(o.optString("uid"), o.optString("name"), o.optString("exp"), o.optString("no"), o.optInt("qty"), o.optBoolean("foil"),
                    o.optString("cond"), o.optString("st"), o.optLong("due"), o.optString("err"), if (o.isNull("price")) null else o.optDouble("price"))
            },
            at = v.optString("at"),
        ).also { inv -> runCatching { syncMirror(inv) } }
    }

    suspend fun removeQueued(uid: String): Boolean = call("scan:remove", JSONArray().put(uid)) == true
    suspend fun uploadNow(uid: String): Boolean = call("scan:now", JSONArray().put(uid)) == true

    companion object {
        /** dls-tracker dentro Tailscale (http: il sito non esce dalla rete privata). */
        const val DEFAULT_URL = ""

        fun itemForEntry(e: com.scantrader.app.data.db.CardEntryEntity, quantity: Int): JSONObject {
            val lang = CardLanguage.entries.firstOrNull { it.csvValue == e.language } ?: CardLanguage.ENGLISH
            val cond = CardCondition.entries.firstOrNull { it.csvValue == e.condition } ?: CardCondition.NEAR_MINT
            val card = IdentifiedCard(game = e.game, name = e.name, setCode = e.setCode, setName = e.setName, collectorNumber = e.collectorNumber, catalogName = e.catalogName)
            return itemFor(card, quantity, lang, cond, e.foil)
        }

        fun itemFor(card: IdentifiedCard, quantity: Int, language: CardLanguage, condition: CardCondition, foil: Boolean): JSONObject {
            val exp = when (card.game) {
                Game.MTG -> ExpansionCodes.mtg(card.setCode)
                Game.POKEMON -> ExpansionCodes.pokemon(card.setCode)
                Game.RIFTBOUND -> card.setCode.lowercase()
                Game.ONE_PIECE, Game.DRAGON_BALL, Game.YUGIOH, Game.UNION_ARENA, Game.OTHER -> card.setCode.lowercase()
            }
            return JSONObject()
                .put("uid", "scan-" + UUID.randomUUID().toString())
                .put("game", card.game.csvLabel)
                .put("bp", card.ctBlueprintId ?: JSONObject.NULL)
                .put("exp", exp)
                .put("no", card.collectorNumber)
                .put("name", card.catalogName?.takeIf { it.isNotBlank() } ?: card.name)
                .put("qty", quantity.coerceIn(1, 99))
                .put("cond", if (condition == CardCondition.DAMAGED) "Poor" else condition.csvValue)
                .put("lang", language.cardTraderLang())
                .put("foil", foil && card.game.foilChoice)
        }
    }
}

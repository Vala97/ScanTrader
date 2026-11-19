package com.scantrader.app.data.catalog.language

import com.scantrader.app.data.model.CardLanguage
import java.text.Normalizer
import java.util.Locale

/**
 * Words printed on a Magic card (type line and rules text) scored per language.
 * The card name is never part of this text: the caller drops the title line first.
 *
 * Type-line words weigh most. A word shared by two languages (`creature` is the
 * English type and the Italian plural, `criatura` is Spanish and Portuguese)
 * counts for both, so it never separates them. Words that are the same in every
 * language we read (Aura, Saga, Planeswalker, mana) are listed in [NEUTRAL] and
 * score nothing.
 *
 * Old frames (before 2014) have no language next to the collector number, so the
 * old type words are here too: `Evoca ~`, `Incanta ~`, `Interruzione`, `Invocar ~`,
 * `Summon ~`, `Interrupt`.
 */
object MagicLexicon {
    private val IT = setOf(CardLanguage.ITALIAN)
    private val EN = setOf(CardLanguage.ENGLISH)
    private val ES = setOf(CardLanguage.SPANISH)
    private val FR = setOf(CardLanguage.FRENCH)
    private val DE = setOf(CardLanguage.GERMAN)
    private val PT = setOf(CardLanguage.PORTUGUESE)
    private val EN_IT = setOf(CardLanguage.ENGLISH, CardLanguage.ITALIAN)
    private val EN_DE = setOf(CardLanguage.ENGLISH, CardLanguage.GERMAN)
    private val IT_ES = setOf(CardLanguage.ITALIAN, CardLanguage.SPANISH)
    private val IT_PT = setOf(CardLanguage.ITALIAN, CardLanguage.PORTUGUESE)
    private val ES_PT = setOf(CardLanguage.SPANISH, CardLanguage.PORTUGUESE)

    /** Same word in Italian, English and Spanish prints: never a language clue. */
    val NEUTRAL: Set<String> = setOf("aura", "saga", "planeswalker", "mana", "zombie", "elfo", "goblin")

    data class Entry(
        val languages: Set<CardLanguage>,
        /** Folded (lowercase, no accents) word or phrase. */
        val term: String,
        val weight: Int,
        /** Match the accented original instead of the folded text (`créature`). */
        val accented: Boolean = false,
    ) {
        val phrase: Boolean get() = term.contains(' ')
    }

    private fun words(languages: Set<CardLanguage>, weight: Int, vararg terms: String) =
        terms.map { Entry(languages, it, weight) }

    const val TYPE_WEIGHT = 6

    val entries: List<Entry> = buildList {
        // Italian: type line, modern and old frames.
        addAll(
            words(
                IT, TYPE_WEIGHT,
                "creatura", "stregoneria", "istantaneo", "incantesimo", "artefatto",
                "leggendario", "leggendaria", "equipaggiamento", "viandante", "interruzione",
                "evoca", "terra base",
            ),
        )
        addAll(words(IT, 5, "terra", "leggenda", "incanta", "incantare", "tribale"))
        addAll(words(IT, 4, "veicolo", "battaglia", "assedio", "foresta", "isola", "montagna", "palude", "pianura"))
        addAll(
            words(
                IT, 3,
                "umano", "guerriero", "soldato", "chierico", "spirito", "cavaliere", "stregone", "sciamano", "drago",
            ),
        )
        // Italian: rules text.
        addAll(
            words(
                IT, 4,
                "bersaglio", "ogniqualvolta", "giocatore", "avversario", "controlli", "cimitero",
                "segnalino", "segnalini", "volare", "travolgere", "tocco letale", "legame vitale",
                "fino alla fine del turno", "pesca una carta", "campo di battaglia", "punti vita",
                "danno", "danni", "mazzo", "la tua mano",
            ),
        )
        addAll(
            words(
                IT, 3,
                "tappa", "stappa", "rapidita", "cautela", "difensore", "puoi", "ogni", "lancia",
                "magia", "magie", "aggiungi", "prende", "infligge",
            ),
        )
        addAll(words(IT, 2, "della", "delle", "dello", "degli", "che", "il", "gli", "tuo", "tua", "tuoi", "tue"))

        // English.
        addAll(
            words(
                EN, TYPE_WEIGHT,
                "sorcery", "instant", "enchantment", "artifact", "legendary", "equipment",
                "summon", "interrupt", "basic land",
            ),
        )
        addAll(words(EN, 5, "enchant", "legend"))
        addAll(words(EN, 4, "vehicle", "forest", "island", "mountain", "swamp", "plains"))
        addAll(words(EN, 3, "human", "warrior", "soldier", "cleric", "spirit", "knight", "wizard", "beast", "battle"))
        addAll(
            words(
                EN, 4,
                "target", "whenever", "battlefield", "graveyard", "opponent", "player", "you control",
                "until end of turn", "draw a card", "flying", "trample", "deathtouch", "lifelink",
                "damage", "your hand", "life",
            ),
        )
        addAll(
            words(
                EN, 3,
                "when", "haste", "vigilance", "sacrifice", "exile", "tap", "untap", "each", "may",
                "gets", "deals", "spell", "creatures",
            ),
        )
        addAll(words(EN, 2, "the", "your", "you", "and"))

        // Spanish.
        addAll(
            words(
                ES, TYPE_WEIGHT,
                "conjuro", "instantaneo", "encantamiento", "artefacto", "legendario", "legendaria",
                "equipo", "interrupcion", "tierra basica",
            ),
        )
        addAll(words(ES, 5, "tierra", "leyenda", "caminante de planos"))
        addAll(words(ES, 4, "vehiculo", "batalla", "bosque", "isla", "montana", "pantano", "llanura"))
        addAll(
            words(
                ES, 3,
                "humano", "guerrero", "soldado", "clerigo", "espiritu", "caballero", "hechicero", "dragon",
            ),
        )
        addAll(
            words(
                ES, 4,
                "objetivo", "jugador", "controlas", "cementerio", "contador", "contadores", "vuela",
                "volar", "arrollar", "toque mortal", "vinculo vital", "hasta el final del turno",
                "roba una carta", "campo de batalla", "siempre que", "dano", "puntos de vida", "tu mano",
            ),
        )
        addAll(
            words(
                ES, 3,
                "cuando", "prisa", "vigilancia", "girar", "enderezar", "gira", "puedes", "cada",
                "hechizo", "obtiene", "agrega", "anade", "inflige",
            ),
        )
        addAll(words(ES, 2, "los", "las", "el", "tus"))

        // Shared words: they count for every language listed, so they never separate those.
        add(Entry(EN_IT, "creature", 3))
        add(Entry(EN_DE, "land", 5))
        add(Entry(ES_PT, "criatura", TYPE_WEIGHT))
        add(Entry(ES_PT, "invocar", TYPE_WEIGHT))
        add(Entry(ES_PT, "encantar", 5))
        add(Entry(ES_PT, "oponente", 4))
        add(Entry(IT_ES, "sacrifica", 3))
        add(Entry(IT_ES, "esilia", 3))
        add(Entry(IT_ES, "del", 1))
        add(Entry(IT_ES, "una", 1))
        add(Entry(IT_ES, "tu", 1))
        add(Entry(IT_PT, "quando", 3))
        add(Entry(setOf(CardLanguage.ITALIAN, CardLanguage.SPANISH, CardLanguage.PORTUGUESE), "carta", 1))
        add(
            Entry(
                setOf(CardLanguage.ITALIAN, CardLanguage.SPANISH, CardLanguage.PORTUGUESE),
                "turno",
                1,
            ),
        )

        // French, German, Portuguese: enough to keep them from scoring as another language.
        add(Entry(FR, "créature", TYPE_WEIGHT, accented = true))
        addAll(
            words(
                FR, TYPE_WEIGHT,
                "rituel", "ephemere", "enchantement", "artefact", "terrain", "legendaire", "invoquer",
            ),
        )
        addAll(words(FR, 4, "jusqu a la fin du tour", "cible", "joueur", "adversaire", "vous controlez"))
        addAll(
            words(
                DE, TYPE_WEIGHT,
                "kreatur", "hexerei", "spontanzauber", "verzauberung", "artefakt", "legendare",
            ),
        )
        addAll(words(DE, 4, "bis zum ende des zuges", "spieler", "gegner", "kontrollierst"))
        addAll(
            words(
                PT, TYPE_WEIGHT,
                "feitico", "magica instantanea", "encantamento", "artefato", "terreno", "lendario", "lendaria",
            ),
        )
        addAll(words(PT, 4, "ate o final do turno", "jogador", "voce controla", "alvo"))
    }

    data class Score(
        val totals: Map<CardLanguage, Int>,
        val hits: Map<CardLanguage, List<String>>,
    ) {
        val ranked: List<Pair<CardLanguage, Int>> get() = totals.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
        val best: Pair<CardLanguage, Int>? get() = ranked.firstOrNull()
        val runnerUp: Int get() = ranked.getOrNull(1)?.second ?: 0
    }

    private val singleWords: Map<String, List<Entry>> =
        entries.filter { !it.phrase && !it.accented }.groupBy { it.term }

    fun score(text: String): Score {
        val folded = fold(text)
        val tokens = folded.split(' ').filter { it.isNotEmpty() }
        val padded = " $folded "
        val rawLower = text.lowercase(Locale.ROOT)
        val matched = LinkedHashSet<Entry>()
        for (token in tokens) {
            if (token in NEUTRAL) continue
            val exact = singleWords[token]
            if (exact != null) {
                matched += exact
                continue
            }
            fuzzy(token)?.let { matched += it }
        }
        for (entry in entries) {
            if (entry.accented) {
                if (Regex("""(?<!\p{L})${Regex.escape(entry.term)}(?!\p{L})""").containsMatchIn(rawLower)) {
                    matched += entry
                }
            } else if (entry.phrase && padded.contains(" ${entry.term} ")) {
                matched += entry
            }
        }
        // An accented French `créature` is not also the English/Italian `creature`.
        if (matched.any { it.accented && it.term == "créature" }) {
            matched.removeAll { it.term == "creature" && !it.accented }
        }
        val totals = mutableMapOf<CardLanguage, Int>()
        val hits = mutableMapOf<CardLanguage, MutableList<String>>()
        for (entry in matched) {
            for (language in entry.languages) {
                totals[language] = (totals[language] ?: 0) + entry.weight
                hits.getOrPut(language) { mutableListOf() } += entry.term
            }
        }
        return Score(totals, hits)
    }

    /**
     * One OCR slip on a long word (`stregonena`, `istantanco`). Only a single
     * nearest entry counts: `creaturo` is one edit from both `creature` and
     * `creatura`, so it counts for neither.
     */
    private fun fuzzy(token: String): List<Entry>? {
        if (token.length < 7) return null
        val near = singleWords.keys.filter { it.length >= 7 && kotlin.math.abs(it.length - token.length) <= 1 }
            .filter { distanceAtMostOne(it, token) }
        if (near.size != 1) return null
        return singleWords[near.first()]
    }

    private fun distanceAtMostOne(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length == b.length) return a.indices.count { a[it] != b[it] } == 1
        val (short, long) = if (a.length < b.length) a to b else b to a
        if (long.length - short.length != 1) return false
        var i = 0
        var j = 0
        var skipped = false
        while (i < short.length && j < long.length) {
            if (short[i] == long[j]) {
                i++
                j++
            } else {
                if (skipped) return false
                skipped = true
                j++
            }
        }
        return true
    }

    /** Lowercase, no accents, letters and digits only, single spaces. */
    fun fold(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
}

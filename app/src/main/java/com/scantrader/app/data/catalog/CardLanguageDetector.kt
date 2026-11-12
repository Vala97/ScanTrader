package com.scantrader.app.data.catalog

import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import java.text.Normalizer
import java.util.Locale

/**
 * Picks the confirm-screen language.
 * Magic compares the OCR title with printed names.
 * Pokémon also reads rules words and script on the card (stage, weakness, evolve-from).
 * A tie or a weak score falls back to the last choice.
 */
object CardLanguageDetector {
    data class Detection(
        val language: CardLanguage?,
        val evidence: String,
    ) {
        /** Logcat message. The tag is [com.scantrader.app.scan.ScanDebug.TAG], so the filter is `ScanTrader lang:`. */
        fun logLine(): String {
            val picked = language?.label ?: "uncertain"
            return "lang: $picked evidence=${evidence.ifBlank { "none" }}"
        }
    }

    fun choose(
        detectEnabled: Boolean,
        detected: CardLanguage?,
        lastChosen: CardLanguage,
    ): CardLanguage = if (detectEnabled && detected != null) detected else lastChosen

    /** Cap while the rules strip is still being read. Past this, detection uses the text it has. */
    const val RULES_WAIT_CAP_MS = 2_500L

    /**
     * Pokémon rules text is what separates languages that share a title.
     * A title that already proves the language (Hangul, for example) does not wait.
     * Other games wait only while a rules pass is actually in flight.
     */
    fun shouldWaitForRules(
        game: Game,
        ocrName: String?,
        ocrText: String?,
        rulesPending: Boolean,
        waitedMs: Long,
        capMs: Long = RULES_WAIT_CAP_MS,
    ): Boolean {
        if (waitedMs >= capMs) return false
        if (game == Game.POKEMON && provesLanguage(ocrName, ocrText)) return false
        return rulesPending
    }

    /** True when the title or the text already on hand is enough, without the rules strip. */
    fun provesLanguage(ocrName: String?, ocrText: String?): Boolean {
        val text = listOfNotNull(ocrName, ocrText).filter { it.isNotBlank() }.joinToString("\n")
        return detect(
            ocrName = ocrName,
            englishName = null,
            printed = emptyMap(),
            ocrText = text,
            pokemonSignals = true,
        ) != null
    }

    /**
     * A failed, cancelled, or uncertain lookup must not replace a language already accepted.
     * A new confident detection still wins.
     */
    fun retainConfident(previous: CardLanguage?, incoming: Detection): Detection {
        if (incoming.language != null || previous == null) return incoming
        return Detection(previous, "kept")
    }

    fun detect(
        ocrName: String?,
        englishName: String?,
        printed: Map<CardLanguage, String>,
        ocrText: String? = null,
        pokemonSignals: Boolean = false,
        collectorLang: String? = null,
    ): CardLanguage? = explain(ocrName, englishName, printed, ocrText, pokemonSignals, collectorLang).language

    /**
     * Pokémon rules text wins when the printed name is the same in every language
     * we know (Machop, Pikachu). A title that differs across languages still wins.
     */
    fun explain(
        ocrName: String?,
        englishName: String?,
        printed: Map<CardLanguage, String>,
        ocrText: String? = null,
        pokemonSignals: Boolean = false,
        collectorLang: String? = null,
    ): Detection {
        val named = pickName(ocrName, englishName, printed)
        val cardText = if (pokemonSignals) {
            scoreCardText(listOfNotNull(ocrText, ocrName).filter { it.isNotBlank() }.joinToString("\n"))
        } else {
            null
        }
        val nameProvesLanguage = named != null &&
            (!pokemonSignals || nameDistinguishes(ocrName, englishName, printed, named))
        val proven = named?.takeIf { nameProvesLanguage }
        val language = when {
            cardText?.language != null && proven == null -> cardText.language
            proven != null -> proven.language
            else -> null
        }
        if (language == null && !pokemonSignals) {
            trustedCollector(collectorLang, ocrName, ocrText, printed)?.let { return it }
            inferPrintedLanguage(
                ocrName = ocrName,
                englishName = englishName,
                italianTitle = printed[CardLanguage.ITALIAN],
                collectorLang = collectorLang,
            )?.let { return it }
        }
        val evidence = buildList {
            val labels = when {
                language != null && cardText?.language == language -> cardText.labels
                language == null -> cardText?.every.orEmpty()
                else -> emptyList()
            }
            addAll(labels)
            when {
                pokemonSignals && proven == null -> add("name=shared")
                proven != null -> add("name=${proven.language.scryfallLang()}")
            }
        }.distinct().joinToString(",")
        return Detection(language, evidence)
    }

    fun normalize(raw: String?): String? {
        val text = raw?.trim()?.lowercase(Locale.ROOT)
            ?.replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
        return text?.takeIf { it.length >= 3 }
    }

    private data class NamePick(val language: CardLanguage, val exact: Boolean)

    private fun pickName(
        ocrName: String?,
        englishName: String?,
        printed: Map<CardLanguage, String>,
    ): NamePick? {
        val ocr = normalize(ocrName) ?: return null
        val scored = buildList {
            if (!englishName.isNullOrBlank()) add(CardLanguage.ENGLISH to score(ocr, englishName))
            printed.forEach { (language, title) ->
                if (language != CardLanguage.ENGLISH && title.isNotBlank()) {
                    add(language to score(ocr, title))
                }
            }
        }
        val best = scored.maxByOrNull { it.second } ?: return null
        if (best.second < MIN_SCORE) return null
        val runnerUp = scored.filter { it.first != best.first }.maxOfOrNull { it.second } ?: 0f
        if (best.second < 1f && best.second - runnerUp < MIN_GAP) return null
        if (best.second >= 1f && runnerUp >= 1f) return null
        return NamePick(best.first, exact = best.second >= 1f)
    }

    /**
     * True when one language's title matches the OCR and another known title does not.
     * A single shared species name (only "Machop", or Italian and English both "Machop")
     * does not prove the print language.
     */
    private fun nameDistinguishes(
        ocrName: String?,
        englishName: String?,
        printed: Map<CardLanguage, String>,
        named: NamePick,
    ): Boolean {
        if (!named.exact) return false
        val ocr = normalize(ocrName) ?: return false
        val titles = buildList {
            normalize(englishName)?.let { add(it) }
            printed.values.forEach { title -> normalize(title)?.let { add(it) } }
        }.distinct()
        if (titles.isEmpty()) return false
        if (titles.size == 1 && titles.first() == ocr) return false
        return titles.any { it != ocr }
    }

    /**
     * The physical copy's language, written onto [IdentifiedCard.printedLang].
     * The English catalog name, id, and price stay on the card: Morningtide has
     * no Italian Scryfall object, but the title printed on the card is still Italian.
     */
    fun applyCopyLanguage(card: IdentifiedCard, ocrName: String?, collectorLang: String? = null): IdentifiedCard =
        resolveCopyLanguage(card, ocrName, collectorLang).card

    data class CopyLanguage(val card: IdentifiedCard, val detection: Detection)

    fun resolveCopyLanguage(
        card: IdentifiedCard,
        ocrName: String?,
        collectorLang: String? = null,
        ocrText: String? = null,
    ): CopyLanguage {
        if (card.game != Game.MTG) return CopyLanguage(card, Detection(null, "none"))
        val english = card.catalogName?.takeIf { it.isNotBlank() }
            ?: card.name.takeUnless { it.equals(card.printedName, ignoreCase = true) }
        val printed = linkedMapOf<CardLanguage, String>()
        val known = card.printedLang?.let { CardLanguage.fromCatalogCode(it) }
        if (known != null && known != CardLanguage.ENGLISH && !card.printedName.isNullOrBlank()) {
            printed[known] = card.printedName
        }
        val detection = explain(ocrName, english, printed, ocrText = ocrText, collectorLang = collectorLang)
        val token = CardLanguage.fromCatalogCode(collectorLang.orEmpty())
        val englishTitle = detection.language == CardLanguage.ENGLISH &&
            detection.evidence.contains("name=en")
        val englishToken = token == CardLanguage.ENGLISH
        val namedOtherLanguage = detection.language != null &&
            detection.language != CardLanguage.ENGLISH &&
            detection.evidence.contains("name=")
        if ((englishTitle || englishToken) && !namedOtherLanguage) {
            val chosen = if (detection.language == CardLanguage.ENGLISH) {
                detection
            } else {
                Detection(
                    CardLanguage.ENGLISH,
                    listOfNotNull(detection.evidence.ifBlank { null }, "collector=en").joinToString(","),
                )
            }
            val stamped = if (card.printedLang.equals("en", ignoreCase = true)) card else card.copy(printedLang = "en")
            return CopyLanguage(stamped, chosen)
        }
        val code = detection.language?.scryfallLang() ?: return CopyLanguage(card, detection)
        if (code.equals(card.printedLang, ignoreCase = true)) return CopyLanguage(card, detection)
        if (!card.printedLang.isNullOrBlank() && detection.language == CardLanguage.ENGLISH) {
            return CopyLanguage(card, detection)
        }
        return CopyLanguage(card.copy(printedLang = code), detection)
    }

    /**
     * Magic collector token (`HOB • EN`). EN and the other codes are the print
     * language. A bare IT or ES is not: OCR mixes those two up, so the title or
     * the rules have to agree.
     */
    private fun trustedCollector(
        collectorLang: String?,
        ocrName: String?,
        ocrText: String?,
        printed: Map<CardLanguage, String>,
    ): Detection? {
        val token = CardLanguage.fromCatalogCode(collectorLang ?: return null) ?: return null
        val supported = when (token) {
            CardLanguage.ITALIAN -> agreesWithTitle(ocrName, printed[CardLanguage.ITALIAN]) ||
                italianTextSupport(ocrName, ocrText)
            CardLanguage.SPANISH -> agreesWithTitle(ocrName, printed[CardLanguage.SPANISH]) ||
                spanishTextSupport(ocrName, ocrText)
            else -> true
        }
        if (!supported) return null
        return Detection(token, "collector=${token.scryfallLang()}")
    }

    private fun agreesWithTitle(ocrName: String?, title: String?): Boolean {
        val ocr = normalize(ocrName) ?: return false
        if (title.isNullOrBlank()) return false
        return score(ocr, title) >= MIN_SCORE
    }

    /** `zz` or a real rules word. A lone `di` / `del` is not support for an IT token. */
    private fun italianTextSupport(ocrName: String?, ocrText: String?): Boolean {
        val blob = listOfNotNull(ocrName, ocrText).filter { it.isNotBlank() }.joinToString("\n")
        if (blob.isBlank()) return false
        if (ENGLISH_RULES.containsMatchIn(blob)) return false
        if (ITALIAN_RULES.containsMatchIn(blob)) return true
        val labels = cueLabels(CardLanguage.ITALIAN, ocrName, ocrText)
        if (labels.isEmpty()) return false
        return labels.any { it != "di" && it != "del" }
    }

    private fun spanishTextSupport(ocrName: String?, ocrText: String?): Boolean {
        val raw = listOfNotNull(ocrName, ocrText).filter { it.isNotBlank() }.joinToString("\n")
        if (raw.isEmpty()) return false
        if (ENGLISH_RULES.containsMatchIn(raw)) return false
        if (raw.contains('ñ') || raw.contains('Ñ')) return true
        if (SPANISH_RULES.containsMatchIn(raw)) return true
        return Regex("""(?i)\b(?:los|las)\b""").containsMatchIn(raw)
    }

    private fun cueLabels(language: CardLanguage, ocrName: String?, ocrText: String?): List<String> {
        val blob = listOfNotNull(ocrName, ocrText).filter { it.isNotBlank() }.joinToString("\n")
        if (blob.isBlank()) return emptyList()
        val raw = blob.lowercase(Locale.ROOT)
        val folded = fold(raw)
        return titleCues.mapNotNull { cue ->
            if (cue.language != language) return@mapNotNull null
            val haystack = if (cue.folded) folded else raw
            if (cue.pattern.containsMatchIn(haystack)) cue.label else null
        }.distinct()
    }

    /**
     * OCR title vs the English oracle name when Scryfall has no localized printing.
     * `Druido del Suolo Avvizzito` is not `Blightsoil Druid`. A title word that is
     * also a rules word is not enough; Italian needs a real function word, and
     * `del` only counts together with Italian spelling (`avvizzito`).
     */
    private fun inferPrintedLanguage(
        ocrName: String?,
        englishName: String?,
        italianTitle: String?,
        collectorLang: String?,
    ): Detection? {
        val ocr = normalize(ocrName) ?: return null
        if (englishName.isNullOrBlank()) return null
        if (score(ocr, englishName) >= MIN_SCORE) return null
        val raw = ocrName.orEmpty().lowercase(Locale.ROOT)
        val folded = fold(raw)
        val scores = mutableMapOf<CardLanguage, Int>()
        val cues = mutableMapOf<CardLanguage, MutableList<String>>()
        for (cue in titleCues) {
            val haystack = if (cue.folded) folded else raw
            if (!cue.pattern.containsMatchIn(haystack)) continue
            scores[cue.language] = (scores[cue.language] ?: 0) + cue.weight
            val found = cues.getOrPut(cue.language) { mutableListOf() }
            if (cue.label !in found) found += cue.label
        }
        if (scores[CardLanguage.ITALIAN] != null &&
            !italianCueAllowed(cues[CardLanguage.ITALIAN].orEmpty(), ocrName, italianTitle, collectorLang)
        ) {
            scores.remove(CardLanguage.ITALIAN)
        }
        val best = scores.maxByOrNull { it.value } ?: return null
        val runner = scores.filterKeys { it != best.key }.maxOfOrNull { it.value } ?: 0
        if (best.value < 5 || (runner > 0 && best.value - runner < 2)) return null
        val labels = cues[best.key].orEmpty().joinToString("+")
        return Detection(
            best.key,
            "title=${best.key.scryfallLang()} not-en cue=$labels",
        )
    }

    /**
     * A lone `di` (or `del`) is not Italian when the collector line says EN, and
     * it is not Italian just because the word appeared. With a Scryfall Italian
     * title, the OCR has to agree with that title.
     */
    private fun italianCueAllowed(
        labels: List<String>,
        ocrName: String?,
        italianTitle: String?,
        collectorLang: String?,
    ): Boolean {
        val weakOnly = labels.isNotEmpty() && labels.all { it == "di" || it == "del" }
        val token = CardLanguage.fromCatalogCode(collectorLang.orEmpty())
        if (token == CardLanguage.ENGLISH && weakOnly) return false
        if (!italianTitle.isNullOrBlank()) return agreesWithTitle(ocrName, italianTitle)
        return !weakOnly
    }

    private data class TitleCue(
        val language: CardLanguage,
        val pattern: Regex,
        val weight: Int,
        val label: String,
        /** Diacritics are stripped by [fold], so those cues read the original title. */
        val folded: Boolean = true,
    )

    private val titleCues = listOf(
        TitleCue(CardLanguage.ITALIAN, Regex("""\b(?:della|dello|delle|degli|dei|di)\b"""), 5, "di"),
        TitleCue(CardLanguage.ITALIAN, Regex("""\b(?:del|dell)\b"""), 3, "del"),
        TitleCue(CardLanguage.ITALIAN, Regex("""\p{L}zz\p{L}"""), 3, "zz"),
        TitleCue(CardLanguage.GERMAN, Regex("""\b(?:der|die|das|von|und|aus|dem|den|des)\b"""), 5, "der"),
        TitleCue(CardLanguage.GERMAN, Regex("""[äöüß]"""), 4, "umlaut", folded = false),
        TitleCue(CardLanguage.FRENCH, Regex("""\b(?:du|des|au|aux|une)\b"""), 5, "du"),
        TitleCue(CardLanguage.FRENCH, Regex("""[éèêëç]"""), 4, "accent", folded = false),
        TitleCue(CardLanguage.SPANISH, Regex("""ñ"""), 5, "ñ", folded = false),
        TitleCue(CardLanguage.SPANISH, Regex("""\b(?:los|las)\b"""), 5, "los"),
        TitleCue(CardLanguage.PORTUGUESE, Regex("""[ãõ]"""), 5, "ã", folded = false),
        TitleCue(CardLanguage.PORTUGUESE, Regex("""\b(?:não|dos|das)\b"""), 5, "não", folded = false),
    )

    private fun score(ocr: String, title: String): Float {
        val other = normalize(title) ?: return 0f
        if (ocr == other) return 1f
        val ocrTokens = tokens(ocr)
        val titleTokens = tokens(other)
        if (ocrTokens.isEmpty() || titleTokens.isEmpty()) return 0f
        val hits = ocrTokens.count { token -> titleTokens.any { it == token } }
        val union = (ocrTokens + titleTokens).distinct().size
        if (union == 0) return 0f
        return hits.toFloat() / union
    }

    private fun tokens(text: String): List<String> =
        text.split(' ').filter { it.length >= 3 }

    private data class Signal(
        val language: CardLanguage,
        val pattern: Regex,
        val weight: Int,
        val label: String,
    )

    private val pokemonSignals: List<Signal> = listOf(
        Signal(CardLanguage.ITALIAN, Regex("""\bsi\s*evol\w{0,8}\s+da\b"""), 6, "si evolve da"),
        Signal(CardLanguage.ITALIAN, Regex("""\bsi\s*eval\w{0,8}\s+da\b"""), 6, "si evolve da"),
        Signal(CardLanguage.ITALIAN, Regex("""\bsievo\w{0,8}da\b"""), 6, "si evolve da"),
        Signal(CardLanguage.ITALIAN, Regex("""\bdebole\w{0,3}za\b"""), 4, "debolezza"),
        Signal(CardLanguage.ITALIAN, Regex("""\bresistenza\b"""), 3, "resistenza"),
        Signal(CardLanguage.ITALIAN, Regex("""\bcosto\s+di\s+ritirata\b"""), 6, "costo di ritirata"),
        Signal(CardLanguage.ITALIAN, Regex("""\britirata\b"""), 2, "ritirata"),
        Signal(CardLanguage.ITALIAN, Regex("""\blunghezza\b"""), 4, "lunghezza"),
        Signal(CardLanguage.ITALIAN, Regex("""\bpeso\b"""), 2, "peso"),
        Signal(CardLanguage.ITALIAN, Regex("""\bpokemon\s+base\b"""), 4, "pokemon base"),
        Signal(CardLanguage.ITALIAN, Regex("""\b\d{1,3}\s*pv\b"""), 3, "pv"),
        Signal(CardLanguage.ITALIAN, Regex("""\bfase\w{0,2}\b"""), 2, "fase"),
        Signal(CardLanguage.ITALIAN, Regex("""\baiuto\b"""), 4, "aiuto"),
        Signal(CardLanguage.ITALIAN, Regex("""\bstrumento\b"""), 4, "strumento"),
        Signal(CardLanguage.ITALIAN, Regex("""\bstadio\b"""), 3, "stadio"),
        Signal(CardLanguage.ENGLISH, Regex("""\bsupporter\b"""), 4, "supporter"),
        Signal(CardLanguage.ENGLISH, Regex("""\bstadium\b"""), 4, "stadium"),
        Signal(CardLanguage.GERMAN, Regex("""\bunterstutzer\b"""), 4, "unterstutzer"),
        Signal(CardLanguage.SPANISH, Regex("""\bpartidario\b"""), 4, "partidario"),
        Signal(CardLanguage.PORTUGUESE, Regex("""\bapoiador\b"""), 4, "apoiador"),
        Signal(CardLanguage.ENGLISH, Regex("""\bevolves?\s+from\b"""), 6, "evolves from"),
        Signal(CardLanguage.ENGLISH, Regex("""\bweakness\b"""), 4, "weakness"),
        Signal(CardLanguage.ENGLISH, Regex("""\bresistance\b"""), 3, "resistance"),
        Signal(CardLanguage.ENGLISH, Regex("""\bretreat\b"""), 4, "retreat"),
        Signal(CardLanguage.ENGLISH, Regex("""\b\d{1,3}\s*hp\b"""), 3, "hp"),
        Signal(CardLanguage.FRENCH, Regex("""\bevolue\s+de\b"""), 6, "evolue de"),
        Signal(CardLanguage.FRENCH, Regex("""\bniveau\b"""), 3, "niveau"),
        Signal(CardLanguage.FRENCH, Regex("""\bfaiblesse\b"""), 4, "faiblesse"),
        Signal(CardLanguage.FRENCH, Regex("""\bresistance\b"""), 3, "resistance"),
        Signal(CardLanguage.FRENCH, Regex("""\bretraite\b"""), 4, "retraite"),
        Signal(CardLanguage.FRENCH, Regex("""\b\d{1,3}\s*pv\b"""), 3, "pv"),
        Signal(CardLanguage.GERMAN, Regex("""\bentwickelt\s+sich\s+aus\b"""), 6, "entwickelt sich aus"),
        Signal(CardLanguage.GERMAN, Regex("""\bschwache\b"""), 4, "schwache"),
        Signal(CardLanguage.GERMAN, Regex("""\bresistenz\b"""), 4, "resistenz"),
        Signal(CardLanguage.GERMAN, Regex("""\bruckzug\b"""), 4, "ruckzug"),
        Signal(CardLanguage.SPANISH, Regex("""\bevoluciona\s+de\b"""), 6, "evoluciona de"),
        Signal(CardLanguage.SPANISH, Regex("""\bdebilidad\b"""), 4, "debilidad"),
        Signal(CardLanguage.SPANISH, Regex("""\bresistencia\b"""), 3, "resistencia"),
        Signal(CardLanguage.SPANISH, Regex("""\bretirada\b"""), 3, "retirada"),
        Signal(CardLanguage.PORTUGUESE, Regex("""\bevolui\s+de\b"""), 6, "evolui de"),
        Signal(CardLanguage.PORTUGUESE, Regex("""\bfraqueza\b"""), 4, "fraqueza"),
        Signal(CardLanguage.PORTUGUESE, Regex("""\bresistencia\b"""), 3, "resistencia"),
        Signal(CardLanguage.PORTUGUESE, Regex("""\brecuo\b"""), 4, "recuo"),
        Signal(CardLanguage.JAPANESE, Regex("""にげる"""), 6, "にげる"),
        Signal(CardLanguage.JAPANESE, Regex("""ワザ"""), 3, "ワザ"),
        Signal(CardLanguage.KOREAN, Regex("""약점"""), 6, "약점"),
        Signal(CardLanguage.KOREAN, Regex("""후퇴"""), 4, "후퇴"),
        Signal(CardLanguage.CHINESE_T, Regex("""弱點"""), 6, "弱點"),
        Signal(CardLanguage.CHINESE_S, Regex("""弱点"""), 6, "弱点"),
    )

    private data class TextScore(
        val language: CardLanguage?,
        val labels: List<String>,
        val every: List<String>,
    )

    private fun scoreCardText(raw: String?): TextScore? {
        val original = raw?.trim().orEmpty()
        if (original.length < 3) return null
        val folded = fold(original)
        val scores = mutableMapOf<CardLanguage, Int>()
        val labels = mutableMapOf<CardLanguage, MutableList<String>>()
        for (signal in pokemonSignals) {
            val haystack = if (signal.pattern.pattern.all { it.code < 0x250 || it == '\\' || it in "b+*?.()|" }) {
                folded
            } else {
                original
            }
            if (signal.pattern.containsMatchIn(haystack)) {
                scores[signal.language] = (scores[signal.language] ?: 0) + signal.weight
                val found = labels.getOrPut(signal.language) { mutableListOf() }
                if (signal.label !in found) found += signal.label
            }
        }
        scriptScore(original)?.let { (language, weight) ->
            scores[language] = (scores[language] ?: 0) + weight
            val found = labels.getOrPut(language) { mutableListOf() }
            if ("script" !in found) found += "script"
        }
        val every = labels.values.flatten().distinct()
        val best = scores.maxByOrNull { it.value } ?: return TextScore(null, emptyList(), every)
        val runner = scores.filterKeys { it != best.key }.maxOfOrNull { it.value } ?: 0
        if (best.value < 3 || (runner > 0 && best.value - runner < 2)) {
            return TextScore(null, emptyList(), every)
        }
        return TextScore(best.key, labels[best.key].orEmpty(), every)
    }

    private fun scriptScore(text: String): Pair<CardLanguage, Int>? {
        val hangul = text.count { it in '\uAC00'..'\uD7A3' || it in '\u1100'..'\u11FF' }
        val kana = text.count { it in '\u3040'..'\u30FF' || it == 'ー' }
        val han = text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        val latin = text.count { it.isLetter() && it.code < 0x250 }
        if (hangul >= 2 && hangul >= kana && hangul >= latin) return CardLanguage.KOREAN to 8
        if (kana >= 2 && kana >= latin) return CardLanguage.JAPANESE to 8
        if (han >= 2 && kana == 0 && hangul == 0 && han >= latin) {
            val traditional = text.count { it in TRADITIONAL_HINTS }
            val simplified = text.count { it in SIMPLIFIED_HINTS }
            return when {
                traditional > simplified && traditional > 0 -> CardLanguage.CHINESE_T to 8
                simplified > traditional && simplified > 0 -> CardLanguage.CHINESE_S to 8
                else -> null
            }
        }
        return null
    }

    /**
     * A language the scan can hand to confirm. A title match, a trusted collector
     * token, or a rules-title inference counts. An empty or kept result does not.
     */
    fun handedLanguage(detection: Detection?): CardLanguage? {
        val language = detection?.language ?: return null
        val evidence = detection.evidence
        if (evidence.isBlank() || evidence == "none" || evidence == "kept") return null
        val confident = evidence.contains("name=") ||
            evidence.contains("collector=") ||
            evidence.contains("title=")
        return language.takeIf { confident }
    }

    /** Confident scan result, otherwise the language already on the session. */
    fun confirmLanguage(
        detection: Detection?,
        session: CardLanguage?,
        fallback: CardLanguage,
    ): CardLanguage = handedLanguage(detection) ?: session ?: fallback

    private val ITALIAN_RULES = Regex(
        """(?i)\b(?:creatura|istantaneo|stregoneria|incantesimo|artefatto|terra|""" +
            """volare|travolgere|cautela|quando|ogniqualvolta|controlli)\b|""" +
            """tocco letale|fino alla fine del turno""",
    )
    private val SPANISH_RULES = Regex(
        """(?i)\b(?:criatura|instant[aá]neo|conjuro|encantamiento|artefacto|tierra|""" +
            """volar|arrollar|prisa|vigilancia|cuando|controlas)\b|""" +
            """toque mortal|hasta el final del turno|siempre que""",
    )
    private val ENGLISH_RULES = Regex(
        """(?i)\b(?:creature|instant|sorcery|enchantment|artifact|land|""" +
            """flying|trample|haste|vigilance|deathtouch|lifelink|whenever|when|controls?)\b|""" +
            """until end of turn|you control""",
    )

    private fun fold(raw: String): String {
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
        return decomposed.lowercase(Locale.ROOT)
    }

    private const val TRADITIONAL_HINTS = "點國龍電擊對這個屬傷無與為開關戰鬥寶夢術強網線體"
    private const val SIMPLIFIED_HINTS = "点国龙电击对这个属伤无与为开关战斗宝梦术强网线体"

    private const val MIN_SCORE = 0.75f
    private const val MIN_GAP = 0.15f
}

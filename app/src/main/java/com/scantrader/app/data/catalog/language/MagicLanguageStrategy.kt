package com.scantrader.app.data.catalog.language

import com.scantrader.app.data.catalog.CardLanguageDetector.Detection
import com.scantrader.app.data.model.CardLanguage

/**
 * Magic print language, strongest signal first:
 *
 * 1. The code next to the collector number (`0309 R / LCC • IT`). Modern frames only.
 *    IT and ES are soft: OCR mixes those two up, so a clear Italian or Spanish text
 *    corrects the other one. Any other clean code decides on its own.
 * 2. Printed words: the type line and rules text ([MagicLexicon]), or the script
 *    for Japanese and Chinese. This is the only signal on old frames.
 * 3. The name, only to break a tie the text left open. Never on its own: many
 *    localized prints keep the English name, and many names are the same everywhere.
 *
 * A code that disagrees with strong text, or no signal at all, is not a language:
 * the caller keeps the previous card's language and does not auto-save.
 */
object MagicLanguageStrategy : LanguageStrategy {
    override val usesPrintedNameLookup: Boolean = false

    /** Text decides alone at this score, with [MIN_GAP] over the next language. */
    const val MIN_TEXT_SCORE = 6
    const val MIN_GAP = 4

    /** Text this strong overrides a soft code and turns a hard code into a conflict. */
    const val STRONG_TEXT_SCORE = 8

    data class Token(val language: CardLanguage, val code: String, val soft: Boolean)

    /** Printed collector code (`it`, `es`, `sp`, `jp`, `cs`…) → language. */
    fun collectorToken(raw: String?): Token? {
        val code = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val language = when (code) {
            "it", "1t", "lt", "|t" -> CardLanguage.ITALIAN
            "es", "sp" -> CardLanguage.SPANISH
            "en" -> CardLanguage.ENGLISH
            "ja", "jp" -> CardLanguage.JAPANESE
            "zhs", "cs" -> CardLanguage.CHINESE_S
            "zht", "ct" -> CardLanguage.CHINESE_T
            "ko", "kr" -> CardLanguage.KOREAN
            "fr" -> CardLanguage.FRENCH
            "de" -> CardLanguage.GERMAN
            "pt" -> CardLanguage.PORTUGUESE
            else -> null
        } ?: return null
        val soft = language == CardLanguage.ITALIAN || language == CardLanguage.SPANISH
        return Token(language, code, soft)
    }

    private data class TextVerdict(
        val language: CardLanguage?,
        val score: Int,
        val label: String,
        val tied: List<CardLanguage> = emptyList(),
        val chineseUnsure: Boolean = false,
    ) {
        val strong: Boolean get() = language != null && score >= STRONG_TEXT_SCORE
    }

    override fun explain(input: LanguageInput): Detection {
        val body = bodyText(input.ocrName, input.ocrText)
        val text = readText(body)
        val token = collectorToken(input.collectorLang)
        if (token == null) {
            if (text.language != null) return Detection(text.language, text.label)
            nameTiebreak(input, text)?.let { return it }
            val why = when {
                text.chineseUnsure -> "uncertain script=zh"
                text.label.isNotBlank() -> "uncertain ${text.label}"
                else -> "none"
            }
            return Detection(null, why)
        }
        val collector = "collector=${token.language.scryfallLang()}"
        val textLang = text.language
        if (textLang == null || textLang == token.language) {
            val evidence = listOfNotNull(collector, text.label.takeIf { textLang != null }).joinToString(",")
            return Detection(token.language, evidence)
        }
        // IT and ES read for each other: the printed words settle it.
        val itEs = setOf(CardLanguage.ITALIAN, CardLanguage.SPANISH)
        if (token.soft && textLang in itEs && token.language in itEs) {
            return Detection(textLang, "${text.label},collector-misread=${token.code}")
        }
        // Simplified and traditional share most forms: the printed code knows better.
        val chinese = setOf(CardLanguage.CHINESE_S, CardLanguage.CHINESE_T)
        if (token.language in chinese && textLang in chinese) {
            return Detection(token.language, "$collector,${text.label}")
        }
        if (text.strong) {
            return Detection(null, "conflict code=${token.code} ${text.label}")
        }
        return Detection(token.language, "$collector,weak-${text.label}")
    }

    /**
     * True when the Latin read found no printed words, so a Japanese or Chinese
     * card may be behind it. The caller may then run the CJK recognizers once,
     * off the scan path.
     */
    fun needsScriptPass(ocrName: String?, ocrText: String?, collectorLang: String?): Boolean {
        if (collectorToken(collectorLang) != null) return false
        val body = bodyText(ocrName, ocrText)
        if (scriptLanguage(body) != null) return false
        val best = MagicLexicon.score(body).best?.second ?: 0
        return best < MIN_TEXT_SCORE
    }

    private fun readText(body: String): TextVerdict {
        val script = scriptLanguage(body)
        if (script != null) {
            val language = script.language
                ?: return TextVerdict(null, 0, "script=zh", chineseUnsure = true)
            return TextVerdict(language, STRONG_TEXT_SCORE, "script=${language.scryfallLang()}")
        }
        val score = MagicLexicon.score(body)
        val best = score.best ?: return TextVerdict(null, 0, "")
        val words = score.hits[best.first].orEmpty().take(4).joinToString("+")
        val label = "text=${best.first.scryfallLang()}($words)"
        if (best.second >= MIN_TEXT_SCORE && best.second - score.runnerUp >= MIN_GAP) {
            return TextVerdict(best.first, best.second, label)
        }
        val tied = score.ranked
            .filter { it.second >= MIN_TEXT_SCORE && best.second - it.second < MIN_GAP }
            .map { it.first }
        return TextVerdict(null, best.second, label, tied = tied)
    }

    /** The name only picks between languages the text already left level. */
    private fun nameTiebreak(input: LanguageInput, text: TextVerdict): Detection? {
        if (text.tied.size < 2) return null
        val ocr = MagicLexicon.fold(input.ocrName.orEmpty()).takeIf { it.length >= 3 } ?: return null
        val titles = buildMap {
            input.englishName?.let { put(CardLanguage.ENGLISH, MagicLexicon.fold(it)) }
            input.printed.forEach { (language, title) -> put(language, MagicLexicon.fold(title)) }
        }.filterValues { it.isNotBlank() }
        val matching = text.tied.filter { titles[it] == ocr }
        if (matching.size != 1) return null
        val winner = matching.first()
        if (titles.any { (language, title) -> language != winner && title == ocr }) return null
        val others = text.tied.joinToString("+") { it.scryfallLang() }
        return Detection(winner, "text-tie=$others,name-tiebreak=${winner.scryfallLang()}")
    }

    /**
     * Card text without the title line, the bottom block, and the copyright line
     * (`Wizards of the Coast` is English on every print).
     */
    fun bodyText(ocrName: String?, ocrText: String?): String {
        val raw = ocrText.orEmpty().substringBefore(BOTTOM_MARKER)
        val name = MagicLexicon.fold(ocrName.orEmpty())
        return raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { COPYRIGHT.containsMatchIn(it) }
            .filterNot { line -> name.length >= 3 && isTitleLine(MagicLexicon.fold(line), name) }
            .joinToString("\n")
    }

    private fun isTitleLine(line: String, name: String): Boolean {
        if (line == name) return true
        // `Lightning Bolt R` (mana symbol read as a letter) is still the title.
        return line.startsWith(name) && line.length - name.length <= 4
    }

    private const val BOTTOM_MARKER = "--- bottom ---"
    private val COPYRIGHT = Regex("""(?i)wizards|©|™|\billus\b|\(c\)\s*\d{4}""")

    /** Script read off the card. [chineseUnsure]: Han text with no simplified or traditional form. */
    data class ScriptRead(val language: CardLanguage?, val chineseUnsure: Boolean = false)

    /** Kana is Japanese. Han without kana is Chinese, simplified or traditional by its forms. */
    fun scriptLanguage(text: String): ScriptRead? {
        val kana = text.count { it in '\u3040'..'\u30FF' || it == 'ー' }
        val hangul = text.count { it in '\uAC00'..'\uD7A3' }
        val han = text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        if (kana >= 3) return ScriptRead(CardLanguage.JAPANESE)
        if (hangul >= 3) return ScriptRead(CardLanguage.KOREAN)
        if (han >= 4) {
            val traditional = text.count { it in TRADITIONAL }
            val simplified = text.count { it in SIMPLIFIED }
            return when {
                traditional > simplified -> ScriptRead(CardLanguage.CHINESE_T)
                simplified > traditional -> ScriptRead(CardLanguage.CHINESE_S)
                else -> ScriptRead(null, chineseUnsure = true)
            }
        }
        return null
    }

    private const val SIMPLIFIED = "这个对为无与点战张场时获进让结从将发来动伤选组横"
    private const val TRADITIONAL = "這個對為無與點戰張場時獲進讓結從將發來動傷選組橫"
}

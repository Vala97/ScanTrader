package com.scantrader.app.data.catalog

import com.scantrader.app.scan.OcrParser

/**
 * Printed codes on international cards (DRI, OBF, …) → TCGdex set ids (`sv10`, `sv03`).
 * Japanese and Chinese cards print their own set id (SV2a, CSV3C); those are not remapped.
 */
object PokemonSets {
    private val internationalPrinted = setOf(
        "svi", "pal", "obf", "mew", "par", "paf", "tef", "twm", "sfa", "scr", "ssp", "pre",
        "jtg", "dri", "wht", "blk", "meg", "pfl", "asc", "por", "cri", "pbl", "30c",
        // Sword & Shield, Sun & Moon, XY. Japanese ids (s1a, SV2a) are not in this set.
        "ssh", "rcl", "daa", "cpa", "viv", "shf", "bst", "cre", "evs", "fst", "brs", "asr",
        "pgo", "lor", "sit", "crz",
        "sum", "gri", "bus", "slg", "cin", "upr", "fli", "ces", "drm", "lot", "teu", "unb",
        "unm", "hif", "cec", "det",
        "xy", "flf", "ffi", "phf", "prc", "dcr", "ros", "aor", "bkt", "bkp", "gen", "fco",
        "sts", "evo",
    )

    private val toTcgdex = mapOf(
        "svi" to "sv01",
        "sv1" to "sv01",
        "sv01" to "sv01",
        "pal" to "sv02",
        "sv2" to "sv02",
        "sv02" to "sv02",
        "obf" to "sv03",
        "sv3" to "sv03",
        "sv03" to "sv03",
        "mew" to "sv03.5",
        "sv3pt5" to "sv03.5",
        "sv03.5" to "sv03.5",
        "par" to "sv04",
        "sv4" to "sv04",
        "sv04" to "sv04",
        "paf" to "sv04.5",
        "sv4pt5" to "sv04.5",
        "sv04.5" to "sv04.5",
        "tef" to "sv05",
        "sv5" to "sv05",
        "sv05" to "sv05",
        "twm" to "sv06",
        "sv6" to "sv06",
        "sv06" to "sv06",
        "sfa" to "sv06.5",
        "sv6pt5" to "sv06.5",
        "sv06.5" to "sv06.5",
        "scr" to "sv07",
        "sv7" to "sv07",
        "sv07" to "sv07",
        "ssp" to "sv08",
        "sv8" to "sv08",
        "sv08" to "sv08",
        "pre" to "sv08.5",
        "sv8pt5" to "sv08.5",
        "sv08.5" to "sv08.5",
        "jtg" to "sv09",
        "sv9" to "sv09",
        "sv09" to "sv09",
        "dri" to "sv10",
        "sv9pt5" to "sv10",
        "sv10" to "sv10",
        "wht" to "sv10.5w",
        "blk" to "sv10.5b",
        "meg" to "me01",
        "me1" to "me01",
        "me01" to "me01",
        "pfl" to "me02",
        "me2" to "me02",
        "me02" to "me02",
        "asc" to "me02.5",
        "me2pt5" to "me02.5",
        "me02.5" to "me02.5",
        "por" to "me03",
        "me3" to "me03",
        "me03" to "me03",
        "cri" to "me04",
        "me4" to "me04",
        "me04" to "me04",
        "pbl" to "me05",
        "me5" to "me05",
        "me05" to "me05",
        // 30th Celebration prints `30C` and `006/128`. TCGdex id is `30th`.
        "30c" to "30th",
        "30th" to "30th",
        "ssh" to "swsh1",
        "swsh1" to "swsh1",
        "rcl" to "swsh2",
        "swsh2" to "swsh2",
        "daa" to "swsh3",
        "swsh3" to "swsh3",
        "cpa" to "swsh3.5",
        "swsh3.5" to "swsh3.5",
        "viv" to "swsh4",
        "swsh4" to "swsh4",
        "shf" to "swsh4.5",
        "swsh4.5" to "swsh4.5",
        "bst" to "swsh5",
        "swsh5" to "swsh5",
        "cre" to "swsh6",
        "swsh6" to "swsh6",
        "evs" to "swsh7",
        "swsh7" to "swsh7",
        "fst" to "swsh8",
        "swsh8" to "swsh8",
        "brs" to "swsh9",
        "swsh9" to "swsh9",
        "asr" to "swsh10",
        "swsh10" to "swsh10",
        "pgo" to "swsh10.5",
        "swsh10.5" to "swsh10.5",
        "lor" to "swsh11",
        "swsh11" to "swsh11",
        "sit" to "swsh12",
        "swsh12" to "swsh12",
        "crz" to "swsh12.5",
        "swsh12.5" to "swsh12.5",
        "sum" to "sm1",
        "sm1" to "sm1",
        "gri" to "sm2",
        "sm2" to "sm2",
        "bus" to "sm3",
        "sm3" to "sm3",
        "slg" to "sm3.5",
        "sm3.5" to "sm3.5",
        "cin" to "sm4",
        "sm4" to "sm4",
        "upr" to "sm5",
        "sm5" to "sm5",
        "fli" to "sm6",
        "sm6" to "sm6",
        "ces" to "sm7",
        "sm7" to "sm7",
        "drm" to "sm7.5",
        "sm7.5" to "sm7.5",
        "lot" to "sm8",
        "sm8" to "sm8",
        "teu" to "sm9",
        "sm9" to "sm9",
        "unb" to "sm10",
        "sm10" to "sm10",
        "unm" to "sm11",
        "sm11" to "sm11",
        "hif" to "sm115",
        "sm115" to "sm115",
        "cec" to "sm12",
        "sm12" to "sm12",
        "det" to "det1",
        "det1" to "det1",
        "xy" to "xy1",
        "xy1" to "xy1",
        "flf" to "xy2",
        "xy2" to "xy2",
        "ffi" to "xy3",
        "xy3" to "xy3",
        "phf" to "xy4",
        "xy4" to "xy4",
        "prc" to "xy5",
        "xy5" to "xy5",
        "dcr" to "dc1",
        "dc1" to "dc1",
        "ros" to "xy6",
        "xy6" to "xy6",
        "aor" to "xy7",
        "xy7" to "xy7",
        "bkt" to "xy8",
        "xy8" to "xy8",
        "bkp" to "xy9",
        "xy9" to "xy9",
        "gen" to "g1",
        "g1" to "g1",
        "fco" to "xy10",
        "xy10" to "xy10",
        "sts" to "xy11",
        "xy11" to "xy11",
        "evo" to "xy12",
        "xy12" to "xy12",
    )

    fun isInternationalPrinted(code: String?): Boolean =
        code?.trim()?.lowercase() in internationalPrinted

    /** TCGdex id this catalog already searches (`base1`, `sv10`, `30th`). */
    fun isCatalogId(code: String?): Boolean {
        val key = normalize(code) ?: return false
        if (key in toTcgdex.values) return true
        return byOfficialCount.values.any { key in it }
    }

    /** TCGdex ids for a known printed code. Unknown codes are omitted. */
    fun recognizedIds(printedOrId: String?): List<String> {
        val key = normalize(printedOrId) ?: return emptyList()
        return listOfNotNull(toTcgdex[key])
    }

    fun idCandidates(printedOrId: String?): List<String> {
        val known = recognizedIds(printedOrId)
        if (known.isNotEmpty()) return known
        return listOfNotNull(normalize(printedOrId))
    }

    fun sameSet(a: String?, b: String?): Boolean {
        val right = idCandidates(b).toSet()
        if (right.isEmpty()) return false
        return idCandidates(a).any { it in right }
    }

    /**
     * Set ids to try on one TCGdex language.
     * English and Italian use the padded international id. Japanese and Chinese keep the printed id.
     */
    fun idsFor(printedOrId: String?, lang: String): List<String> {
        val raw = printedOrId?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val key = raw.lowercase()
        return when (lang) {
            "en", "it" -> listOfNotNull(toTcgdex[key] ?: key)
            else -> listOf(raw)
        }
    }

    /**
     * When OCR loses the set abbr but reads `072/217`, map official printed totals
     * to likely TCGdex international set ids.
     */
    /**
     * Official set sizes → likely TCGdex ids.
     * Vintage WOTC totals are distinctive (102 Base, 64 Jungle, 83 Rocket, …).
     * Modern SV totals disambiguate when the printed abbr is missing/misread.
     */
    private val byOfficialCount = mapOf(
        // —— WOTC / early (number usually bottom-right) ——
        102 to listOf("base1"), // Base Set
        64 to listOf("base2"), // Jungle
        62 to listOf("base3"), // Fossil
        82 to listOf("base5"), // Team Rocket (main list)
        83 to listOf("base5"), // Team Rocket (+ secret)
        110 to listOf("gym1"), // Gym Heroes
        132 to listOf("base4", "gym2"), // Base Set 2 or Gym Challenge — disambiguate by name
        75 to listOf("neo1"), // Neo Genesis
        111 to listOf("neo2"), // Neo Discovery
        66 to listOf("neo3"), // Neo Revelation
        105 to listOf("neo4"), // Neo Destiny
        // —— modern SV / Mega (number usually bottom-left) ——
        165 to listOf("sv03.5"), // 151 / MEW
        182 to listOf("sv10"), // Destined Rivals / DRI
        187 to listOf("sv04"), // Paradox Rift
        198 to listOf("sv01"), // SVI
        217 to listOf("me02.5"), // Ascended Heroes / ASC
        226 to listOf("sv09"), // Journey Together
        252 to listOf("sv08"), // Surging Sparks
        128 to listOf("30th"), // 30th Celebration / 30C (main set, not the secret total)
    )

    fun idsForCardCount(officialCount: Int?): List<String> {
        if (officialCount == null) return emptyList()
        return byOfficialCount[officialCount].orEmpty()
    }

    /**
     * TCGdex set ids to try from a printed code and/or the `NN/NNN` total.
     * Unknown OCR tokens (`wird`) are dropped. A known code (`dri`, `oasc`) and
     * the printed total (`102` → `base1`) are the direct id path.
     */
    fun directIds(printed: String?, count: Int?): List<String> {
        val tokens = OcrParser.setCodeCandidates(printed)
        val known = tokens.flatMap { recognizedIds(it) }
        val canon = tokens.mapNotNull { canonicalizePrinted(it) }.flatMap { recognizedIds(it) }
        // setCodeCandidates drops tokens longer than 5 (swsh12). A known id still counts.
        val raw = recognizedIds(printed)
        return (raw + known + canon + idsForCardCount(count)).distinct()
    }

    /**
     * Map an OCR set token onto a known printed code.
     * Trailing/leading junk (`asch`, `dasc`, `oasc`, `asca`) collapses to `asc`.
     * Already-known printed codes and TCGdex ids are returned unchanged.
     */
    fun canonicalizePrinted(raw: String?): String? {
        val trimmed = raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        if (trimmed in internationalPrinted || toTcgdex.containsKey(trimmed)) return trimmed
        val compact = trimmed.filter { it.isLetterOrDigit() }
        if (compact in internationalPrinted || toTcgdex.containsKey(compact)) return compact
        if (!compact.all { it.isLetter() } || compact.length !in 4..6) return null
        var best: String? = null
        var bestExtra = Int.MAX_VALUE
        for (code in internationalPrinted) {
            if (!compact.contains(code)) continue
            val extra = compact.length - code.length
            if (extra !in 1..2) continue
            if (extra < bestExtra || (extra == bestExtra && code.length > (best?.length ?: 0))) {
                best = code
                bestExtra = extra
            }
        }
        return best
    }

    private fun normalize(value: String?): String? =
        value?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
}

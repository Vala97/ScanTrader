package com.scantrader.app.scan

/**
 * The preview stays portrait. A flat phone makes CameraX report 0°/180° from the
 * accelerometer, the analysis bitmap stays landscape, and the title strip lands
 * on the collector line. These checks are the OCR fallback once that happens.
 */
object CardOrientation {
    private val rulesWords = setOf(
        "life", "lives", "target", "token", "nchar", "nchantment", "enchantment",
        "creature", "saga", "create", "discard", "army", "wizards", "opponent",
        "gain", "also", "choose", "reveal", "sacrifice", "instant", "sorcery",
        "artifact", "planeswalker", "battlefield", "destroy", "exile", "whenever",
        "land", "basic",
    )

    private val creditPatterns = listOf(
        // ™ / © / ® and the OCR spellings of `TM & ©` (`M&C`, `H&C`, `w&C`, `TM&C`).
        Regex("""[™©®]"""),
        Regex("""(?i)(?:^|\s)[a-z]{0,2}\s*&\s*[c©]\b"""),
        // `Wizards of the Coast`, also mangled (`Wnds ofth Co`, `Wids od the Coas`, `Wirards of the Coat`).
        Regex("""(?i)\bw[a-z]{1,6}\s+o[fd]\s*th?e?\s+c[o0]a?s?t?\b"""),
        Regex("""(?i)\bcoast\b"""),
        // Artist credit.
        Regex("""(?i)\billus\b"""),
        // A copyright year (not a collector fraction `2021/281`).
        Regex("""(?<![\d/])(?:19|20)\d{2}(?![\d/])"""),
        // Collector number with rarity or total (`214/281 U`, `214 U`).
        Regex("""\b\d{1,4}\s*/\s*\d{1,4}\b"""),
        Regex("""^\s*\d{1,4}\s*[CURMSLTP]?\s*$"""),
        // Set + language from the collector line (`AFR • EN`, `AFR EN`), upper case only.
        Regex("""\b[A-Z0-9]{3,5}(?:\s+|\s*[•·●*+.\-]\s*)(?:EN|IT|ES|SP|FR|DE|PT|JP|JA|CS|CT|ZHS|ZHT|KO|RU|PH)\b"""),
    )

    /**
     * Copyright, artist, year, collector or set+language line. These sit at
     * the bottom of a Magic card and are never its title (`M&C 2021 Wzards of the Coast`).
     */
    fun isCreditLine(line: String?): Boolean {
        val text = line?.trim()?.takeIf { it.isNotBlank() } ?: return false
        return creditPatterns.any { it.containsMatchIn(text) }
    }

    /** Short lowercase fragment (`nchar`), a rules word (`life`, `target`), or a credit line. */
    fun implausibleTitle(name: String?): Boolean {
        val cleaned = name?.trim()?.takeIf { it.isNotBlank() } ?: return true
        if (isCreditLine(cleaned)) return true
        val words = cleaned.lowercase().split(Regex("""[^a-z]+""")).filter { it.length >= 3 }
        if (words.isEmpty() || words.all { it in rulesWords }) return true
        val letters = cleaned.filter { it.isLetter() }
        if (letters.length < 4) return true
        if (words.size == 1 && letters.length <= 12 && letters.none { it.isUpperCase() }) return true
        // A title starts with a capital. A single word like `fEHR` is an OCR crumb.
        if (words.size == 1 && letters.first().isLowerCase()) return true
        return false
    }

    /** Number, set, or copyright text in the band that should be the title. */
    fun collectorInTitleBand(topText: String): Boolean {
        if (topText.isBlank()) return false
        val head = topText.lineSequence().take(8).joinToString("\n")
        if (Regex("""(?i)wizards|©|(?:^|[\s&])(?:19|20)\d{2}\b""").containsMatchIn(head)) return true
        if (Regex("""(?i)\bO\d{2,4}\b""").containsMatchIn(head)) return true
        if (Regex("""(?i)(?<![\d/+])0\d{2,3}\b""").containsMatchIn(head)) return true
        if (Regex("""(?i)\b[A-Z0-9]{1,5}\s*[+*•·●]\s*(?:EN|IT|DE|FR|ES)\b""").containsMatchIn(head)) {
            return true
        }
        if (Regex("""(?i)\b[A-Z]{3}(?:EN|IT|FR|DE|ES)\b""").containsMatchIn(head)) return true
        return false
    }

    fun rulesLeak(topText: String): Boolean =
        Regex(
            """(?i)\b(?:life|target|token|enchantment|nchantment|creature|saga)\b""",
        ).containsMatchIn(topText)

    /**
     * Title strip is the collector/rules end of an upright card: the crop
     * rotation put the bottom of the card at the top of the bitmap.
     */
    fun regionMappingOff(topText: String, parsedName: String?): Boolean {
        if (!collectorInTitleBand(topText)) return false
        if (implausibleTitle(parsedName)) return true
        val name = parsedName?.trim().orEmpty()
        val shortPartial = name.isNotEmpty() && !name.contains(' ') && name.length < 12
        return shortPartial && rulesLeak(topText)
    }

    /** Keep a 180° reread only when it produces a better title. */
    fun rotatedPassWins(firstName: String?, secondName: String?): Boolean {
        val secondOk = !secondName.isNullOrBlank() && !implausibleTitle(secondName)
        if (!secondOk) return false
        val firstOk = !firstName.isNullOrBlank() && !implausibleTitle(firstName)
        if (!firstOk) return true
        return secondName!!.length > firstName!!.length + 3
    }

    /**
     * Max side for the bitmap the title strip is cut from.
     * The 480px strip cap shrinks a long title ("Down, Down to Goblin-town")
     * until OCR keeps a fragment.
     */
    fun titleMaxSide(
        width: Int,
        height: Int,
        floor: Int,
        ceiling: Int,
        titleHeightFraction: Float,
        minTitlePx: Int,
    ): Int {
        val longest = maxOf(width, height)
        if (longest <= floor || height <= 0 || titleHeightFraction <= 0f) return minOf(longest, floor)
        val stripAtFloor = height * (floor.toFloat() / longest) * titleHeightFraction
        if (stripAtFloor >= minTitlePx) return floor
        val needed = (minTitlePx / titleHeightFraction * longest / height).toInt()
        return needed.coerceIn(floor, minOf(ceiling, longest))
    }
}

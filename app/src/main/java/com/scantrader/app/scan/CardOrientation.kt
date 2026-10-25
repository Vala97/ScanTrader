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

    /** Short lowercase fragment (`nchar`) or a rules word (`life`, `target`). */
    fun implausibleTitle(name: String?): Boolean {
        val cleaned = name?.trim()?.takeIf { it.isNotBlank() } ?: return true
        val words = cleaned.lowercase().split(Regex("""[^a-z]+""")).filter { it.length >= 3 }
        if (words.isEmpty() || words.all { it in rulesWords }) return true
        val letters = cleaned.filter { it.isLetter() }
        if (letters.length < 4) return true
        if (words.size == 1 && letters.length <= 12 && letters.none { it.isUpperCase() }) return true
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

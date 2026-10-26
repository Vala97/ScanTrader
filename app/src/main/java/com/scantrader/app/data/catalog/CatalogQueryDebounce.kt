package com.scantrader.app.data.catalog

import com.scantrader.app.scan.OcrParser

/**
 * Autoscan should not hit the network on every frame.
 *
 * A lookup runs when either:
 * - the same primary key has been seen [stableFramesNeeded] times
 *   (Pokémon: collector + printed total, or a multi-word trainer title when the
 *   collector is missing; MTG: collector + set), or
 * - the same card region has been analyzed for [requeryAfterMs]
 *   or [fallbackFrames] frames without a lookup.
 *
 * OCR name/set jitter must not reset that clock. The query uses the majority
 * name, collector, and set from the recent frames so one bad read cannot win.
 * The same key is not queried again until [requeryAfterMs] has passed.
 *
 * Consecutive reads are compared with [agreementKey], which treats OCR look-alikes
 * (I/l/1/|, O/0, apostrophe shapes) as the same. The name stored for the catalog
 * stays the text that was read.
 */
class CatalogQueryDebounce(
    private val stableFramesNeeded: Int = 2,
    private val requeryAfterMs: Long = 2_000L,
    private val fallbackFrames: Int = 3,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    data class Frame(
        val name: String? = null,
        val collectorNumber: String? = null,
        val setCode: String? = null,
        val setCardCount: Int? = null,
        val pokemon: Boolean = false,
    )

    private val recent = ArrayDeque<Frame>()
    private var lastFrame: Frame? = null
    private var regionStartedAtMs: Long = 0L
    private var regionFrames: Int = 0

    private var lastSeenRaw: String? = null
    private var seenCount: Int = 0
    private var lastQueriedKey: String? = null
    private var lastQueryAtMs: Long = 0L

    fun normalize(identity: String): String =
        identity.trim().lowercase().replace(Regex("""\s+"""), " ")

    /**
     * Stability id. Case and spacing match [normalize]. I/l/1/| (and a few
     * look-alike strokes), O/0, and apostrophe shapes match too.
     * Fields stay split on `|`, so the separators themselves are not folded.
     */
    fun agreementKey(identity: String): String {
        val normalized = normalize(identity)
        if (normalized.isEmpty()) return ""
        return normalized.split('|').joinToString("|") { foldOcrGlyphs(it) }
    }

    /**
     * True when the previous frames already settled on this exact id.
     * Does not count the current frame. Used to skip extra OCR passes.
     */
    fun seenThisBefore(identity: String): Boolean {
        val key = agreementKey(identity)
        return key.isNotBlank() && key == lastSeenRaw && seenCount >= 1
    }

    fun shouldQuery(identity: String): Boolean {
        val key = normalize(identity)
        val agreed = agreementKey(key)
        noteExact(agreed)
        val frame = parseIdentity(key)
        if (!hasSignal(frame)) {
            clearRegion()
            return false
        }
        return observe(frame, agreed)
    }

    fun shouldQuery(frame: Frame): Boolean {
        val agreed = agreementKey(exactKey(frame))
        noteExact(agreed)
        if (!hasSignal(frame)) {
            clearRegion()
            return false
        }
        return observe(frame, agreed)
    }

    /**
     * Lookup now, without waiting for [stableFramesNeeded].
     * Used when a gallery hit disagrees with the OCR title.
     * The same id still waits [requeryAfterMs].
     */
    fun forceQuery(identity: String): Boolean {
        val key = agreementKey(identity)
        if (key.isBlank()) return false
        val t = now()
        if (key == lastQueriedKey && t - lastQueryAtMs < requeryAfterMs) return false
        lastQueriedKey = key
        lastQueryAtMs = t
        return true
    }

    /** The last identity failed (429 / cooldown). The next stable frame may query again. */
    fun invalidateLastQuery() {
        lastQueriedKey = null
        lastQueryAtMs = 0L
    }

    /** Drop the current region. The next card starts the stability count over. */
    fun reset() {
        recent.clear()
        lastFrame = null
        regionStartedAtMs = 0L
        regionFrames = 0
        lastSeenRaw = null
        seenCount = 0
        lastQueriedKey = null
        lastQueryAtMs = 0L
    }

    /** Majority-vote read of the current card region, including the latest frame. */
    fun votedFrame(): Frame? {
        if (recent.isEmpty()) return null
        return majority(recent)
    }

    /**
     * Fill [current] with the majority name / collector / set / count.
     * A one-frame stage crumb ("FASE") loses to the name cluster seen most often.
     */
    fun voted(current: com.scantrader.app.data.model.ParsedCardText): com.scantrader.app.data.model.ParsedCardText {
        val vote = votedFrame() ?: return current
        val pokemon = recent.any { it.pokemon }
        val name = preferredName(current.nameGuess, vote.name)
        val setRaw = vote.setCode?.takeIf { it.isNotBlank() } ?: current.setCode
        val set = if (pokemon) {
            setRaw?.let { PokemonSets.canonicalizePrinted(it) ?: it.trim().lowercase() }
        } else {
            setRaw
        }
        return current.copy(
            nameGuess = name,
            collectorNumber = vote.collectorNumber?.takeIf { it.isNotBlank() } ?: current.collectorNumber,
            setCode = set,
            setCardCount = vote.setCardCount ?: current.setCardCount,
        )
    }

    private fun observe(frame: Frame, exactKey: String): Boolean {
        val t = now()
        val previous = lastFrame
        val same = previous != null && sameCardRegion(previous, frame)
        if (!same) {
            regionStartedAtMs = t
            regionFrames = 0
            recent.clear()
        }
        regionFrames++
        recent.addLast(frame)
        while (recent.size > RECENT_LIMIT) recent.removeFirst()
        lastFrame = frame

        val primary = primaryKey(frame)
        val primaryHits = if (primary == null) 0 else recent.count { primaryKey(it) == primary }
        val ready = seenCount >= stableFramesNeeded ||
            primaryHits >= stableFramesNeeded ||
            regionFrames >= fallbackFrames ||
            t - regionStartedAtMs >= requeryAfterMs
        if (!ready) return false
        val vote = majority(recent)
        val voteKey = primaryKey(vote) ?: exactKey
        if (voteKey == lastQueriedKey && t - lastQueryAtMs < requeryAfterMs) return false
        lastQueriedKey = voteKey
        lastQueryAtMs = t
        return true
    }

    private fun clearRegion() {
        lastFrame = null
        recent.clear()
        regionFrames = 0
    }

    private fun noteExact(key: String) {
        if (key == lastSeenRaw) {
            seenCount++
        } else {
            lastSeenRaw = key
            seenCount = 1
        }
    }

    private fun exactKey(frame: Frame): String =
        listOf(frame.name, frame.collectorNumber, frame.setCode)
            .joinToString("|") { it.orEmpty() }

    /**
     * Glyphs ML Kit swaps on a title. Applied per field so `|` stays the
     * separator. S/5, B/8, and Z/2 are left alone: those merge real numbers.
     */
    private fun foldOcrGlyphs(field: String): String = buildString(field.length) {
        for (ch in field) {
            append(
                when (ch) {
                    'i', 'l', '1', '|', '!',
                    '\u00A6', '\u01C0', '\u0131', '\u03B9', '\u2223', '\u2502',
                    -> 'i'
                    'o', '0', '\u03BF', '\u25CB', '\u3007' -> 'o'
                    '\'', '`',
                    '\u00B4', '\u2018', '\u2019', '\u201B', '\u2032', '\u02BC', '\u02BB', '\u02B9',
                    -> '\''
                    else -> ch
                },
            )
        }
    }

    private fun parseIdentity(key: String): Frame {
        val parts = key.split("|")
        val count = parts.getOrNull(3)?.toIntOrNull()
        return Frame(
            name = parts.getOrNull(0)?.takeIf { it.isNotBlank() },
            collectorNumber = parts.getOrNull(1)?.takeIf { it.isNotBlank() },
            setCode = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
            setCardCount = count,
            pokemon = count != null || parts.size >= 4,
        )
    }

    private fun hasSignal(frame: Frame): Boolean =
        !frame.name.isNullOrBlank() ||
            !frame.collectorNumber.isNullOrBlank() ||
            !frame.setCode.isNullOrBlank() ||
            frame.setCardCount != null

    private fun sameCardRegion(previous: Frame, current: Frame): Boolean {
        val cnPrev = CardIdentity.normCollector(previous.collectorNumber)
        val cnCur = CardIdentity.normCollector(current.collectorNumber)
        val countPrev = previous.setCardCount
        val countCur = current.setCardCount
        if (cnPrev != null && cnCur != null && cnPrev != cnCur) return false
        if (countPrev != null && countCur != null && countPrev != countCur) return false

        if (previous.pokemon || current.pokemon) {
            if (countPrev != null && countPrev == countCur) return true
            if (cnPrev != null && cnPrev == cnCur) return true
            return CardIdentity.namesSimilar(previous.name, current.name)
        }

        val setPrev = plainSet(previous.setCode)
        val setCur = plainSet(current.setCode)
        if (cnPrev != null && cnCur != null && setPrev != null && setCur != null) {
            return cnPrev == cnCur && setPrev == setCur
        }
        if (cnPrev != cnCur || setPrev != setCur) return false
        return CardIdentity.namesSimilar(previous.name, current.name) ||
            previous.name.isNullOrBlank() ||
            current.name.isNullOrBlank()
    }

    private fun primaryKey(frame: Frame): String? {
        val cn = CardIdentity.normCollector(frame.collectorNumber)
        if (frame.pokemon) {
            if (cn != null && frame.setCardCount != null) return "p:$cn/${frame.setCardCount}"
            val trainer = OcrParser.trainerStabilityName(frame.name) ?: return null
            return "t:$trainer"
        }
        if (cn == null) return null
        val set = plainSet(frame.setCode) ?: return null
        return "m:$cn/$set"
    }

    private fun plainSet(raw: String?): String? =
        raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

    private fun majority(frames: Iterable<Frame>): Frame {
        val list = frames.toList()
        val pokemon = list.any { it.pokemon }
        val cn = mode(list.mapNotNull { CardIdentity.normCollector(it.collectorNumber) })
        val count = mode(list.mapNotNull { it.setCardCount })
        val set = mode(list.mapNotNull { frameSet(it) })
        val name = voteName(list.mapNotNull { it.name?.trim()?.takeIf { n -> n.isNotBlank() } })
        return Frame(
            name = name,
            collectorNumber = cn,
            setCode = set,
            setCardCount = count,
            pokemon = pokemon,
        )
    }

    private fun frameSet(frame: Frame): String? {
        val raw = frame.setCode?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        if (!frame.pokemon) return raw
        return PokemonSets.canonicalizePrinted(raw) ?: raw
    }

    private fun voteName(names: List<String>): String? {
        if (names.isEmpty()) return null
        val usable = names.filterNot {
            CardIdentity.isStageNoise(it) || OcrParser.isTrainerLayoutWord(it)
        }.ifEmpty { names }
        val clusters = mutableListOf<MutableList<String>>()
        for (name in usable) {
            val cluster = clusters.firstOrNull { members ->
                members.any { CardIdentity.namesSimilar(it, name) }
            }
            if (cluster == null) clusters.add(mutableListOf(name)) else cluster.add(name)
        }
        val best = clusters.maxBy { it.size }
        val byNorm = best.groupBy { CardIdentity.normalizeName(it) }
        val topNorm = byNorm.maxWith(
            compareBy<Map.Entry<String, List<String>>>({ it.value.size }, { it.key.length }),
        ).key
        val frequent = byNorm.getValue(topNorm).maxWith(
            compareBy<String>({ ownerTitle(it) }, { it.length }),
        )
        val longerTitle = best.firstOrNull { candidate ->
            candidate.length > frequent.length &&
                ownerTitle(candidate) &&
                CardIdentity.namesSimilar(candidate, frequent)
        }
        return CardIdentity.repairOwnerName(longerTitle ?: frequent)
    }

    private fun preferredName(current: String?, voted: String?): String? {
        val raw = current?.trim()?.takeIf { it.isNotBlank() }
        val vote = voted?.trim()?.takeIf { it.isNotBlank() }
        if (raw == null) return vote
        if (vote == null) return raw
        if (CardIdentity.isStageNoise(raw) && !CardIdentity.isStageNoise(vote)) return vote
        if (OcrParser.isTrainerLayoutWord(raw) && !OcrParser.isTrainerLayoutWord(vote)) return vote
        if (!CardIdentity.namesSimilar(raw, vote)) return vote
        val voteOwner = ownerTitle(vote)
        val rawOwner = ownerTitle(raw)
        if (voteOwner && !rawOwner) return vote
        if (rawOwner && !voteOwner) return raw
        return if (vote.length >= raw.length) vote else raw
    }

    private fun ownerTitle(name: String): Boolean =
        Regex("""(?i)\b(?:di|del|della|dello|dei|delle)\b""").containsMatchIn(name)

    private fun <T> mode(values: List<T>): T? {
        if (values.isEmpty()) return null
        return values.groupingBy { it }.eachCount().maxBy { it.value }.key
    }

    private companion object {
        const val RECENT_LIMIT = 8
    }
}

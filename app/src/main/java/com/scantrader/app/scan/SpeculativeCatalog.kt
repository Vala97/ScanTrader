package com.scantrader.app.scan

import com.scantrader.app.data.catalog.CardIdentity
import com.scantrader.app.data.model.ParsedCardText

/**
 * Starts a catalog lookup on the first high-confidence frame and accepts it
 * when the next frame reads the same collector and printed total.
 * The lookup runs while that second frame is still in OCR.
 */
class SpeculativeCatalog {
    data class Step(
        val start: Boolean,
        val accept: Boolean,
        val key: String?,
        /** The in-flight lookup was for a different card and must be discarded. */
        val dropInFlight: Boolean = false,
    )

    private var pendingKey: String? = null
    private var pendingName: String? = null

    fun onOcr(parsed: ParsedCardText): Step {
        val key = confidentKey(parsed)
        if (key == null) {
            if (pendingKey != null && FrameNameGate.disagrees(parsed.nameGuess, pendingName)) {
                pendingKey = null
                pendingName = null
                return Step(start = false, accept = false, key = null, dropInFlight = true)
            }
            return Step(start = false, accept = false, key = null)
        }
        val sameLookup = pendingKey == key && !FrameNameGate.disagrees(parsed.nameGuess, pendingName)
        if (sameLookup) {
            pendingKey = null
            pendingName = null
            return Step(start = false, accept = true, key = key)
        }
        val drop = pendingKey != null
        pendingKey = key
        pendingName = parsed.nameGuess
        return Step(start = true, accept = false, key = key, dropInFlight = drop)
    }

    fun clear() {
        pendingKey = null
        pendingName = null
    }

    companion object {
        /**
         * Name plus collector, and either a set code or the printed total.
         * A stage crumb or the supporter label `Aiuto` is not confident.
         */
        fun confidentKey(parsed: ParsedCardText): String? {
            val name = parsed.nameGuess?.trim().orEmpty()
            val cn = CardIdentity.normCollector(parsed.collectorNumber) ?: return null
            if (name.length < 3) return null
            if (CardIdentity.isStageNoise(name) || OcrParser.isTrainerLayoutWord(name)) return null
            val count = parsed.setCardCount
            val set = parsed.setCode?.trim()?.lowercase()?.takeIf { code -> code.any { it.isLetter() } }
            if (count == null && set == null) return null
            return if (count != null) "p:$cn/$count" else "p:$cn/$set"
        }
    }
}

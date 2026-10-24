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
    )

    private var pendingKey: String? = null

    fun onOcr(parsed: ParsedCardText): Step {
        val key = confidentKey(parsed)
        if (key == null) return Step(start = false, accept = false, key = null)
        if (pendingKey == key) {
            pendingKey = null
            return Step(start = false, accept = true, key = key)
        }
        pendingKey = key
        return Step(start = true, accept = false, key = key)
    }

    fun clear() {
        pendingKey = null
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

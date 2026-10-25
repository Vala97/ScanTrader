package com.scantrader.app.scan

import com.scantrader.app.data.catalog.CardIdentity
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.vision.MatchFusion

/**
 * A set code read from the bottom line is kept only when it is one of the
 * printings of the OCR name. `pru` on Serpente di Bosco Dorato is not BNG.
 */
object SetPrintingGuard {
    fun discardUnknownSet(parsed: ParsedCardText, printings: List<IdentifiedCard>): ParsedCardText {
        if (unknownSet(parsed, printings) == null) return parsed
        return parsed.copy(setCode = null)
    }

    /** The parsed set, when no agreeing printing uses it. */
    fun unknownSet(parsed: ParsedCardText, printings: List<IdentifiedCard>): String? {
        val set = parsed.setCode?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val named = printings.filter { printingOfName(it, parsed) }
        if (named.isEmpty()) return null
        val known = named.map { it.setCode.trim().lowercase() }.filter { it.isNotBlank() }
        if (known.isEmpty()) return null
        val matches = known.any { code ->
            code == set || code.startsWith(set) || set.startsWith(code)
        }
        return if (matches) null else set
    }

    private fun printingOfName(card: IdentifiedCard, parsed: ParsedCardText): Boolean {
        if (MatchFusion.exactTitle(card, parsed.nameGuess)) return true
        return FrameNameGate.agrees(card, parsed)
    }
}

/**
 * Sets already rejected for the current OCR name. The next frame drops them
 * before they become the catalog id.
 */
class RejectedSetCodes {
    private var name: String? = null
    private val sets = mutableSetOf<String>()

    fun scrub(parsed: ParsedCardText): ParsedCardText {
        val current = parsed.nameGuess?.trim().orEmpty()
        if (name != null && !CardIdentity.namesSimilar(name, current)) {
            clear()
        }
        val set = parsed.setCode?.trim()?.lowercase() ?: return parsed
        if (set !in sets) return parsed
        return parsed.copy(setCode = null)
    }

    fun note(parsed: ParsedCardText, printings: List<IdentifiedCard>) {
        val rejected = SetPrintingGuard.unknownSet(parsed, printings) ?: return
        val current = parsed.nameGuess?.trim().orEmpty()
        if (name != null && !CardIdentity.namesSimilar(name, current)) {
            sets.clear()
        }
        name = current
        sets += rejected
    }

    fun clear() {
        name = null
        sets.clear()
    }
}

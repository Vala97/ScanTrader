package com.scantrader.app.scan

import com.scantrader.app.data.catalog.CardIdentity
import com.scantrader.app.data.model.ParsedCardText

/**
 * Once auto-scan accepts a card, later frames and a different collector must not
 * start a new lookup until the card has left the guide.
 */
class AcceptedCandidateLock(
    private val clearFramesNeeded: Int = 3,
) {
    data class Snapshot(
        val key: String,
        val collectorNumber: String?,
        val name: String?,
    )

    var snapshot: Snapshot? = null
        private set

    private var clearFrames: Int = 0

    val key: String? get() = snapshot?.key

    fun clear() {
        snapshot = null
        clearFrames = 0
    }

    fun lock(key: String, collectorNumber: String?, name: String?) {
        snapshot = Snapshot(key, collectorNumber, name)
        clearFrames = 0
    }

    /**
     * True when this frame must not query the catalog or replace the accepted card.
     * The same physical card (collector, or trainer title when the number is missing)
     * stays ignored. A different card is ignored until [clearFramesNeeded] frames,
     * then the lock opens.
     */
    fun shouldIgnore(parsed: ParsedCardText): Boolean {
        val locked = snapshot ?: return false
        if (sameCard(locked, parsed)) {
            clearFrames = 0
            return true
        }
        clearFrames++
        if (clearFrames < clearFramesNeeded) return true
        clear()
        return false
    }

    private fun sameCard(locked: Snapshot, parsed: ParsedCardText): Boolean {
        val cn = CardIdentity.normCollector(parsed.collectorNumber)
        val lockedCn = CardIdentity.normCollector(locked.collectorNumber)
        if (cn != null && lockedCn != null) return cn == lockedCn
        if (CardIdentity.namesSimilar(parsed.nameGuess, locked.name)) return true
        val trainer = OcrParser.trainerStabilityName(parsed.nameGuess)
        val lockedTrainer = OcrParser.trainerStabilityName(locked.name)
        return trainer != null && trainer == lockedTrainer
    }
}

package com.scantrader.app.scan

import com.scantrader.app.data.catalog.CardIdentity
import com.scantrader.app.data.model.ParsedCardText

/**
 * Holds the card shown on the confirm overlay.
 * The lock lasts only while that overlay is pending: [clear] on close.
 * A parsed name or collector that belongs to a different card releases it
 * immediately so the next card is not ignored and cannot reuse this identity.
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
     * The same physical card stays ignored while the overlay is pending.
     * A different name or collector releases the lock on this frame.
     * Frames with no usable identity still wait [clearFramesNeeded] reads.
     */
    fun shouldIgnore(parsed: ParsedCardText): Boolean {
        val locked = snapshot ?: return false
        if (sameCard(locked, parsed)) {
            clearFrames = 0
            return true
        }
        if (identityChanged(locked, parsed)) {
            clear()
            return false
        }
        clearFrames++
        if (clearFrames < clearFramesNeeded) return true
        clear()
        return false
    }

    private fun identityChanged(locked: Snapshot, parsed: ParsedCardText): Boolean {
        val cn = CardIdentity.normCollector(parsed.collectorNumber)
        val lockedCn = CardIdentity.normCollector(locked.collectorNumber)
        if (cn != null && lockedCn != null && cn != lockedCn) return true
        return FrameNameGate.disagrees(parsed.nameGuess, locked.name)
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

package com.scantrader.app.scan

import com.scantrader.app.data.catalog.CardIdentity
import com.scantrader.app.data.model.ParsedCardText

/**
 * The lookup uses the majority vote while the title still agrees.
 * A different title on the current frame replaces that vote, including a
 * collector that was read for the previous card.
 */
object FrameQuery {
    fun resolve(frame: ParsedCardText, voted: ParsedCardText): ParsedCardText {
        if (!FrameNameGate.disagrees(frame.nameGuess, voted.nameGuess)) return voted
        val frameCn = CardIdentity.normCollector(frame.collectorNumber)
        val votedCn = CardIdentity.normCollector(voted.collectorNumber)
        val collector = when {
            frameCn != null -> frame.collectorNumber
            votedCn != null -> null
            else -> frame.collectorNumber
        }
        return frame.copy(
            collectorNumber = collector,
            setCode = frame.setCode ?: voted.setCode,
            setCardCount = frame.setCardCount ?: voted.setCardCount,
        )
    }
}

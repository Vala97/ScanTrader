package com.scantrader.app.scan

import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.vision.MatchFusion

/**
 * Three frames in a row with the same printing, a matching name, and no
 * name conflict are enough to open confirm. Medium confidence is enough.
 * A name conflict (Elrond vs Ravening Warg) resets the run and never accepts.
 */
class StableTopAccept(
    private val framesNeeded: Int = 3,
    private val mediumConfidence: Float = 0.55f,
) {
    private var key: String? = null
    private var count: Int = 0

    fun ready(card: IdentifiedCard, decision: MatchFusion.AutoMatchDecision): Boolean {
        if (!decision.nameOk || decision.nameConflict) {
            reset()
            return false
        }
        val confidence = decision.confidence ?: 0f
        if (confidence < mediumConfidence) {
            reset()
            return false
        }
        val next = MatchFusion.printingKey(card)
        if (next == key) {
            count++
        } else {
            key = next
            count = 1
        }
        return count >= framesNeeded
    }

    fun reset() {
        key = null
        count = 0
    }
}

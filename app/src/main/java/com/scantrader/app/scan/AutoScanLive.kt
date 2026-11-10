package com.scantrader.app.scan

import com.scantrader.app.data.model.CardLanguage

/** Last card written into a collection during this scan visit. */
data class LastSavedCard(
    val name: String,
    val setCode: String,
    val collectorNumber: String,
    val languageLabel: String,
    val language: CardLanguage,
    val savedAtMs: Long,
    val identityKey: String,
    /** Room row of that save. Null when the insert did not return an id. */
    val entryId: Long? = null,
    val collectionId: Long? = null,
)

enum class FramedKind { SAVED, NEW, ANALYZING }

/** What the camera is holding, next to the last saved line. */
data class FramedCard(
    val kind: FramedKind,
    val name: String?,
    /**
     * True only when this frame's catalog identity is the last saved card.
     * A blank analysis, or a different card, is false.
     */
    val sameIdentity: Boolean = false,
)

object AutoScanLive {
    fun framed(
        sameAsSaved: Boolean,
        recognizedName: String?,
        sameIdentity: Boolean = sameAsSaved,
    ): FramedCard {
        val name = recognizedName?.trim()?.takeIf { it.isNotEmpty() }
        val kind = when {
            sameAsSaved -> FramedKind.SAVED
            name == null -> FramedKind.ANALYZING
            else -> FramedKind.NEW
        }
        return FramedCard(
            kind = kind,
            name = if (kind == FramedKind.ANALYZING) null else name,
            sameIdentity = sameIdentity && kind != FramedKind.NEW,
        )
    }

    /**
     * «Apri» is on the bar for the whole visit after a save, including while
     * another card is in the frame or the frame is still being read.
     */
    fun showApri(hasLastSaved: Boolean): Boolean = hasLastSaved

    /** Room row the button opens. Missing when the save did not return an id. */
    fun savedEntryId(entryId: Long?, collectionId: Long?): Long? =
        if (collectionId != null && entryId != null && entryId > 0L) entryId else null

    /**
     * The bar above the scan controls. The analysis sentence stays on the
     * centered overlay, so this bar only keeps the last saved card, whether
     * the frame is the same or a new card, and «Apri».
     */
    fun showLastCardBar(hasLastSaved: Boolean, framed: FramedCard): Boolean {
        if (hasLastSaved) return true
        return framed.kind != FramedKind.ANALYZING
    }

    /** Missing preference stays expanded. */
    fun lastCardBarCollapsed(stored: Boolean?): Boolean = stored == true

    fun toggleLastCardBar(collapsed: Boolean): Boolean = !collapsed

    fun showFrameStatus(kind: FramedKind): Boolean = kind != FramedKind.ANALYZING

    enum class FramePhase { SEARCHING, ANALYZING, RECOGNIZED }

    /** Corner color: white while searching, accent while analyzing, green once a card is known. */
    fun framePhase(processing: Boolean, autoStatus: String?, framed: FramedKind): FramePhase {
        if (processing || autoStatus == "Analisi in corso…" || autoStatus == "Scryfall occupato, riprovo…") {
            return FramePhase.ANALYZING
        }
        if (framed == FramedKind.SAVED || framed == FramedKind.NEW || autoStatus == "Carta trovata") {
            return FramePhase.RECOGNIZED
        }
        return FramePhase.SEARCHING
    }
}

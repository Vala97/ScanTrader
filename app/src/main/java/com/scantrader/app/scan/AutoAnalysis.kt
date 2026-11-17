package com.scantrader.app.scan

import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Auto-scan must not sit on a slow gallery hydrate, and a result for the
 * previous card must not be saved after the frame has changed.
 */
object AutoAnalysis {
    const val GALLERY_HYDRATE_TIMEOUT_MS = 2_500L

    /** Hung analysis stops blocking the next frame. A scene change releases sooner. */
    const val BUSY_CAP_MS = 8_000L

    fun strictNameSetCn(hits: List<IdentifiedCard>, parsed: ParsedCardText): Boolean {
        val top = hits.firstOrNull() ?: return false
        return strictNameSetCn(top, parsed)
    }

    /** Name, set, and collector already agree. Gallery art is not required. */
    fun strictNameSetCn(card: IdentifiedCard, parsed: ParsedCardText): Boolean {
        val name = FrameNameGate.confidentTitle(parsed.nameGuess) ?: return false
        val nameOk = FrameNameGate.agreesName(
            card.game,
            card.name,
            card.printedName,
            name,
            card.catalogName,
        ) || (card.game == Game.MTG && (
            FrameNameGate.mtgTitlesAgree(card.name, name, allowTruncatedTitle = true) ||
                FrameNameGate.mtgTitlesAgree(card.printedName, name, allowTruncatedTitle = true) ||
                FrameNameGate.mtgTitlesAgree(card.catalogName, name, allowTruncatedTitle = true)
            ))
        if (!nameOk) return false
        if (!collectorOk(card.collectorNumber, parsed.collectorNumber)) return false
        return setOk(card.setCode, parsed.setCode)
    }

    fun staleLog(generation: Int): String = "autoResult stale discarded gen=$generation"

    const val GALLERY_TIMEOUT_LOG = "gallery hydrate timeout"

    private fun collectorOk(cardNumber: String, parsedNumber: String?): Boolean {
        val numbers = com.scantrader.app.scan.OcrParser.collectorCandidates(parsedNumber)
        if (numbers.isEmpty()) return false
        val cn = cardNumber.trimStart('0').ifBlank { cardNumber }
        return numbers.any {
            cardNumber.equals(it, ignoreCase = true) ||
                cn.equals(it.trimStart('0').ifBlank { it }, ignoreCase = true)
        }
    }

    private fun setOk(cardSet: String, parsedSet: String?): Boolean {
        val sets = com.scantrader.app.scan.OcrParser.setCodeCandidates(parsedSet)
        if (sets.isEmpty()) return false
        val key = cardSet.lowercase()
        return sets.any { set -> key == set || key.startsWith(set) || set.startsWith(key) }
    }
}

/**
 * One generation per card in the frame. A later scene change or a busy-cap
 * handover invalidates the analysis that was already running.
 */
class AutoFrameCoordinator(
    private val busyCapMs: Long = AutoAnalysis.BUSY_CAP_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    var generation: Int = 0
        private set

    @get:Synchronized
    var busy: Boolean = false
        private set

    private var startedAt: Long = 0L

    @Synchronized
    fun isCurrent(started: Int): Boolean = started == generation

    @Synchronized
    fun canAccept(at: Long): Boolean {
        if (busy && at - startedAt >= busyCapMs) {
            generation++
            busy = false
        }
        return !busy
    }

    @Synchronized
    fun start(at: Long): Int {
        if (busy && at - startedAt >= busyCapMs) generation++
        busy = true
        startedAt = at
        return generation
    }

    /** @return true when the frame changed and the gate was released */
    @Synchronized
    fun noteSceneChanged(motion: Double?): Boolean {
        if (!busy || motion == null || motion <= FrameGate.MOTION_MAX) return false
        generation++
        busy = false
        return true
    }

    /** @return false when this analysis must not publish or autosave */
    @Synchronized
    fun finish(started: Int): Boolean {
        val fresh = started == generation
        if (fresh) busy = false
        if (!fresh) ScanDebug.d(AutoAnalysis.staleLog(started))
        return fresh
    }
}

object CatalogGalleryJoin {
    /**
     * Catalog is already in hand. A strict name+set+collector hit does not
     * wait for gallery hydration. Any other hydrate is capped, then cancelled.
     */
    suspend fun <T> afterCatalog(
        strict: Boolean,
        timeoutMs: Long = AutoAnalysis.GALLERY_HYDRATE_TIMEOUT_MS,
        gallery: suspend () -> List<T>,
        cancelGallery: () -> Unit,
    ): List<T> {
        if (strict) {
            cancelGallery()
            ScanDebug.d("gallery skipped (strict name+set+cn)")
            return emptyList()
        }
        val hydrated = try {
            withTimeoutOrNull(timeoutMs) { gallery() }
        } catch (cancel: CancellationException) {
            cancelGallery()
            throw cancel
        }
        if (hydrated == null) {
            cancelGallery()
            ScanDebug.d(AutoAnalysis.GALLERY_TIMEOUT_LOG)
            return emptyList()
        }
        return hydrated
    }
}

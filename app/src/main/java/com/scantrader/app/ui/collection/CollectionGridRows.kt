package com.scantrader.app.ui.collection

import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.model.Game
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One item of the collection grid, in drawing order. The grid and the fast-scroll
 * bubble both read this list, so the label always matches the item on screen.
 */
sealed interface CollectionGridRow {
    /** Same keys the grid used before: `game-…`, `section-…`, card id. */
    val key: Any

    /** Text of the fast-scroll bubble when this item is at the top. */
    val label: String

    data class GameHeader(val game: Game, val copies: Int, val open: Boolean, override val label: String) : CollectionGridRow {
        override val key: Any get() = "game-${game.name}"
    }

    data class SetHeader(val section: CollectionSection, val open: Boolean, override val label: String) : CollectionGridRow {
        override val key: Any get() = "section-${section.key}"
    }

    data class Card(val card: CardEntryEntity, override val label: String) : CollectionGridRow {
        override val key: Any get() = card.id
    }
}

/** Collapse key of a game header, kept in the screen's saved `collapsed` set. */
fun collectionGameCollapseKey(game: Game): String = "g:${game.name}"

/** Collapse key of an expansion header. */
fun collectionSectionCollapseKey(section: CollectionSection): String = "s:${section.key}"

/**
 * Grid items for [result]: when grouped, one header per game then its expansions,
 * skipping what is collapsed; otherwise the cards only.
 */
fun collectionGridRows(
    result: CollectionBrowseResult,
    sort: CollectionSort,
    collapsed: Set<String>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<CollectionGridRow> {
    val rows = ArrayList<CollectionGridRow>()
    val gameCopies = result.sections.groupBy { it.game }.mapValues { (_, list) -> list.sumOf { it.copies } }
    val dates = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ITALY).withZone(zone)
    var lastGame: Game? = null
    for (section in result.sections) {
        if (result.grouped) {
            val game = section.game
            if (game != null && game != lastGame) {
                lastGame = game
                val open = collectionGameCollapseKey(game) !in collapsed
                rows += CollectionGridRow.GameHeader(game, gameCopies[game] ?: 0, open, game.shareLabel)
            }
            if (game != null && collectionGameCollapseKey(game) in collapsed) continue
            val open = collectionSectionCollapseKey(section) !in collapsed
            rows += CollectionGridRow.SetHeader(section, open, section.title)
            if (!open) continue
        }
        for (card in section.cards) {
            rows += CollectionGridRow.Card(card, collectionScrollLabel(card, sort, section.title, dates))
        }
    }
    return rows
}

/**
 * Bubble text for a card by the current sort: the expansion, the first letter of
 * the name (`#` for digits and symbols), the price, or the day it was added.
 */
fun collectionScrollLabel(
    card: CardEntryEntity,
    sort: CollectionSort,
    sectionTitle: String,
    dates: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ITALY).withZone(ZoneId.systemDefault()),
): String = when (sort) {
    CollectionSort.COLLECTOR_NUMBER -> sectionTitle.ifBlank { card.setCode.uppercase() }
    CollectionSort.NAME -> collectionNameInitial(card.name)
    CollectionSort.PRICE, CollectionSort.PRICE_ASC ->
        collectionPrice(card)?.let(::formatCollectionTotal) ?: "Senza prezzo"
    CollectionSort.DATE_ADDED -> dates.format(Instant.ofEpochMilli(card.createdAt))
}

/** `Éowyn` → `E`, `1996 World Champion` → `#`. */
fun collectionNameInitial(name: String): String {
    val first = foldMatch(name.trim()).firstOrNull() ?: return "#"
    return if (first in 'a'..'z') first.uppercaseChar().toString() else "#"
}

/** Fast-scroll rules and the math between the thumb and the grid position. */
object CollectionFastScrollMath {
    /** Below this many cards the bar stays hidden. */
    const val MIN_CARDS = 60

    fun visible(cardCount: Int, canScroll: Boolean): Boolean = cardCount >= MIN_CARDS && canScroll

    /**
     * Position of the list in 0..1: first visible item plus how much of it has
     * scrolled away, over the items that can still go off the top.
     */
    fun fractionFor(firstIndex: Int, firstOffsetFraction: Float, visibleCount: Int, itemCount: Int): Float {
        val scrollable = itemCount - visibleCount
        if (itemCount <= 0 || scrollable <= 0) return 0f
        val position = firstIndex + firstOffsetFraction.coerceIn(0f, 1f)
        return (position / scrollable).coerceIn(0f, 1f)
    }

    /** First item to show for a thumb at [fraction]. The very end goes to the last item. */
    fun indexFor(fraction: Float, itemCount: Int, visibleCount: Int): Int {
        if (itemCount <= 0) return 0
        val f = fraction.coerceIn(0f, 1f)
        if (f >= 1f) return itemCount - 1
        val scrollable = (itemCount - visibleCount).coerceAtLeast(0)
        return (f * scrollable).roundToInt().coerceIn(0, itemCount - 1)
    }

    /** Thumb fraction for a touch at [y] on a track of [trackPx] with a thumb of [thumbPx]. */
    fun fractionForTouch(y: Float, trackPx: Float, thumbPx: Float): Float {
        val room = trackPx - thumbPx
        if (room <= 0f) return 0f
        return ((y - thumbPx / 2f) / room).coerceIn(0f, 1f)
    }

    /** Thumb top in pixels for [fraction]. */
    fun thumbTop(fraction: Float, trackPx: Float, thumbPx: Float): Float =
        fraction.coerceIn(0f, 1f) * (trackPx - thumbPx).coerceAtLeast(0f)
}

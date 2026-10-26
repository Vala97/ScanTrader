package com.scantrader.app.ui.collection

import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.db.CardPrice
import java.math.BigDecimal

enum class CollectionSort {
    COLLECTOR_NUMBER,
    PRICE,
}

data class CollectionSection(
    val key: String,
    val title: String,
    val copies: Int,
    val cards: List<CardEntryEntity>,
)

/**
 * Expansions ordered by set name. Cards inside a section follow [sort]:
 * collector number ascending (`62`, `105`, `105a`), or price descending
 * with a missing price last.
 */
fun collectionSections(
    cards: List<CardEntryEntity>,
    sort: CollectionSort,
): List<CollectionSection> {
    if (cards.isEmpty()) return emptyList()
    return cards
        .groupBy { sectionKey(it) }
        .map { (key, rows) ->
            val title = rows.firstNotNullOfOrNull { it.setName.trim().takeIf { name -> name.isNotEmpty() } }
                ?: rows.first().setCode.uppercase()
            CollectionSection(
                key = key,
                title = title,
                copies = rows.sumOf { it.quantity.coerceAtLeast(0) },
                cards = rows.sortedWith(cardOrder(sort)),
            )
        }
        .sortedWith(compareBy({ it.title.lowercase() }, { it.key }))
}

/** Euro amount used for price sorting. Same side as the price shown on the tile. */
fun collectionPrice(card: CardEntryEntity): BigDecimal? {
    val raw = CardPrice.effectiveRaw(card)
    if (raw.isNullOrBlank()) return null
    val parsed = raw.trim().replace(',', '.').toBigDecimalOrNull() ?: return null
    return parsed.takeIf { it > BigDecimal.ZERO }
}

private fun sectionKey(card: CardEntryEntity): String =
    "${card.game.name}:${card.setCode.trim().lowercase()}"

private fun cardOrder(sort: CollectionSort): Comparator<CardEntryEntity> =
    when (sort) {
        CollectionSort.COLLECTOR_NUMBER -> compareBy(::collectorRank).thenBy { it.id }
        CollectionSort.PRICE -> Comparator<CardEntryEntity> { left, right ->
            val leftPrice = collectionPrice(left)
            val rightPrice = collectionPrice(right)
            when {
                leftPrice == null && rightPrice == null -> collectorRank(left).compareTo(collectorRank(right))
                leftPrice == null -> 1
                rightPrice == null -> -1
                else -> rightPrice.compareTo(leftPrice)
            }
        }.thenBy { it.id }
    }

private data class CollectorRank(val number: Int, val suffix: String, val raw: String) : Comparable<CollectorRank> {
    override fun compareTo(other: CollectorRank): Int {
        val byNumber = number.compareTo(other.number)
        if (byNumber != 0) return byNumber
        val bySuffix = suffix.compareTo(other.suffix)
        if (bySuffix != 0) return bySuffix
        return raw.compareTo(other.raw)
    }
}

private fun collectorRank(card: CardEntryEntity): CollectorRank {
    val raw = card.collectorNumber.trim()
    val match = Regex("""^(\d+)(.*)$""").find(raw)
    if (match == null) return CollectorRank(Int.MAX_VALUE, "", raw.lowercase())
    val number = match.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE
    return CollectorRank(number, match.groupValues[2].lowercase(), raw.lowercase())
}

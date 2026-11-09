package com.scantrader.app.ui.collection

import com.scantrader.app.data.db.CardEntryEntity

data class SelectionCount(
    val rows: Int,
    val copies: Int,
    val hiddenRows: Int,
    val hiddenCopies: Int,
)

object CollectionSelection {
    fun toggle(selected: Set<Long>, id: Long): Set<Long> =
        if (id in selected) selected - id else selected + id

    fun selectVisible(selected: Set<Long>, visibleIds: Set<Long>): Set<Long> = selected + visibleIds

    fun deselectVisible(selected: Set<Long>, visibleIds: Set<Long>): Set<Long> = selected - visibleIds

    fun defaultQuantities(selected: Set<Long>, cards: List<CardEntryEntity>): Map<Long, Int> =
        cards.filter { it.id in selected && it.quantity > 0 }.associate { it.id to it.quantity }

    fun allVisibleSelected(selected: Set<Long>, visibleIds: Set<Long>): Boolean =
        visibleIds.isNotEmpty() && visibleIds.all { it in selected }

    /** Only rows that still exist count. A filter change does not drop ids. */
    fun count(selected: Set<Long>, all: List<CardEntryEntity>, visibleIds: Set<Long>): SelectionCount {
        val rows = all.filter { it.id in selected }
        val visible = rows.filter { it.id in visibleIds }
        val copies = rows.sumOf { it.quantity.coerceAtLeast(0) }
        val visibleCopies = visible.sumOf { it.quantity.coerceAtLeast(0) }
        return SelectionCount(
            rows = rows.size,
            copies = copies,
            hiddenRows = rows.size - visible.size,
            hiddenCopies = copies - visibleCopies,
        )
    }

    fun label(count: SelectionCount): String {
        val rows = if (count.rows == 1) "1 carta" else "${count.rows} carte"
        val copies = if (count.copies == 1) "1 copia" else "${count.copies} copie"
        val hidden = when (count.hiddenRows) {
            0 -> ""
            1 -> " · 1 non visibile"
            else -> " · ${count.hiddenRows} non visibili"
        }
        return "$rows · $copies$hidden"
    }
}

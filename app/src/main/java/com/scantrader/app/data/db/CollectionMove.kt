package com.scantrader.app.data.db

/**
 * Moves copies between two local collections.
 * Compatible rows merge with [StockMerge]. Language, condition, foil, and variant stay separate.
 * Unsent copies move first, so a later tracker send does not repeat copies already sent.
 */
object CollectionMove {
    data class Request(val sourceId: Long, val quantity: Int)

    data class SentMove(
        val sourceId: Long,
        val destinationId: Long,
        val movedCopies: Int,
        val sourceQuantityBefore: Int,
    )

    sealed class Op {
        data class Update(val row: CardEntryEntity) : Op()
        data class Insert(val row: CardEntryEntity, val sent: SentMove) : Op()
        data class Delete(val id: Long) : Op()
        data class Retarget(val sent: SentMove) : Op()
    }

    data class Plan(
        val source: List<CardEntryEntity>,
        val destination: List<CardEntryEntity>,
        val ops: List<Op>,
        val copiesMoved: Int,
    )

    /** Null when nothing can move. More than the row holds is cut down to the row. */
    fun clamp(requested: Int, available: Int): Int? {
        if (available <= 0 || requested <= 0) return null
        return requested.coerceAtMost(available)
    }

    /**
     * Copies already reported to the tracker that travel with this move.
     * Copies not yet sent travel first, so the destination does not look unsent
     * for a copy that was already queued.
     */
    fun sentMoved(sourceQuantity: Int, alreadySent: Int, moving: Int): Int {
        val quantity = sourceQuantity.coerceAtLeast(0)
        val movingCopies = moving.coerceIn(0, quantity)
        val sent = alreadySent.coerceIn(0, quantity)
        val unsent = quantity - sent
        return (movingCopies - unsent).coerceAtLeast(0)
    }

    fun plan(
        requests: List<Request>,
        sourceRows: List<CardEntryEntity>,
        destinationRows: List<CardEntryEntity>,
        destinationId: Long,
        sentFor: (Long) -> Int = { 0 },
    ): Plan {
        val source = sourceRows.associateBy { it.id }.toMutableMap()
        val destination = destinationRows.associateBy { it.id }.toMutableMap()
        val ops = mutableListOf<Op>()
        var copies = 0
        var tempId = -1L
        for (request in requests) {
            val row = source[request.sourceId] ?: continue
            val qty = clamp(request.quantity, row.quantity) ?: continue
            val moving = row.copy(
                id = 0L,
                collectionId = destinationId,
                quantity = qty,
            ).stamped()
            val match = destination.values.firstOrNull { StockKey.of(it) == StockKey.of(moving) }
            val before = row.quantity
            val sent = sentMoved(before, sentFor(row.id), qty)
            if (qty == before) source.remove(row.id) else source[row.id] = row.copy(quantity = before - qty)
            if (match == null && qty == before) {
                val relocated = moving.copy(id = row.id, createdAt = row.createdAt)
                destination[row.id] = relocated
                ops += Op.Update(relocated)
            } else if (match == null) {
                val created = moving.copy(id = tempId, createdAt = row.createdAt)
                tempId -= 1
                destination[created.id] = created
                ops += Op.Update(source.getValue(row.id))
                ops += Op.Insert(
                    created,
                    SentMove(row.id, destinationId = 0L, movedCopies = qty, sourceQuantityBefore = before),
                )
            } else {
                val merged = StockMerge.merge(
                    listOf(
                        match,
                        moving.copy(createdAt = maxOf(match.createdAt, row.createdAt) + 1L),
                    ),
                ).copy(id = match.id, collectionId = destinationId)
                destination[match.id] = merged
                ops += Op.Update(merged)
                if (qty == before) ops += Op.Delete(row.id) else ops += Op.Update(source.getValue(row.id))
                if (sent > 0) {
                    ops += Op.Retarget(
                        SentMove(row.id, match.id, movedCopies = qty, sourceQuantityBefore = before),
                    )
                }
            }
            copies += qty
        }
        return Plan(
            source = source.values.toList(),
            destination = destination.values.filter { it.collectionId == destinationId },
            ops = ops,
            copiesMoved = copies,
        )
    }
}

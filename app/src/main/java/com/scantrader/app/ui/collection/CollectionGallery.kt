package com.scantrader.app.ui.collection

import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.db.CardPrice
import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.text.NumberFormat
import java.util.Locale

enum class CollectionSort {
    /** Expansions grouped, collector number inside. The view when no other sort is chosen. */
    COLLECTOR_NUMBER,
    NAME,
    PRICE_ASC,
    /** Highest price first. Missing prices stay last. */
    PRICE,
    DATE_ADDED,
}

/** How long a non-empty search waits before the list updates. */
const val COLLECTION_SEARCH_DEBOUNCE_MS = 250L

fun collectionSearchDelay(query: String): Long =
    if (query.isBlank()) 0L else COLLECTION_SEARCH_DEBOUNCE_MS

data class CollectionChoice(val value: String, val label: String)

/**
 * Filters and sort for one collection. Empty sets mean "every value".
 * [foil] null means both foil and non-foil.
 */
data class CollectionBrowseState(
    val query: String = "",
    val sets: Set<String> = emptySet(),
    val languages: Set<String> = emptySet(),
    val conditions: Set<String> = emptySet(),
    val foil: Boolean? = null,
    val sort: CollectionSort = CollectionSort.COLLECTOR_NUMBER,
) {
    val active: Boolean
        get() = query.isNotBlank() ||
            sets.isNotEmpty() ||
            languages.isNotEmpty() ||
            conditions.isNotEmpty() ||
            foil != null ||
            sort != CollectionSort.COLLECTOR_NUMBER
}

data class CollectionBrowseResult(
    val sections: List<CollectionSection> = emptyList(),
    val grouped: Boolean = true,
    val copies: Int = 0,
    val total: BigDecimal = BigDecimal.ZERO,
)

data class CollectionBrowser(
    val controls: CollectionBrowseState = CollectionBrowseState(),
    val result: CollectionBrowseResult = CollectionBrowseResult(),
    val sets: List<CollectionChoice> = emptyList(),
    val languages: List<CollectionChoice> = emptyList(),
    val conditions: List<CollectionChoice> = emptyList(),
)

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
        .groupBy { collectionSetKey(it) }
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

fun collectionSetKey(card: CardEntryEntity): String =
    "${card.game.name}:${card.setCode.trim().lowercase()}"

fun collectionSetChoices(cards: List<CardEntryEntity>): List<CollectionChoice> =
    cards.groupBy { collectionSetKey(it) }
        .map { (key, rows) -> CollectionChoice(key, sectionTitle(rows)) }
        .sortedBy { it.label.lowercase(Locale.ITALY) }

fun collectionLanguageChoices(cards: List<CardEntryEntity>): List<CollectionChoice> =
    cards.map { it.language.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .map { value ->
            val label = CardLanguage.entries.firstOrNull { it.csvValue == value }?.label ?: value
            CollectionChoice(value, label)
        }
        .sortedBy { it.label.lowercase(Locale.ITALY) }

fun collectionConditionChoices(cards: List<CardEntryEntity>): List<CollectionChoice> =
    cards.map { it.condition.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .map { value ->
            val label = CardCondition.entries.firstOrNull { it.csvValue == value }?.label ?: value
            CollectionChoice(value, label)
        }
        .sortedBy { it.label.lowercase(Locale.ITALY) }

/**
 * Search, filters, then sort. [CollectionSort.COLLECTOR_NUMBER] keeps one section
 * per expansion. The other sorts are a single list.
 */
fun browseCollection(
    cards: List<CardEntryEntity>,
    state: CollectionBrowseState,
): CollectionBrowseResult {
    val filtered = cards.filter { cardMatches(it, state) }
    val copies = filtered.sumOf { it.quantity.coerceAtLeast(0) }
    val total = filtered.fold(BigDecimal.ZERO) { acc, card ->
        val price = collectionPrice(card) ?: return@fold acc
        acc + price.multiply(BigDecimal.valueOf(card.quantity.coerceAtLeast(0).toLong()))
    }
    val grouped = state.sort == CollectionSort.COLLECTOR_NUMBER
    val sections = when {
        filtered.isEmpty() -> emptyList()
        grouped -> collectionSections(filtered, CollectionSort.COLLECTOR_NUMBER)
        else -> listOf(
            CollectionSection(
                key = "all",
                title = "",
                copies = copies,
                cards = filtered.sortedWith(flatOrder(state.sort)),
            ),
        )
    }
    return CollectionBrowseResult(sections, grouped, copies, total)
}

fun collectionSummary(copies: Int, total: BigDecimal): String {
    val copiesLabel = if (copies == 1) "1 copia" else "$copies copie"
    return "$copiesLabel · ${formatCollectionTotal(total)}"
}

fun formatCollectionTotal(total: BigDecimal): String {
    val number = NumberFormat.getNumberInstance(Locale.ITALY).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
        roundingMode = RoundingMode.HALF_UP
    }
    return number.format(total) + " €"
}

fun encodeCollectionBrowse(state: CollectionBrowseState): String {
    fun join(values: Set<String>) = values.map { it.replace("\n", " ") }.sorted().joinToString("\u001f")
    val foil = when (state.foil) {
        null -> "any"
        true -> "foil"
        false -> "normal"
    }
    return listOf(
        "1",
        state.query.replace("\n", " "),
        join(state.sets),
        join(state.languages),
        join(state.conditions),
        foil,
        state.sort.name,
    ).joinToString("\n")
}

fun decodeCollectionBrowse(raw: String?): CollectionBrowseState {
    if (raw.isNullOrBlank()) return CollectionBrowseState()
    val lines = raw.split('\n', limit = 7)
    if (lines.firstOrNull() != "1" || lines.size < 7) return CollectionBrowseState()
    val sort = runCatching { CollectionSort.valueOf(lines[6]) }.getOrDefault(CollectionSort.COLLECTOR_NUMBER)
    return CollectionBrowseState(
        query = lines[1],
        sets = splitField(lines[2]),
        languages = splitField(lines[3]),
        conditions = splitField(lines[4]),
        foil = when (lines[5]) {
            "foil" -> true
            "normal" -> false
            else -> null
        },
        sort = sort,
    )
}

private fun splitField(raw: String): Set<String> =
    raw.split('\u001f').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

private fun cardMatches(card: CardEntryEntity, state: CollectionBrowseState): Boolean {
    if (!matchesCollectionQuery(card, state.query)) return false
    if (state.sets.isNotEmpty() && collectionSetKey(card) !in state.sets) return false
    if (state.languages.isNotEmpty() && card.language !in state.languages) return false
    if (state.conditions.isNotEmpty() && card.condition !in state.conditions) return false
    if (state.foil != null && card.foil != state.foil) return false
    return true
}

private fun matchesCollectionQuery(card: CardEntryEntity, query: String): Boolean {
    val needle = foldMatch(query.trim())
    if (needle.isEmpty()) return true
    if (foldMatch(card.name).contains(needle)) return true
    return foldMatch(card.catalogName.orEmpty()).contains(needle)
}

private fun foldMatch(value: String): String {
    val stripped = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
    return stripped.lowercase(Locale.ITALY)
}

private fun sectionTitle(rows: List<CardEntryEntity>): String =
    rows.firstNotNullOfOrNull { it.setName.trim().takeIf { name -> name.isNotEmpty() } }
        ?: rows.first().setCode.uppercase()

private fun flatOrder(sort: CollectionSort): Comparator<CardEntryEntity> =
    when (sort) {
        CollectionSort.NAME -> compareBy<CardEntryEntity> { foldMatch(it.name) }
            .thenBy { foldMatch(it.catalogName.orEmpty()) }
            .thenBy(::collectorRank)
            .thenBy { it.id }
        CollectionSort.PRICE_ASC -> priceOrder(ascending = true)
        CollectionSort.PRICE -> priceOrder(ascending = false)
        CollectionSort.DATE_ADDED ->
            compareByDescending<CardEntryEntity> { it.createdAt }.thenByDescending { it.id }
        CollectionSort.COLLECTOR_NUMBER -> compareBy(::collectorRank).thenBy { it.id }
    }

private fun priceOrder(ascending: Boolean): Comparator<CardEntryEntity> =
    Comparator<CardEntryEntity> { left, right ->
        val leftPrice = collectionPrice(left)
        val rightPrice = collectionPrice(right)
        when {
            leftPrice == null && rightPrice == null -> collectorRank(left).compareTo(collectorRank(right))
            leftPrice == null -> 1
            rightPrice == null -> -1
            ascending -> leftPrice.compareTo(rightPrice)
            else -> rightPrice.compareTo(leftPrice)
        }
    }.thenBy { it.id }

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
        else -> flatOrder(sort)
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

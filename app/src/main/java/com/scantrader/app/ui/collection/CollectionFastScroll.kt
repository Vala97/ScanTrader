package com.scantrader.app.ui.collection

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Width reserved on the right of the grid for the bar, so tiles stay outside its touch area. */
val CollectionFastScrollWidth = 24.dp

private val ThumbHeight = 48.dp
private const val IDLE_ALPHA = 0.4f
private const val FADE_DELAY_MS = 1_500L

/**
 * Fast-scroll bar on the right edge of the collection grid: drag the thumb or tap
 * the track to jump. While dragging, a bubble shows [rows]' label at that point.
 * Hidden with fewer than [CollectionFastScrollMath.MIN_CARDS] cards or when
 * everything fits on screen. Draw it over the grid, filling the same box.
 */
@Composable
fun CollectionFastScroll(
    state: LazyGridState,
    rows: List<CollectionGridRow>,
    cardCount: Int,
    modifier: Modifier = Modifier,
) {
    val canScroll by remember(state) { derivedStateOf { state.canScrollForward || state.canScrollBackward } }
    if (!CollectionFastScrollMath.visible(cardCount, canScroll)) return

    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var lastIndex by remember { mutableIntStateOf(-1) }
    val listFraction by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()
            val height = first?.size?.height ?: 0
            val offset = if (first == null || height <= 0) 0f else -first.offset.y.toFloat() / height
            CollectionFastScrollMath.fractionFor(
                firstIndex = state.firstVisibleItemIndex,
                firstOffsetFraction = offset,
                visibleCount = info.visibleItemsInfo.size,
                itemCount = info.totalItemsCount,
            )
        }
    }
    val fraction = if (dragging) dragFraction else listFraction

    // Full strength while moving, faint after a short pause.
    var active by remember { mutableStateOf(false) }
    val moving = dragging || state.isScrollInProgress
    LaunchedEffect(moving) {
        if (moving) active = true else {
            delay(FADE_DELAY_MS)
            active = false
        }
    }
    val alpha by animateFloatAsState(if (active) 1f else IDLE_ALPHA, label = "fastScrollAlpha")

    fun jumpTo(target: Float) {
        val info = state.layoutInfo
        val index = CollectionFastScrollMath.indexFor(target, info.totalItemsCount, info.visibleItemsInfo.size)
        if (index == lastIndex) return
        lastIndex = index
        // A newer scroll cancels the one still running, so fast drags do not queue up.
        scope.launch { state.scrollToItem(index) }
    }

    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val trackPx = constraints.maxHeight.toFloat()
        val thumbPx = with(density) { ThumbHeight.toPx() }
        val thumbTop = CollectionFastScrollMath.thumbTop(fraction, trackPx, thumbPx).roundToInt()
        val percent = (fraction * 100).roundToInt()

        if (dragging) {
            val label = rows.getOrNull(lastIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0)))?.label.orEmpty()
            if (label.isNotBlank()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(0, (thumbTop + thumbPx / 2f).roundToInt() - with(density) { 20.dp.roundToPx() }) }
                        .padding(end = CollectionFastScrollWidth + 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 4.dp,
                    shadowElevation = 4.dp,
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                    )
                }
            }
        }

        Box(
            Modifier
                .align(Alignment.TopEnd)
                .width(CollectionFastScrollWidth)
                .fillMaxHeight()
                .alpha(alpha)
                .semantics {
                    contentDescription = "Scorrimento rapido della collezione"
                    stateDescription = "$percent%"
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    setProgress { target ->
                        jumpTo(target)
                        true
                    }
                    customActions = listOf(
                        CustomAccessibilityAction("Vai all'inizio") {
                            lastIndex = 0
                            scope.launch { state.scrollToItem(0) }
                            true
                        },
                        CustomAccessibilityAction("Vai alla fine") {
                            val end = (state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                            lastIndex = end
                            scope.launch { state.scrollToItem(end) }
                            true
                        },
                    )
                }
                .pointerInput(state) {
                    detectTapGestures { offset ->
                        lastIndex = -1
                        jumpTo(CollectionFastScrollMath.fractionForTouch(offset.y, size.height.toFloat(), thumbPx))
                    }
                }
                .pointerInput(state) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            lastIndex = -1
                            dragFraction = CollectionFastScrollMath.fractionForTouch(offset.y, size.height.toFloat(), thumbPx)
                            jumpTo(dragFraction)
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        dragFraction = CollectionFastScrollMath.fractionForTouch(change.position.y, size.height.toFloat(), thumbPx)
                        jumpTo(dragFraction)
                    }
                },
        ) {
            // Thin track.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .width(2.dp)
                    .fillMaxHeight()
                    .padding(vertical = 4.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(1.dp)),
            )
            // Thumb. Only this area is taken from the system back gesture (Android allows 200 dp per edge).
            Box(
                Modifier
                    .offset { IntOffset(0, thumbTop) }
                    .width(CollectionFastScrollWidth)
                    .size(width = CollectionFastScrollWidth, height = ThumbHeight)
                    .systemGestureExclusion(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 6.dp, height = ThumbHeight)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
                )
            }
        }
    }
}

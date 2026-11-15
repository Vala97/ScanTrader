package com.scantrader.app.ui.scan

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scantrader.app.R
import com.scantrader.app.scan.AutoScanLive
import com.scantrader.app.scan.FramedCard
import com.scantrader.app.scan.FramedKind
import com.scantrader.app.scan.LastSavedCard
import com.scantrader.app.ui.theme.ScanColors
import java.util.Date

/**
 * Last saved card, same or new card in the frame, and «Apri».
 * The analysis sentence stays on the centered overlay, so this bar is absent
 * when it would only repeat that sentence.
 */
@Composable
fun AutoScanBanner(
    lastSaved: LastSavedCard?,
    framed: FramedCard,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    onOpenLastSaved: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val showApri = AutoScanLive.showApri(lastSaved != null)
    val showStatus = AutoScanLive.showFrameStatus(framed.kind)
    if (!AutoScanLive.showLastCardBar(lastSaved != null, framed)) return
    val framedLine = if (showStatus) {
        when (framed.kind) {
            FramedKind.SAVED -> stringResource(R.string.scan_framed_saved)
            FramedKind.NEW -> stringResource(R.string.scan_framed_new, framed.name.orEmpty())
            FramedKind.ANALYZING -> ""
        }
    } else {
        ""
    }
    val saved = lastSaved
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color(0xE6101014),
    ) {
        if (saved == null) {
            if (showStatus) {
                Text(
                    framedLine,
                    color = ScanColors.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        } else {
            val time = DateFormat.getTimeFormat(androidx.compose.ui.platform.LocalContext.current)
                .format(Date(saved.savedAtMs))
            val barCollapsed = collapsed
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
                IconButton(onClick = onToggleCollapsed) {
                    Icon(
                        imageVector = if (barCollapsed) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(
                            if (barCollapsed) R.string.scan_last_bar_expand else R.string.scan_last_bar_collapse,
                        ),
                        tint = ScanColors.text,
                    )
                }
                AnimatedContent(
                    targetState = barCollapsed,
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 12.dp, end = 8.dp),
                    transitionSpec = {
                        val enter = expandVertically(
                            animationSpec = tween(220),
                            expandFrom = Alignment.Bottom,
                        ) + fadeIn(tween(220))
                        val exit = shrinkVertically(
                            animationSpec = tween(220),
                            shrinkTowards = Alignment.Bottom,
                        ) + fadeOut(tween(160))
                        enter togetherWith exit
                    },
                    label = "lastCardBar",
                ) { isCollapsed ->
                    Column(Modifier.fillMaxWidth()) {
                        if (isCollapsed) {
                            Text(
                                stringResource(R.string.scan_last_saved_collapsed, time),
                                color = ScanColors.text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Text(
                                saved.name,
                                color = ScanColors.text,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                stringResource(
                                    R.string.scan_last_saved_meta,
                                    saved.setCode.uppercase(),
                                    saved.collectorNumber,
                                    saved.languageLabel,
                                    time,
                                ),
                                color = ScanColors.text,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (showStatus && framedLine.isNotEmpty()) {
                                Text(
                                    framedLine,
                                    color = ScanColors.accent,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                if (showApri) {
                    Box(
                        modifier = Modifier
                            .widthIn(min = 72.dp)
                            .padding(top = 2.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        OpenSavedButton(onOpenLastSaved)
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenSavedButton(onClick: () -> Unit) {
    val description = stringResource(R.string.scan_open_last_card)
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .widthIn(min = 72.dp)
            .heightIn(min = 48.dp)
            .semantics { contentDescription = description },
    ) {
        Text(
            stringResource(R.string.scan_open_saved),
            color = Color(0xFF64B5F6),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

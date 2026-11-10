package com.scantrader.app.ui.scan

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
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
 * Last saved card, same or new card in the frame, and the open action.
 * The analysis sentence stays on the centered overlay, so this bar is absent
 * when it would only repeat that sentence.
 */
@Composable
fun AutoScanBanner(
    lastSaved: LastSavedCard?,
    framed: FramedCard,
    onOpenLastSaved: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val openLast = AutoScanLive.showOpenLast(framed, lastSaved?.entryId)
    val showStatus = AutoScanLive.showFrameStatus(framed.kind)
    if (!AutoScanLive.showLastCardBar(lastSaved != null, framed, lastSaved?.entryId)) return
    val framedLine = if (showStatus) {
        when (framed.kind) {
            FramedKind.SAVED -> stringResource(R.string.scan_framed_saved)
            FramedKind.NEW -> stringResource(R.string.scan_framed_new, framed.name.orEmpty())
            FramedKind.ANALYZING -> ""
        }
    } else {
        ""
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color(0xE6101014),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            lastSaved?.let { saved ->
                val time = DateFormat.getTimeFormat(androidx.compose.ui.platform.LocalContext.current)
                    .format(Date(saved.savedAtMs))
                Text(
                    stringResource(
                        R.string.scan_last_saved,
                        saved.name,
                        saved.setCode.uppercase(),
                        saved.collectorNumber,
                        saved.languageLabel,
                        time,
                    ),
                    color = ScanColors.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showStatus) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        framedLine,
                        color = ScanColors.accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (openLast) {
                        OpenLastCardButton(onOpenLastSaved)
                    }
                }
            } else if (openLast) {
                OpenLastCardButton(onOpenLastSaved)
            }
        }
    }
}

@Composable
private fun OpenLastCardButton(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Text(
            stringResource(R.string.scan_open_last_card),
            color = Color(0xFF64B5F6),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

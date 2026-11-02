package com.scantrader.app.ui.scan

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scantrader.app.R
import com.scantrader.app.scan.FramedCard
import com.scantrader.app.scan.FramedKind
import com.scantrader.app.scan.LastSavedCard
import com.scantrader.app.ui.theme.ScanColors
import java.util.Date

/**
 * Always on during auto-scan, including while confirm is open.
 * The last saved line and the card in the guide right now.
 */
@Composable
fun AutoScanBanner(
    lastSaved: LastSavedCard?,
    framed: FramedCard,
    modifier: Modifier = Modifier,
) {
    val framedLine = when (framed.kind) {
        FramedKind.SAVED -> stringResource(R.string.scan_framed_saved)
        FramedKind.NEW -> stringResource(R.string.scan_framed_new, framed.name.orEmpty())
        FramedKind.ANALYZING -> stringResource(R.string.scan_framed_analyzing)
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
                )
            }
            Text(
                framedLine,
                color = ScanColors.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

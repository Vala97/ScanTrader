package com.scantrader.app.ui

import android.content.res.Resources
import com.scantrader.app.R
import com.scantrader.app.scan.LensOption
import com.scantrader.app.scan.LensRole
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

fun LensOption.displayLabel(resources: Resources): String {
    val role = roleLabel(resources)
    if (this.role == LensRole.AUTOMATIC) return role
    if (showZoom && zoomRatio != null) {
        return resources.getString(R.string.lens_with_zoom, role, formatZoom(zoomRatio))
    }
    val focal = focalLengthMm
    if (focal != null) {
        return resources.getString(R.string.lens_with_focal, role, focal)
    }
    return role
}

private fun LensOption.roleLabel(resources: Resources): String {
    if (role == LensRole.MAIN && roleOrdinal > 0) {
        return resources.getString(R.string.lens_main_numbered, roleOrdinal)
    }
    val name = when (role) {
        LensRole.AUTOMATIC -> resources.getString(R.string.lens_automatic)
        LensRole.ULTRAWIDE -> resources.getString(R.string.lens_ultrawide)
        LensRole.TELE -> resources.getString(R.string.lens_tele)
        LensRole.MAIN -> resources.getString(R.string.lens_main)
    }
    if (roleOrdinal > 0 && role != LensRole.AUTOMATIC) {
        return resources.getString(R.string.lens_role_numbered, name, roleOrdinal)
    }
    return name
}

private fun formatZoom(ratio: Float): String {
    val rounded = (ratio * 10f).roundToInt() / 10f
    return if (abs(rounded - rounded.roundToInt()) < 0.05f) {
        rounded.roundToInt().toString()
    } else {
        String.format(Locale.getDefault(), "%.1f", rounded)
    }
}

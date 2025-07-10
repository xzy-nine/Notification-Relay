// Copyright 2025, miuix-kotlin-multiplatform contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.utils

import android.annotation.SuppressLint
import android.os.Build
import android.view.RoundedCorner
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.WindowMetricsCalculator
import kotlin.math.max
import kotlin.math.min

@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
actual fun getWindowSize(): WindowSize {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val screenHeightDp = configuration.screenHeightDp
    val context = LocalContext.current
    val windowMetrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(context)
    val widthPx = windowMetrics.bounds.width()
    val heightPx = windowMetrics.bounds.height()
    return if (screenWidthDp > screenHeightDp)
        WindowSize(max(widthPx, heightPx), min(widthPx, heightPx))
    else
        WindowSize(min(widthPx, heightPx), max(widthPx, heightPx))
}

actual fun platform(): Platform = Platform.Android

@Composable
actual fun getRoundedCorner(): Dp = getSystemCornerRadius()

// NewApi. Represents a rounded corner of the display.
@Composable
private fun getSystemCornerRadius(): Dp {
    val insets = LocalView.current.rootWindowInsets
    val density = LocalDensity.current.density
    val roundedCornerRadius = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: getCornerRadiusBottom()
    } else {
        getCornerRadiusBottom()
    }
    val cornerDp = (roundedCornerRadius / density).dp
    if (cornerDp <= 32.dp) return 0.dp
    return cornerDp
}

// from https://dev.mi.com/distribute/doc/details?pId=1631
@Composable
@SuppressLint("DiscouragedApi")
fun getCornerRadiusBottom(): Int {
    val context = LocalContext.current
    val resourceId = context.resources.getIdentifier("rounded_corner_radius_bottom", "dimen", "android")
    return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
}

@Composable
actual fun BackHandler(
    enabled: Boolean,
    onBack: () -> Unit
) {
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
}
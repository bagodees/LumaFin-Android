package io.github.bagodees.lumafin.mobile.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Every screen picks its layout from the window's width, never from the device type: a folded
 * foldable is COMPACT like a phone, unfolded it's MEDIUM like a small tablet, and a large tablet is
 * EXPANDED. The breakpoints are Android's standard ones (600dp and 840dp).
 */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

fun widthClassFor(width: Dp): WidthClass = when {
	width >= 840.dp -> WidthClass.EXPANDED
	width >= 600.dp -> WidthClass.MEDIUM
	else -> WidthClass.COMPACT
}

/**
 * The app window's width. Read from the configuration, which changes on every rotate, fold and
 * window resize on all Android versions; the window-info size went stale after rotating on Android 9.
 */
@Composable
fun currentWidth(): Dp = LocalConfiguration.current.screenWidthDp.dp

@Composable
fun currentWidthClass(): WidthClass = widthClassFor(currentWidth())

package io.github.bagodees.lumafin.mobile.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
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

@Composable
fun currentWidthClass(): WidthClass {
	val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
	return widthClassFor(width)
}

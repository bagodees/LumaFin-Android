package io.github.bagodees.lumafin.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The same palette as the TV app, so both apps look like one brand. */
object LumaFinColors {
	val Background = Color(0xFF080D1C)
	val Surface = Color(0xFF101A2D)
	val Raised = Color(0xFF1B2A45)
	val Cyan = Color(0xFF39CEF3)
	val Blue = Color(0xFF679BFF)
	val Purple = Color(0xFFB9A4FF)
	val Selected = Color(0xFF302448)
	val Text = Color(0xFFF2F5FF)
	val SecondaryText = Color(0xFFB6C3DC)
	val Muted = Color(0xFF9BAFD0)
}

private val colors = darkColorScheme(
	primary = LumaFinColors.Cyan,
	onPrimary = LumaFinColors.Background,
	primaryContainer = LumaFinColors.Raised,
	onPrimaryContainer = LumaFinColors.Cyan,
	secondary = LumaFinColors.Purple,
	onSecondary = LumaFinColors.Background,
	secondaryContainer = LumaFinColors.Raised,
	onSecondaryContainer = LumaFinColors.Cyan,
	background = LumaFinColors.Background,
	onBackground = LumaFinColors.Text,
	surface = LumaFinColors.Background,
	onSurface = LumaFinColors.Text,
	surfaceVariant = LumaFinColors.Raised,
	onSurfaceVariant = LumaFinColors.SecondaryText,
	surfaceContainerLowest = LumaFinColors.Background,
	surfaceContainerLow = LumaFinColors.Surface,
	surfaceContainer = LumaFinColors.Surface,
	surfaceContainerHigh = LumaFinColors.Raised,
	surfaceContainerHighest = LumaFinColors.Raised,
	outline = LumaFinColors.Muted,
	outlineVariant = LumaFinColors.Raised,
)

@Composable
fun LumaFinTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, content = content)

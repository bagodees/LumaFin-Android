package io.github.bagodees.lumafin.mobile.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.bagodees.lumafin.mobile.ui.theme.LumaFinColors

/** Card and spacing sizes per screen width, so a folded phone, an unfolded one and a tablet each get sensible rows. */
data class Metrics(val gutter: Dp, val landscapeWidth: Dp, val posterWidth: Dp, val wide: Boolean)

fun metricsFor(width: WidthClass) = when (width) {
	WidthClass.COMPACT -> Metrics(gutter = 16.dp, landscapeWidth = 248.dp, posterWidth = 112.dp, wide = false)
	WidthClass.MEDIUM -> Metrics(gutter = 24.dp, landscapeWidth = 280.dp, posterWidth = 132.dp, wide = true)
	WidthClass.EXPANDED -> Metrics(gutter = 32.dp, landscapeWidth = 300.dp, posterWidth = 148.dp, wide = true)
}

@Composable
fun currentMetrics() = metricsFor(currentWidthClass())

/** One card: artwork with an optional progress bar, a title and a second line, the same shape on every screen. */
@Composable
fun MediaCard(
	image: String?,
	title: String,
	subtitle: String?,
	aspect: Float,
	modifier: Modifier = Modifier,
	progress: Float? = null,
	badge: String? = null,
	onClick: () -> Unit,
) {
	Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
		Box(Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(12.dp)).background(LumaFinColors.Raised)) {
			if (image == null) Text(
				title,
				style = MaterialTheme.typography.bodyMedium,
				color = LumaFinColors.SecondaryText,
				maxLines = 3,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.align(Alignment.Center).padding(12.dp),
			)
			AsyncImage(model = image, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
			if (progress != null && progress > 0f) {
				Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f))) {
					Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(LumaFinColors.Cyan))
				}
			}
			if (badge != null) Text(
				badge,
				style = MaterialTheme.typography.labelSmall,
				color = LumaFinColors.Background,
				modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(LumaFinColors.Cyan).padding(horizontal = 6.dp, vertical = 2.dp),
			)
		}
		Text(
			title,
			style = MaterialTheme.typography.bodyMedium,
			fontWeight = FontWeight.Medium,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(top = 6.dp),
		)
		if (subtitle != null) Text(
			subtitle,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

/** A titled, sideways-scrolling row of cards. */
@Composable
fun <T> CardRow(title: String, items: List<T>, metrics: Metrics, key: (T) -> Any, card: @Composable (T) -> Unit) {
	Column(Modifier.padding(top = 20.dp)) {
		Text(
			title,
			style = MaterialTheme.typography.titleMedium,
			fontWeight = FontWeight.SemiBold,
			modifier = Modifier.padding(horizontal = metrics.gutter, vertical = 8.dp),
		)
		LazyRow(contentPadding = PaddingValues(horizontal = metrics.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			items(items, key = key) { card(it) }
		}
	}
}

@Composable
fun CardWidth(width: Dp, content: @Composable () -> Unit) = Box(Modifier.width(width)) { content() }

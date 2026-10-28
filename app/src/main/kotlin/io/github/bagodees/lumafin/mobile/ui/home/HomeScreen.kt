package io.github.bagodees.lumafin.mobile.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.github.bagodees.lumafin.mobile.LumaFinApplication
import io.github.bagodees.lumafin.mobile.R
import io.github.bagodees.lumafin.mobile.data.backdropImage
import io.github.bagodees.lumafin.mobile.data.landscapeImage
import io.github.bagodees.lumafin.mobile.data.posterImage
import io.github.bagodees.lumafin.mobile.ui.common.WidthClass
import io.github.bagodees.lumafin.mobile.ui.common.currentWidthClass
import io.github.bagodees.lumafin.mobile.ui.theme.LumaFinColors
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

/** Card and spacing sizes per screen width, so a folded phone, an unfolded one and a tablet each get sensible rows. */
private data class HomeMetrics(val gutter: Dp, val landscapeWidth: Dp, val posterWidth: Dp, val showPreview: Boolean)

private fun metricsFor(width: WidthClass) = when (width) {
	WidthClass.COMPACT -> HomeMetrics(gutter = 16.dp, landscapeWidth = 248.dp, posterWidth = 112.dp, showPreview = false)
	WidthClass.MEDIUM -> HomeMetrics(gutter = 24.dp, landscapeWidth = 280.dp, posterWidth = 132.dp, showPreview = true)
	WidthClass.EXPANDED -> HomeMetrics(gutter = 32.dp, landscapeWidth = 300.dp, posterWidth = 148.dp, showPreview = true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
	application: LumaFinApplication,
	// One per signed-in user, so switching accounts never shows the last user's rows
	viewModel: HomeViewModel = viewModel(key = "home-${application.sessions.session.value?.userId}"),
) {
	val state by viewModel.state.collectAsState()
	val api = application.sessions.api ?: return
	val metrics = metricsFor(currentWidthClass())

	PullToRefreshBox(isRefreshing = state.loading && state.rows.isNotEmpty(), onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
		when {
			state.loading && state.rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
			state.rows.isEmpty() -> Message(stringResource(if (state.failed) R.string.home_failed else R.string.home_empty))
			else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
				val featured = state.rows.first().items.first()
				if (metrics.showPreview) item(key = "preview") { Preview(api, featured, metrics) }
				else item(key = "status") { Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars)) }
				state.rows.forEach { row ->
					item(key = row.title) { Row(api, row, metrics) }
				}
			}
		}
	}
}

@Composable
private fun Message(text: String) = Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
	Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The large-screen preview: the first title's backdrop with its name and overview, like the TV app's top area. */
@Composable
private fun Preview(api: ApiClient, item: BaseItemDto, metrics: HomeMetrics) {
	Box(Modifier.fillMaxWidth().height(340.dp)) {
		AsyncImage(
			model = api.backdropImage(item),
			contentDescription = null,
			contentScale = ContentScale.Crop,
			modifier = Modifier.fillMaxSize(),
		)
		// Fades the artwork into the page so the text and the first row stay readable
		Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 1f to LumaFinColors.Background)))
		Box(Modifier.fillMaxHeight().fillMaxWidth(0.6f).background(Brush.horizontalGradient(0f to LumaFinColors.Background.copy(alpha = 0.85f), 1f to Color.Transparent)))
		Column(Modifier.align(Alignment.BottomStart).padding(start = metrics.gutter, end = metrics.gutter, bottom = 20.dp).widthIn(max = 560.dp)) {
			Text(item.seriesName ?: item.name.orEmpty(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
			subtitle(item)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
			item.overview?.let {
				Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
			}
		}
	}
}

@Composable
private fun Row(api: ApiClient, row: HomeRow, metrics: HomeMetrics) {
	Column(Modifier.padding(top = 20.dp)) {
		Text(
			row.title,
			style = MaterialTheme.typography.titleMedium,
			fontWeight = FontWeight.SemiBold,
			modifier = Modifier.padding(horizontal = metrics.gutter, vertical = 8.dp),
		)
		LazyRow(contentPadding = PaddingValues(horizontal = metrics.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			items(row.items, key = { it.id }) { item ->
				when (row.style) {
					CardStyle.LANDSCAPE -> Card(api.landscapeImage(item), item, metrics.landscapeWidth, 16f / 9f)
					CardStyle.PORTRAIT -> Card(api.posterImage(item), item, metrics.posterWidth, 2f / 3f)
				}
			}
		}
	}
}

@Composable
private fun Card(image: String?, item: BaseItemDto, width: Dp, aspect: Float) {
	Column(Modifier.width(width)) {
		Box(Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(12.dp)).background(LumaFinColors.Raised)) {
			AsyncImage(model = image, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
			val progress = item.userData?.playedPercentage?.toFloat()?.div(100f)
			if (progress != null && progress > 0f) {
				Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f))) {
					Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(LumaFinColors.Cyan))
				}
			}
		}
		Text(
			item.seriesName ?: item.name.orEmpty(),
			style = MaterialTheme.typography.bodyMedium,
			fontWeight = FontWeight.Medium,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(top = 6.dp),
		)
		subtitle(item)?.let {
			Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
		}
	}
}

/** "S1 E3 · Name" for episodes, the year for everything else. */
private fun subtitle(item: BaseItemDto): String? = when (item.type) {
	BaseItemKind.EPISODE -> listOfNotNull(
		item.parentIndexNumber?.let { season -> item.indexNumber?.let { "S$season E$it" } },
		item.name,
	).joinToString(" · ").ifEmpty { null }
	else -> item.productionYear?.toString()
}

package io.github.bagodees.lumafin.mobile.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.github.bagodees.lumafin.mobile.LumaFinApplication
import io.github.bagodees.lumafin.mobile.R
import io.github.bagodees.lumafin.mobile.data.backdropImage
import io.github.bagodees.lumafin.mobile.ui.common.CardRow
import io.github.bagodees.lumafin.mobile.ui.common.ItemCard
import io.github.bagodees.lumafin.mobile.ui.common.LocalNavigator
import io.github.bagodees.lumafin.mobile.ui.common.Metrics
import io.github.bagodees.lumafin.mobile.ui.common.Route
import io.github.bagodees.lumafin.mobile.ui.common.SeerrCard
import io.github.bagodees.lumafin.mobile.ui.common.currentMetrics
import io.github.bagodees.lumafin.mobile.ui.common.subtitleFor
import io.github.bagodees.lumafin.mobile.ui.common.titleFor
import io.github.bagodees.lumafin.mobile.ui.theme.LumaFinColors
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
	application: LumaFinApplication,
	// One per signed-in user, so switching accounts never shows the last user's rows
	viewModel: HomeViewModel = viewModel(key = "home-${application.sessions.session.value?.userId}"),
) {
	val state by viewModel.state.collectAsState()
	val api = application.sessions.api ?: return
	val metrics = currentMetrics()

	PullToRefreshBox(isRefreshing = state.loading && state.rows.isNotEmpty(), onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
		when {
			state.loading && state.rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
			state.rows.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
				Text(stringResource(if (state.failed) R.string.home_failed else R.string.home_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
				val featured = state.rows.filterIsInstance<HomeRow.Items>().firstOrNull()?.items?.firstOrNull()
				// The preview needs width; on a phone the rows start right away
				if (metrics.wide && featured != null) item(key = "preview") { Preview(api, featured, metrics) }
				else item(key = "status") { Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars)) }
				state.rows.forEach { row ->
					item(key = row.title) {
						when (row) {
							is HomeRow.Items -> CardRow(row.title, row.items, metrics, key = { it.id }) {
								ItemCard(api, it, row.landscape, if (row.landscape) metrics.landscapeWidth else metrics.posterWidth)
							}
							is HomeRow.Seerr -> CardRow(row.title, row.items, metrics, key = { "${it.mediaType}/${it.mediaId}" }) {
								SeerrCard(it, landscape = false, width = metrics.posterWidth)
							}
						}
					}
				}
			}
		}
	}
}

/** The large-screen preview: the first title's backdrop with its name and overview, like the TV app's top area. */
@Composable
private fun Preview(api: ApiClient, item: BaseItemDto, metrics: Metrics) {
	val navigator = LocalNavigator.current
	Box(Modifier.fillMaxWidth().height(340.dp).clickable { navigator.open(Route.Item(item.id)) }) {
		AsyncImage(model = api.backdropImage(item), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
		// Fades the artwork into the page so the text and the first row stay readable
		Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 1f to LumaFinColors.Background)))
		Box(Modifier.fillMaxHeight().fillMaxWidth(0.6f).background(Brush.horizontalGradient(0f to LumaFinColors.Background.copy(alpha = 0.85f), 1f to Color.Transparent)))
		Column(Modifier.align(Alignment.BottomStart).padding(start = metrics.gutter, end = metrics.gutter, bottom = 20.dp).widthIn(max = 560.dp)) {
			Text(titleFor(item), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
			subtitleFor(item)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
			item.overview?.let {
				Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
			}
		}
	}
}

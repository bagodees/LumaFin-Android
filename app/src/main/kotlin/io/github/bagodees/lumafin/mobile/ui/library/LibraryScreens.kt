package io.github.bagodees.lumafin.mobile.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bagodees.lumafin.mobile.LumaFinApplication
import io.github.bagodees.lumafin.mobile.R
import io.github.bagodees.lumafin.mobile.data.primaryImage
import io.github.bagodees.lumafin.mobile.ui.common.ItemCard
import io.github.bagodees.lumafin.mobile.ui.common.LocalNavigator
import io.github.bagodees.lumafin.mobile.ui.common.MediaCard
import io.github.bagodees.lumafin.mobile.ui.common.Route
import io.github.bagodees.lumafin.mobile.ui.common.currentMetrics

/** The Libraries tab: every library the user can see, as wide tiles. */
@Composable
fun LibrariesScreen(application: LumaFinApplication, viewModel: LibrariesViewModel = viewModel()) {
	val libraries by viewModel.libraries.collectAsState()
	val api = application.sessions.api ?: return
	val metrics = currentMetrics()
	val navigator = LocalNavigator.current
	Column(Modifier.fillMaxSize().safeDrawingPadding()) {
		PageTitle(stringResource(R.string.nav_libraries), metrics.gutter)
		if (libraries == null) Loading()
		else LazyVerticalGrid(
			columns = GridCells.Adaptive(if (metrics.wide) 280.dp else 160.dp),
			contentPadding = PaddingValues(start = metrics.gutter, end = metrics.gutter, bottom = 24.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			verticalArrangement = Arrangement.spacedBy(16.dp),
		) {
			items(libraries.orEmpty(), key = { it.id }) { library ->
				MediaCard(
					image = api.primaryImage(library, maxWidth = 720),
					title = library.name.orEmpty(),
					subtitle = null,
					aspect = 16f / 9f,
					onClick = { navigator.open(Route.Library(library.id, library.name.orEmpty(), library.collectionType?.serialName)) },
				)
			}
		}
	}
}

/** One library as a grid that fills the width: more columns on an unfolded foldable or a tablet. */
@Composable
fun LibraryScreen(application: LumaFinApplication, route: Route.Library) {
	val viewModel: LibraryViewModel = viewModel(key = "library-${route.id}") { LibraryViewModel(application, route) }
	val state by viewModel.state.collectAsState()
	val api = application.sessions.api ?: return
	val metrics = currentMetrics()
	val gridState = rememberLazyGridState()
	// Loads the next page as the end comes into view
	val nearEnd by remember { derivedStateOf { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
	LaunchedEffect(nearEnd, state.items.size) { if (nearEnd >= state.items.size - 12) viewModel.loadMore() }

	Column(Modifier.fillMaxSize().safeDrawingPadding()) {
		PageTitle(route.name, metrics.gutter)
		Row(
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = metrics.gutter, vertical = 4.dp),
		) {
			LibrarySort.entries.forEach { sort ->
				FilterChip(selected = state.sort == sort, onClick = { viewModel.sortBy(sort) }, label = { Text(stringResource(sort.label)) })
			}
		}
		if (state.items.isEmpty() && state.loading) Loading()
		else LazyVerticalGrid(
			state = gridState,
			columns = GridCells.Adaptive(metrics.posterWidth),
			contentPadding = PaddingValues(start = metrics.gutter, end = metrics.gutter, top = 8.dp, bottom = 24.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			verticalArrangement = Arrangement.spacedBy(16.dp),
		) {
			items(state.items, key = { it.id }) { item -> ItemCard(api, item, landscape = false, width = metrics.posterWidth, modifier = Modifier.fillMaxWidth()) }
			if (state.loading) item(span = { GridItemSpan(maxLineSpan) }) { Loading(Modifier.padding(16.dp)) }
		}
	}
}

@Composable
fun PageTitle(text: String, gutter: androidx.compose.ui.unit.Dp) = Text(
	text,
	style = MaterialTheme.typography.headlineMedium,
	fontWeight = FontWeight.SemiBold,
	modifier = Modifier.padding(start = gutter, end = gutter, top = 16.dp, bottom = 12.dp),
)

@Composable
fun Loading(modifier: Modifier = Modifier.fillMaxSize()) = Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

package io.github.bagodees.lumafin.mobile.ui.search

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bagodees.lumafin.core.seerr.SeerrClient
import io.github.bagodees.lumafin.core.seerr.SeerrItem
import io.github.bagodees.lumafin.mobile.LumaFinApplication
import io.github.bagodees.lumafin.mobile.R
import io.github.bagodees.lumafin.mobile.ui.common.ItemCard
import io.github.bagodees.lumafin.mobile.ui.common.Route
import io.github.bagodees.lumafin.mobile.ui.common.SeerrCard
import io.github.bagodees.lumafin.mobile.ui.common.currentMetrics
import io.github.bagodees.lumafin.mobile.ui.library.Loading
import io.github.bagodees.lumafin.mobile.ui.library.PageTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

data class SearchState(val query: String = "", val loading: Boolean = false, val results: List<BaseItemDto> = emptyList(), val seerr: List<SeerrItem> = emptyList())

class SearchViewModel(application: Application) : AndroidViewModel(application) {
	private val app = application as LumaFinApplication
	private val _state = MutableStateFlow(SearchState())
	val state: StateFlow<SearchState> = _state.asStateFlow()
	private var job: Job? = null

	fun search(query: String) {
		_state.value = _state.value.copy(query = query)
		job?.cancel()
		val api = app.sessions.api ?: return
		if (query.trim().length < 2) {
			_state.value = SearchState(query)
			return
		}
		job = viewModelScope.launch {
			// Searches as you type, after a short pause
			delay(350.milliseconds)
			_state.value = _state.value.copy(loading = true)
			val results = withContext(Dispatchers.IO) {
				runCatching {
					api.itemsApi.getItems(
						GetItemsRequest(
							searchTerm = query.trim(),
							includeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE, BaseItemKind.BOX_SET),
							recursive = true,
							limit = 60,
							fields = setOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO),
							imageTypeLimit = 1,
						)
					).content.items
				}.getOrDefault(emptyList())
					// Shows and movies before their episodes
					.sortedBy { if (it.type == BaseItemKind.EPISODE) 1 else 0 }
			}
			val seerr = if (app.settings.seerrRows) withContext(Dispatchers.IO) { SeerrClient.search(api, query.trim()) } else emptyList()
			_state.value = SearchState(query, loading = false, results = results, seerr = seerr)
		}
	}
}

@Composable
fun SearchScreen(application: LumaFinApplication, viewModel: SearchViewModel = viewModel()) {
	val state by viewModel.state.collectAsState()
	val api = application.sessions.api ?: return
	val metrics = currentMetrics()
	Column(Modifier.fillMaxSize().safeDrawingPadding()) {
		PageTitle(stringResource(R.string.nav_search), metrics.gutter)
		OutlinedTextField(
			value = state.query,
			onValueChange = viewModel::search,
			leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
			placeholder = { Text(stringResource(R.string.search_hint)) },
			singleLine = true,
			keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
			modifier = Modifier.fillMaxWidth().padding(horizontal = metrics.gutter),
		)
		LazyVerticalGrid(
			columns = GridCells.Adaptive(metrics.posterWidth),
			contentPadding = PaddingValues(start = metrics.gutter, end = metrics.gutter, top = 16.dp, bottom = 24.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			verticalArrangement = Arrangement.spacedBy(16.dp),
		) {
			if (state.loading) item(span = { GridItemSpan(maxLineSpan) }) { Loading(Modifier.padding(24.dp)) }
			items(state.results, key = { it.id }) { ItemCard(api, it, landscape = false, width = metrics.posterWidth, modifier = Modifier.fillMaxWidth()) }
			// Titles that aren't here yet but can be requested
			val requestable = state.seerr.filter { seerr -> state.results.none { it.name.equals(seerr.name, ignoreCase = true) } }
			if (requestable.isNotEmpty()) {
				item(span = { GridItemSpan(maxLineSpan) }) {
					Text(stringResource(R.string.search_not_in_library), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
				}
				items(requestable, key = { "seerr-${it.mediaType}-${it.mediaId}" }) { SeerrCard(it, landscape = false, width = metrics.posterWidth, modifier = Modifier.fillMaxWidth()) }
			}
			if (!state.loading && state.query.trim().length >= 2 && state.results.isEmpty() && requestable.isEmpty()) {
				item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.search_nothing), color = MaterialTheme.colorScheme.onSurfaceVariant) }
			}
		}
	}
}

class PersonViewModel(application: LumaFinApplication, personId: UUID) : AndroidViewModel(application) {
	private val _items = MutableStateFlow<List<BaseItemDto>?>(null)
	val items: StateFlow<List<BaseItemDto>?> = _items.asStateFlow()

	init {
		viewModelScope.launch {
			val api = application.sessions.api ?: return@launch
			_items.value = withContext(Dispatchers.IO) {
				runCatching {
					api.itemsApi.getItems(
						GetItemsRequest(
							personIds = setOf(personId),
							includeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
							recursive = true,
							sortBy = setOf(ItemSortBy.PREMIERE_DATE),
							sortOrder = setOf(SortOrder.DESCENDING),
							fields = setOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO),
						)
					).content.items
				}.getOrDefault(emptyList())
			}
		}
	}
}

/** An actor's titles in the library, newest first. */
@Composable
fun PersonScreen(application: LumaFinApplication, route: Route.Person) {
	val viewModel: PersonViewModel = viewModel(key = "person-${route.id}") { PersonViewModel(application, route.id) }
	val items by viewModel.items.collectAsState()
	val api = application.sessions.api ?: return
	val metrics = currentMetrics()
	Column(Modifier.fillMaxSize().safeDrawingPadding()) {
		PageTitle(route.name, metrics.gutter)
		if (items == null) Loading()
		else LazyVerticalGrid(
			columns = GridCells.Adaptive(metrics.posterWidth),
			contentPadding = PaddingValues(start = metrics.gutter, end = metrics.gutter, bottom = 24.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			verticalArrangement = Arrangement.spacedBy(16.dp),
		) {
			items(items.orEmpty(), key = { it.id }) { ItemCard(api, it, landscape = false, width = metrics.posterWidth, modifier = Modifier.fillMaxWidth()) }
		}
	}
}

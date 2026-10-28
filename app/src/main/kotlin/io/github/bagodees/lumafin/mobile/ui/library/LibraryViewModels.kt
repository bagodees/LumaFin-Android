package io.github.bagodees.lumafin.mobile.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bagodees.lumafin.core.toprated.loadTopRated
import io.github.bagodees.lumafin.mobile.LumaFinApplication
import io.github.bagodees.lumafin.mobile.R
import io.github.bagodees.lumafin.mobile.ui.common.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber

class LibrariesViewModel(application: Application) : AndroidViewModel(application) {
	private val app = application as LumaFinApplication
	private val _libraries = MutableStateFlow<List<BaseItemDto>?>(null)
	val libraries: StateFlow<List<BaseItemDto>?> = _libraries.asStateFlow()

	init {
		viewModelScope.launch {
			val api = app.sessions.api ?: return@launch
			_libraries.value = runCatching { withContext(Dispatchers.IO) { api.userViewsApi.getUserViews().content.items } }
				.onFailure { if (it is CancellationException) throw it; Timber.w(it, "Libraries didn't load") }
				.getOrDefault(emptyList())
				// Live TV and playlists open elsewhere in the TV app; the phone app starts with movies, shows and collections
				.filter { it.collectionType?.serialName in setOf("movies", "tvshows", "boxsets", "homevideos", "mixed", null) }
		}
	}
}

enum class LibrarySort(val label: Int) {
	NAME(R.string.sort_name),
	ADDED(R.string.sort_added),
	RELEASED(R.string.sort_released),
	TOP_RATED(R.string.sort_top_rated),
}

data class LibraryState(val items: List<BaseItemDto> = emptyList(), val sort: LibrarySort = LibrarySort.NAME, val loading: Boolean = true, val complete: Boolean = false)

class LibraryViewModel(application: LumaFinApplication, private val library: Route.Library) : AndroidViewModel(application) {
	private val api = application.sessions.api
	private val _state = MutableStateFlow(LibraryState())
	val state: StateFlow<LibraryState> = _state.asStateFlow()
	private var job: Job? = null

	private val itemType = when (library.collectionType) {
		"movies" -> BaseItemKind.MOVIE
		"tvshows" -> BaseItemKind.SERIES
		"boxsets" -> BaseItemKind.BOX_SET
		else -> null
	}

	init {
		loadMore()
	}

	fun sortBy(sort: LibrarySort) {
		if (sort == _state.value.sort) return
		job?.cancel()
		_state.value = LibraryState(sort = sort)
		loadMore()
	}

	fun loadMore() {
		val current = _state.value
		val api = api ?: return
		if (current.complete || (job?.isActive == true)) return
		_state.value = current.copy(loading = true)
		job = viewModelScope.launch {
			try {
				val page = withContext(Dispatchers.IO) {
					if (current.sort == LibrarySort.TOP_RATED) {
						// Ranked by audience and critic scores together, like the TV app's Top Rated
						loadTopRated(api, library.id, itemType ?: BaseItemKind.MOVIE, 60, FIELDS)
					} else api.itemsApi.getItems(
						GetItemsRequest(
							parentId = library.id,
							includeItemTypes = itemType?.let { setOf(it) },
							recursive = itemType != null,
							sortBy = when (current.sort) {
								LibrarySort.ADDED -> listOf(ItemSortBy.DATE_CREATED)
								LibrarySort.RELEASED -> listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME)
								else -> listOf(ItemSortBy.SORT_NAME)
							},
							sortOrder = listOf(if (current.sort == LibrarySort.NAME) SortOrder.ASCENDING else SortOrder.DESCENDING),
							startIndex = current.items.size,
							limit = PAGE,
							fields = FIELDS,
							imageTypeLimit = 1,
							enableTotalRecordCount = false,
						)
					).content.items
				}
				_state.value = current.copy(
					items = (current.items + page).distinctBy { it.id },
					loading = false,
					complete = current.sort == LibrarySort.TOP_RATED || page.size < PAGE,
				)
			} catch (error: CancellationException) {
				throw error
			} catch (error: Exception) {
				Timber.w(error, "Library page didn't load")
				_state.value = current.copy(loading = false, complete = true)
			}
		}
	}

	private companion object {
		const val PAGE = 60
		val FIELDS = setOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO)
	}
}

package io.github.bagodees.lumafin.mobile.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bagodees.lumafin.mobile.LumaFinApplication
import io.github.bagodees.lumafin.mobile.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import timber.log.Timber

enum class CardStyle { LANDSCAPE, PORTRAIT }

data class HomeRow(val title: String, val items: List<BaseItemDto>, val style: CardStyle)

data class HomeState(val loading: Boolean = true, val rows: List<HomeRow> = emptyList(), val failed: Boolean = false)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
	private val sessions = (application as LumaFinApplication).sessions
	private val _state = MutableStateFlow(HomeState())
	val state: StateFlow<HomeState> = _state.asStateFlow()

	init {
		refresh()
	}

	fun refresh() {
		val api = sessions.api ?: return
		_state.value = _state.value.copy(loading = true, failed = false)
		viewModelScope.launch {
			try {
				val rows = withContext(Dispatchers.IO) { loadRows(api) }
				_state.value = HomeState(loading = false, rows = rows)
			} catch (error: CancellationException) {
				throw error
			} catch (error: Exception) {
				Timber.w(error, "Home didn't load")
				_state.value = _state.value.copy(loading = false, failed = true)
			}
		}
	}

	private suspend fun loadRows(api: ApiClient): List<HomeRow> = coroutineScope {
		val context = getApplication<Application>()
		val fields = setOf(ItemFields.OVERVIEW, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO)
		val resume = async {
			api.itemsApi.getResumeItems(GetResumeItemsRequest(limit = 20, fields = fields, enableTotalRecordCount = false)).content.items
		}
		val nextUp = async {
			api.tvShowsApi.getNextUp(GetNextUpRequest(limit = 20, fields = fields, enableResumable = false)).content.items
		}
		val movies = async {
			api.userLibraryApi.getLatestMedia(GetLatestMediaRequest(includeItemTypes = setOf(BaseItemKind.MOVIE), limit = 20, fields = fields)).content
		}
		val shows = async {
			// Grouped, so a show with new episodes appears once
			api.userLibraryApi.getLatestMedia(GetLatestMediaRequest(includeItemTypes = setOf(BaseItemKind.EPISODE), limit = 20, fields = fields, groupItems = true)).content
		}
		listOf(
			HomeRow(context.getString(R.string.home_continue_watching), resume.await(), CardStyle.LANDSCAPE),
			HomeRow(context.getString(R.string.home_next_up), nextUp.await(), CardStyle.LANDSCAPE),
			HomeRow(context.getString(R.string.home_recent_movies), movies.await(), CardStyle.PORTRAIT),
			HomeRow(context.getString(R.string.home_recent_shows), shows.await(), CardStyle.PORTRAIT),
		).filter { it.items.isNotEmpty() }
	}
}

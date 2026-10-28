package io.github.bagodees.lumafin.mobile.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bagodees.lumafin.core.seerr.SeerrClient
import io.github.bagodees.lumafin.core.seerr.SeerrItem
import io.github.bagodees.lumafin.mobile.LumaFinApplication
import io.github.bagodees.lumafin.mobile.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

sealed interface HomeRow {
	val title: String

	data class Items(override val title: String, val items: List<BaseItemDto>, val landscape: Boolean) : HomeRow
	data class Seerr(override val title: String, val items: List<SeerrItem>) : HomeRow
}

data class HomeState(val loading: Boolean = true, val rows: List<HomeRow> = emptyList(), val failed: Boolean = false)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
	private val app = application as LumaFinApplication
	private val _state = MutableStateFlow(HomeState())
	val state: StateFlow<HomeState> = _state.asStateFlow()

	init {
		refresh()
	}

	fun refresh() {
		val api = app.sessions.api ?: return
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
		// Seerr rows come last, like on the TV, and only when the server has SeerrFin and they're turned on
		val seerr = if (!app.settings.seerrRows) emptyList() else {
			val requests = async { SeerrClient.myRequests(api).orEmpty() }
			val discover = SeerrClient.discoverRows.map { (key, path) -> async { key to SeerrClient.discover(api, path).orEmpty() } }
			listOf(HomeRow.Seerr(context.getString(R.string.seerr_my_requests), requests.await())) +
				discover.awaitAll().map { (key, items) -> HomeRow.Seerr(context.getString(seerrRowTitle(key)), items) }
		}
		(listOf(
			HomeRow.Items(context.getString(R.string.home_continue_watching), resume.await(), landscape = true),
			HomeRow.Items(context.getString(R.string.home_next_up), nextUp.await(), landscape = true),
			HomeRow.Items(context.getString(R.string.home_recent_movies), movies.await(), landscape = false),
			HomeRow.Items(context.getString(R.string.home_recent_shows), shows.await(), landscape = false),
		) + seerr).filter {
			when (it) {
				is HomeRow.Items -> it.items.isNotEmpty()
				is HomeRow.Seerr -> it.items.isNotEmpty()
			}
		}
	}

	private fun seerrRowTitle(key: String) = when (key) {
		"trending_movies" -> R.string.seerr_trending_movies
		"trending_shows" -> R.string.seerr_trending_shows
		"upcoming_movies" -> R.string.seerr_upcoming_movies
		else -> R.string.seerr_upcoming_shows
	}
}

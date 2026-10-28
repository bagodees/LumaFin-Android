package io.github.bagodees.lumafin.mobile.ui.details

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bagodees.lumafin.core.seerr.SeerrClient
import io.github.bagodees.lumafin.mobile.LumaFinApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import timber.log.Timber
import java.util.UUID

data class DetailsState(
	val item: BaseItemDto? = null,
	val failed: Boolean = false,
	val seasons: List<BaseItemDto> = emptyList(),
	val selectedSeason: UUID? = null,
	val episodes: List<BaseItemDto> = emptyList(),
	/** For a show: the episode Play starts (the next one to watch). */
	val nextEpisode: BaseItemDto? = null,
	val similar: List<BaseItemDto> = emptyList(),
	/** Seasons Seerr can still get for this show, offered as a request on the page. */
	val missingSeasons: List<Int> = emptyList(),
)

class DetailsViewModel(application: LumaFinApplication, private val itemId: UUID) : AndroidViewModel(application) {
	private val api = application.sessions.api
	private val seerrEnabled = application.settings.seerrRows
	private val _state = MutableStateFlow(DetailsState())
	val state: StateFlow<DetailsState> = _state.asStateFlow()

	init {
		load()
	}

	fun load() {
		val api = api ?: return
		viewModelScope.launch {
			try {
				val item = withContext(Dispatchers.IO) { api.userLibraryApi.getItem(itemId).content }
				_state.value = _state.value.copy(item = item, failed = false)
				val similar = async(Dispatchers.IO) {
					runCatching { api.libraryApi.getSimilarItems(itemId, limit = 16, fields = listOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO)).content.items }.getOrDefault(emptyList())
				}
				when (item.type) {
					BaseItemKind.SERIES -> loadSeries(item)
					BaseItemKind.SEASON -> item.seriesId?.let { loadSeasons(it, select = item.id) }
					else -> Unit
				}
				// Each result is awaited before reading the state, so updates made meanwhile (the season's episodes) aren't lost
				val similarItems = similar.await().filter { it.type != BaseItemKind.EPISODE }
				_state.value = _state.value.copy(similar = similarItems)
				if (item.type == BaseItemKind.SERIES && seerrEnabled) {
					val tmdb = item.providerIds?.get("Tmdb")?.toIntOrNull()
					val missing = if (tmdb != null) SeerrClient.missingSeasons(api, tmdb).orEmpty() else emptyList()
					_state.value = _state.value.copy(missingSeasons = missing)
				}
			} catch (error: CancellationException) {
				throw error
			} catch (error: Exception) {
				Timber.w(error, "Details didn't load")
				_state.value = _state.value.copy(failed = true)
			}
		}
	}

	private suspend fun loadSeries(series: BaseItemDto) {
		val api = api ?: return
		val next = withContext(Dispatchers.IO) {
			runCatching { api.tvShowsApi.getNextUp(GetNextUpRequest(seriesId = series.id, limit = 1, enableResumable = true)).content.items.firstOrNull() }.getOrNull()
		}
		_state.value = _state.value.copy(nextEpisode = next)
		loadSeasons(series.id, select = next?.seasonId)
	}

	private suspend fun loadSeasons(seriesId: UUID, select: UUID?) {
		val api = api ?: return
		val seasons = withContext(Dispatchers.IO) { api.tvShowsApi.getSeasons(seriesId).content.items }
		val selected = seasons.firstOrNull { it.id == select } ?: seasons.firstOrNull { (it.indexNumber ?: 0) > 0 } ?: seasons.firstOrNull()
		_state.value = _state.value.copy(seasons = seasons)
		selected?.let { selectSeason(seriesId, it.id) }
	}

	fun selectSeason(seriesId: UUID, seasonId: UUID) {
		val api = api ?: return
		_state.value = _state.value.copy(selectedSeason = seasonId)
		viewModelScope.launch {
			val episodes = withContext(Dispatchers.IO) {
				runCatching {
					api.tvShowsApi.getEpisodes(seriesId, seasonId = seasonId, fields = listOf(ItemFields.OVERVIEW, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO)).content.items
				}.onFailure { if (it is CancellationException) throw it; Timber.w(it, "Episodes didn't load") }.getOrDefault(emptyList())
			}
			if (_state.value.selectedSeason == seasonId) _state.value = _state.value.copy(episodes = episodes)
		}
	}

	/** Marks the title watched or unwatched; only ever on the user's tap. */
	fun toggleWatched() {
		val api = api ?: return
		val item = _state.value.item ?: return
		val played = item.userData?.played == true
		viewModelScope.launch {
			runCatching {
				withContext(Dispatchers.IO) {
					if (played) api.playStateApi.markUnplayedItem(item.id) else api.playStateApi.markPlayedItem(item.id)
				}
			}.onSuccess { load() }
		}
	}
}

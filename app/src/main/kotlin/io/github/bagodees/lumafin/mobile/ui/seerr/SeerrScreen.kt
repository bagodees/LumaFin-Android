package io.github.bagodees.lumafin.mobile.ui.seerr

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.github.bagodees.lumafin.core.seerr.SeerrClient
import io.github.bagodees.lumafin.core.seerr.SeerrDetails
import io.github.bagodees.lumafin.core.seerr.SeerrItem
import io.github.bagodees.lumafin.core.seerr.loadSeerrDetails
import io.github.bagodees.lumafin.core.seerr.loadSeerrRecommendations
import io.github.bagodees.lumafin.mobile.LumaFinApplication
import io.github.bagodees.lumafin.mobile.R
import io.github.bagodees.lumafin.mobile.ui.common.CardRow
import io.github.bagodees.lumafin.mobile.ui.common.LocalNavigator
import io.github.bagodees.lumafin.mobile.ui.common.Route
import io.github.bagodees.lumafin.mobile.ui.common.SeerrCard
import io.github.bagodees.lumafin.mobile.ui.common.currentMetrics
import io.github.bagodees.lumafin.mobile.ui.library.Loading
import io.github.bagodees.lumafin.mobile.ui.theme.LumaFinColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SeerrPageState(val details: SeerrDetails? = null, val failed: Boolean = false, val recommendations: List<SeerrItem> = emptyList())

class SeerrViewModel(private val application: LumaFinApplication, private val route: Route.Seerr) : AndroidViewModel(application) {
	private val _state = MutableStateFlow(SeerrPageState())
	val state: StateFlow<SeerrPageState> = _state.asStateFlow()

	init {
		load()
	}

	fun load() {
		val api = application.sessions.api ?: return
		viewModelScope.launch {
			val details = loadSeerrDetails(api, route.mediaType, route.mediaId)
			_state.value = SeerrPageState(details, failed = details == null)
			_state.value = _state.value.copy(recommendations = loadSeerrRecommendations(api, route.mediaType, route.mediaId))
		}
	}

	suspend fun request(seasons: List<Int>): Boolean {
		val api = application.sessions.api ?: return false
		val item = _state.value.details?.item ?: return false
		return SeerrClient.request(api, item, seasons).also { if (it) load() }
	}
}

/** A title that isn't in the library yet: its info from Seerr, with Request, Trailer, cast and similar titles. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeerrScreen(application: LumaFinApplication, route: Route.Seerr) {
	val viewModel: SeerrViewModel = viewModel(key = "seerr-${route.mediaType}-${route.mediaId}") { SeerrViewModel(application, route) }
	val state by viewModel.state.collectAsState()
	val metrics = currentMetrics()
	val context = LocalContext.current
	val navigator = LocalNavigator.current
	val scope = rememberCoroutineScope()
	var choosing by remember { mutableStateOf(false) }
	val details = state.details
	if (details == null) {
		if (state.failed) Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
			Text(stringResource(R.string.seerr_details_failed), color = MaterialTheme.colorScheme.onSurfaceVariant)
		} else Loading()
		return
	}
	val item = details.item

	LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
		item {
			Box(Modifier.fillMaxWidth().aspectRatio(if (metrics.wide) 21f / 8f else 16f / 10f)) {
				AsyncImage(model = details.backdropUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
				Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to LumaFinColors.Background)))
			}
		}
		item {
			Column(Modifier.padding(horizontal = metrics.gutter).widthIn(max = 760.dp)) {
				Text(item.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
				Text(
					listOfNotNull(
						details.releaseDate?.year?.toString() ?: item.year?.toString(),
						details.certification,
						details.runtimeMinutes?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
						item.rating?.let { "★ %.1f".format(it) },
						details.criticRating?.let { "${it.toInt()}%" },
					).joinToString(" · "),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(top = 6.dp),
				)
				val credits = details.credits?.let { stringResource(if (item.mediaType == "tv") R.string.seerr_created_by else R.string.seerr_directed_by, it) }
				listOfNotNull(details.genres.take(3).joinToString(" / ").ifEmpty { null }, credits).forEach {
					Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
				}
				FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
					details.jellyfinItemId?.let { id -> Button(onClick = { navigator.open(Route.Item(id)) }) { Text(stringResource(R.string.seerr_open_in_library)) } }
					val tv = item.mediaType == "tv"
					when {
						(details.requested || details.status == 4) && details.requestableSeasons.isNotEmpty() ->
							Button(onClick = { choosing = true }) { Text(stringResource(R.string.seerr_request_missing_seasons)) }
						details.requested -> OutlinedButton(onClick = {
							Toast.makeText(context, R.string.seerr_status_requested_long, Toast.LENGTH_LONG).show()
						}) { Text(stringResource(R.string.seerr_status_requested)) }
						!details.inLibrary && (!tv || details.requestableSeasons.isNotEmpty()) ->
							Button(onClick = { choosing = true }) { Text(stringResource(R.string.seerr_request)) }
					}
					details.trailerUrl?.let { url ->
						OutlinedButton(onClick = {
							runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
								.onFailure { Toast.makeText(context, R.string.seerr_trailer_failed, Toast.LENGTH_LONG).show() }
						}) { Text(stringResource(R.string.seerr_trailer)) }
					}
				}
				details.overview?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp)) }
			}
		}
		if (details.cast.isNotEmpty()) item {
			CardRow(stringResource(R.string.details_cast), details.cast, metrics, key = { "${it.name}-${it.role}" }) { person ->
				Column(Modifier.width(96.dp)) {
					AsyncImage(
						model = person.imageUrl,
						contentDescription = null,
						contentScale = ContentScale.Crop,
						modifier = Modifier.size(96.dp, 128.dp).clip(RoundedCornerShape(12.dp)).background(LumaFinColors.Raised),
					)
					Text(person.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
					person.role?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
				}
			}
		}
		if (state.recommendations.isNotEmpty()) item {
			CardRow(stringResource(R.string.details_more_like_this), state.recommendations, metrics, key = { "${it.mediaType}/${it.mediaId}" }) {
				SeerrCard(it, landscape = false, width = metrics.posterWidth)
			}
		}
	}

	if (choosing) {
		val tv = item.mediaType == "tv"
		val chosen = remember { details.requestableSeasons.map { it.number }.toMutableStateList() }
		AlertDialog(
			onDismissRequest = { choosing = false },
			title = { Text(item.name) },
			text = {
				Column {
					Text(stringResource(if (!tv) R.string.seerr_request_movie_detail else if (details.requested || details.status == 4) R.string.seerr_choose_missing_seasons else R.string.seerr_choose_seasons))
					if (tv) details.requestableSeasons.forEach { season ->
						Row(
							verticalAlignment = Alignment.CenterVertically,
							modifier = Modifier.fillMaxWidth().clickable { if (season.number in chosen) chosen.remove(season.number) else chosen.add(season.number) },
						) {
							Checkbox(checked = season.number in chosen, onCheckedChange = null, modifier = Modifier.padding(12.dp))
							Text(
								listOfNotNull(
									stringResource(R.string.seerr_season, season.number),
									season.episodes?.let { pluralStringResource(R.plurals.episodes, it, it) },
								).joinToString(" · "),
							)
						}
					}
				}
			},
			confirmButton = {
				TextButton(enabled = !tv || chosen.isNotEmpty(), onClick = {
					choosing = false
					scope.launch {
						val ok = viewModel.request(if (tv) chosen.sorted() else emptyList())
						Toast.makeText(context, context.getString(if (ok) R.string.seerr_requested else R.string.seerr_request_failed, item.name), Toast.LENGTH_LONG).show()
					}
				}) { Text(stringResource(R.string.seerr_request)) }
			},
			dismissButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(android.R.string.cancel)) } },
		)
	}
}

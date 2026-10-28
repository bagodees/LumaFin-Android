package io.github.bagodees.lumafin.mobile.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.github.bagodees.lumafin.mobile.LumaFinApplication
import io.github.bagodees.lumafin.mobile.R
import io.github.bagodees.lumafin.mobile.data.DownloadStatus
import io.github.bagodees.lumafin.mobile.data.backdropImage
import io.github.bagodees.lumafin.mobile.data.landscapeImage
import io.github.bagodees.lumafin.mobile.data.posterImage
import io.github.bagodees.lumafin.mobile.ui.cast.CastButton
import io.github.bagodees.lumafin.mobile.ui.common.CardRow
import io.github.bagodees.lumafin.mobile.ui.common.ItemCard
import io.github.bagodees.lumafin.mobile.ui.common.LocalNavigator
import io.github.bagodees.lumafin.mobile.ui.common.Metrics
import io.github.bagodees.lumafin.mobile.ui.common.Route
import io.github.bagodees.lumafin.mobile.ui.common.currentMetrics
import io.github.bagodees.lumafin.mobile.ui.downloads.DownloadButton
import io.github.bagodees.lumafin.mobile.ui.downloads.DownloadSeasonButton
import io.github.bagodees.lumafin.mobile.ui.library.Loading
import io.github.bagodees.lumafin.mobile.ui.player.PlayerActivity
import io.github.bagodees.lumafin.mobile.ui.theme.LumaFinColors
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.PersonKind

@Composable
fun DetailsScreen(application: LumaFinApplication, route: Route.Item) {
	val viewModel: DetailsViewModel = viewModel(key = "details-${route.id}") { DetailsViewModel(application, route.id) }
	val state by viewModel.state.collectAsState()
	val api = application.sessions.api ?: return
	val metrics = currentMetrics()
	val item = state.item ?: return Loading()

	if (metrics.wide) {
		// Unfolded foldables and tablets: the title's info on the left, seasons, episodes and rows on the right
		Box(Modifier.fillMaxSize()) {
			AsyncImage(model = api.backdropImage(item), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
			Box(Modifier.fillMaxSize().background(LumaFinColors.Background.copy(alpha = 0.86f)))
			Row(Modifier.fillMaxSize().safeDrawingPadding()) {
				LazyColumn(Modifier.weight(0.42f).fillMaxSize(), contentPadding = PaddingValues(start = metrics.gutter, end = 16.dp, top = 24.dp, bottom = 24.dp)) {
					item { Info(application, api, item, state, viewModel, showPoster = true) }
				}
				LazyColumn(Modifier.weight(0.58f).fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
					rest(application, api, item, state, viewModel, metrics)
				}
			}
		}
	} else {
		LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
			item {
				Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
					AsyncImage(model = api.backdropImage(item, 1280), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
					Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to LumaFinColors.Background)))
				}
			}
			item { Box(Modifier.padding(horizontal = metrics.gutter)) { Info(application, api, item, state, viewModel, showPoster = false) } }
			rest(application, api, item, state, viewModel, metrics)
		}
	}
}

/** "2021 · PG-13 · 1h 42m · ★ 7.8 · 91%" */
private fun infoLine(item: BaseItemDto) = listOfNotNull(
	item.productionYear?.toString(),
	item.officialRating,
	item.runTimeTicks?.let { ticks -> (ticks / 600_000_000L).takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" } },
	item.communityRating?.let { "★ %.1f".format(it) },
	item.criticRating?.let { "${it.toInt()}%" },
).joinToString(" · ")

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Info(application: LumaFinApplication, api: ApiClient, item: BaseItemDto, state: DetailsState, viewModel: DetailsViewModel, showPoster: Boolean) {
	val context = LocalContext.current
	val navigator = LocalNavigator.current
	var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
	Column {
		if (showPoster) AsyncImage(
			model = if (item.type == BaseItemKind.EPISODE) api.landscapeImage(item) else api.posterImage(item, 600),
			contentDescription = null,
			contentScale = ContentScale.Crop,
			modifier = Modifier.width(if (item.type == BaseItemKind.EPISODE) 320.dp else 200.dp)
				.aspectRatio(if (item.type == BaseItemKind.EPISODE) 16f / 9f else 2f / 3f).clip(RoundedCornerShape(12.dp)).background(LumaFinColors.Raised),
		)
		if (item.type == BaseItemKind.EPISODE && item.seriesName != null) TextButton(
			onClick = { item.seriesId?.let { navigator.open(Route.Item(it)) } },
			contentPadding = PaddingValues(0.dp),
			modifier = Modifier.padding(top = 12.dp),
		) { Text(item.seriesName.orEmpty(), style = MaterialTheme.typography.titleMedium) }
		Text(
			if (item.type == BaseItemKind.EPISODE) listOfNotNull(item.parentIndexNumber?.let { s -> item.indexNumber?.let { "S$s E$it" } }, item.name).joinToString(" · ") else item.name.orEmpty(),
			style = MaterialTheme.typography.headlineSmall,
			fontWeight = FontWeight.SemiBold,
			modifier = Modifier.padding(top = if (showPoster && item.type != BaseItemKind.EPISODE) 16.dp else 4.dp),
		)
		Text(infoLine(item), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
		item.genres?.takeIf { it.isNotEmpty() }?.let {
			Text(it.take(3).joinToString(" / "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
		}

		// Play starts where you left off; a show plays its next episode
		val playTarget = if (item.type == BaseItemKind.SERIES || item.type == BaseItemKind.SEASON) state.nextEpisode ?: state.episodes.firstOrNull() else item
		val resumeTicks = playTarget?.userData?.playbackPositionTicks ?: 0
		androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
			// While casting, Play sends the title to the other screen
			if (playTarget != null && item.type != BaseItemKind.BOX_SET) Button(onClick = {
				val api = application.sessions.api
				if (api == null || !application.cast.playOnCurrent(api, playTarget)) context.startActivity(PlayerActivity.intent(context, playTarget.id))
			}) {
				Icon(painterResource(R.drawable.ic_play), contentDescription = null, modifier = Modifier.size(18.dp))
				Text(
					when {
						resumeTicks > 0 -> stringResource(R.string.details_resume)
						playTarget.id != item.id -> stringResource(R.string.details_play_episode, playTarget.parentIndexNumber ?: 0, playTarget.indexNumber ?: 0)
						else -> stringResource(R.string.details_play)
					},
					modifier = Modifier.padding(start = 6.dp),
				)
			}
			if (playTarget != null && item.type != BaseItemKind.BOX_SET) CastButton(application, playTarget)
			if (item.type == BaseItemKind.MOVIE || item.type == BaseItemKind.EPISODE) DownloadButton(application, item)
			OutlinedButton(onClick = viewModel::toggleWatched) {
				Text(stringResource(if (item.userData?.played == true) R.string.details_mark_unwatched else R.string.details_mark_watched))
			}
		}
		if (state.missingSeasons.isNotEmpty()) {
			val tmdb = item.providerIds?.get("Tmdb")?.toIntOrNull()
			if (tmdb != null) OutlinedButton(onClick = { navigator.open(Route.Seerr("tv", tmdb)) }, modifier = Modifier.padding(top = 8.dp)) {
				Text(stringResource(R.string.seerr_request_missing_seasons))
			}
		}
		item.overview?.let {
			Text(
				it,
				style = MaterialTheme.typography.bodyMedium,
				maxLines = if (expanded) Int.MAX_VALUE else 4,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.padding(top = 16.dp).clickable { expanded = !expanded },
			)
		}
	}
}

private fun LazyListScope.rest(application: LumaFinApplication, api: ApiClient, item: BaseItemDto, state: DetailsState, viewModel: DetailsViewModel, metrics: Metrics) {
	val seriesId = if (item.type == BaseItemKind.SERIES) item.id else item.seriesId
	if (state.seasons.isNotEmpty() && seriesId != null) {
		item(key = "seasons") {
			val chips = androidx.compose.foundation.lazy.rememberLazyListState()
			// The season you're on is scrolled into view
			androidx.compose.runtime.LaunchedEffect(state.selectedSeason, state.seasons) {
				val index = state.seasons.indexOfFirst { it.id == state.selectedSeason }
				if (index > 0) chips.animateScrollToItem(index)
			}
			androidx.compose.foundation.lazy.LazyRow(
				state = chips,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				contentPadding = PaddingValues(horizontal = metrics.gutter),
				modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
			) {
				items(state.seasons, key = { it.id }) { season ->
					FilterChip(selected = season.id == state.selectedSeason, onClick = { viewModel.selectSeason(seriesId, season.id) }, label = { Text(season.name.orEmpty()) })
				}
			}
		}
		if (state.episodes.isNotEmpty()) item(key = "download-season") {
			DownloadSeasonButton(application, state.episodes, Modifier.padding(horizontal = metrics.gutter - 12.dp))
		}
		items(state.episodes, key = { "episode-${it.id}" }) { episode -> EpisodeRow(application, api, episode, metrics) }
	}
	val cast = item.people.orEmpty().filter { it.type == PersonKind.ACTOR || it.type == PersonKind.GUEST_STAR }.take(24)
	if (cast.isNotEmpty()) item(key = "cast") {
		CardRow(stringResource(R.string.details_cast), cast, metrics, key = { "${it.id}-${it.role}" }) { PersonCard(api, it) }
	}
	if (state.similar.isNotEmpty()) item(key = "similar") {
		CardRow(stringResource(R.string.details_more_like_this), state.similar, metrics, key = { it.id }) {
			ItemCard(api, it, landscape = false, width = metrics.posterWidth)
		}
	}
}

@Composable
private fun EpisodeRow(application: LumaFinApplication, api: ApiClient, episode: BaseItemDto, metrics: Metrics) {
	val downloads by application.downloads.downloads.collectAsState()
	val downloaded = downloads.any { it.itemId == episode.id && it.status == DownloadStatus.DONE }
	val navigator = LocalNavigator.current
	val context = LocalContext.current
	Row(
		Modifier.fillMaxWidth().clickable { navigator.open(Route.Item(episode.id)) }.padding(horizontal = metrics.gutter, vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(LumaFinColors.Raised).clickable {
			if (!application.cast.playOnCurrent(api, episode)) context.startActivity(PlayerActivity.intent(context, episode.id))
		}) {
			AsyncImage(model = api.landscapeImage(episode, 400), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
			Icon(
				painterResource(R.drawable.ic_play),
				contentDescription = stringResource(R.string.details_play),
				tint = Color.White,
				modifier = Modifier.align(Alignment.Center).size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(8.dp),
			)
			val progress = episode.userData?.playedPercentage?.toFloat()?.div(100f)
			if (progress != null && progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(3.dp).background(LumaFinColors.Cyan))
		}
		Column(Modifier.padding(start = 14.dp).weight(1f)) {
			Text(
				"${episode.indexNumber ?: ""}. ${episode.name.orEmpty()}",
				style = MaterialTheme.typography.titleSmall,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			val minutes = episode.runTimeTicks?.div(600_000_000L)
			Text(
				listOfNotNull(
					minutes?.takeIf { it > 0 }?.let { "${it}m" },
					if (episode.userData?.played == true) stringResource(R.string.details_watched) else null,
					if (downloaded) stringResource(R.string.download_downloaded) else null,
				).joinToString(" · "),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			episode.overview?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
		}
	}
}

@Composable
private fun PersonCard(api: ApiClient, person: BaseItemPerson) {
	val navigator = LocalNavigator.current
	Column(Modifier.width(96.dp).clip(RoundedCornerShape(12.dp)).clickable { navigator.open(Route.Person(person.id, person.name.orEmpty())) }) {
		AsyncImage(
			model = person.primaryImageTag?.let { api.imageApi.getItemImageUrl(person.id, ImageType.PRIMARY, tag = it, maxWidth = 240) },
			contentDescription = null,
			contentScale = ContentScale.Crop,
			modifier = Modifier.size(96.dp, 128.dp).clip(RoundedCornerShape(12.dp)).background(LumaFinColors.Raised),
		)
		Text(person.name.orEmpty(), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
		person.role?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
	}
}

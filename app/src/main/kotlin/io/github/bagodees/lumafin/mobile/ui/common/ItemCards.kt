package io.github.bagodees.lumafin.mobile.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.width
import io.github.bagodees.lumafin.core.seerr.SeerrItem
import io.github.bagodees.lumafin.core.seerr.SeerrRequestStatus
import io.github.bagodees.lumafin.mobile.R
import io.github.bagodees.lumafin.mobile.data.landscapeImage
import io.github.bagodees.lumafin.mobile.data.posterImage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import android.content.Context
import androidx.compose.ui.platform.LocalContext

/** "S1 E3 · Name" for episodes, "year · rating" for everything else. */
fun subtitleFor(item: BaseItemDto): String? = when (item.type) {
	BaseItemKind.EPISODE -> listOfNotNull(
		item.parentIndexNumber?.let { season -> item.indexNumber?.let { "S$season E$it" } },
		item.name,
	).joinToString(" · ").ifEmpty { null }
	else -> listOfNotNull(item.productionYear?.toString(), item.officialRating).joinToString(" · ").ifEmpty { null }
}

/** The name a card shows: the show's name for an episode. */
fun titleFor(item: BaseItemDto) = item.seriesName?.takeIf { item.type == BaseItemKind.EPISODE } ?: item.name.orEmpty()

@Composable
fun ItemCard(api: ApiClient, item: BaseItemDto, landscape: Boolean, width: Dp, modifier: Modifier = Modifier) {
	val navigator = LocalNavigator.current
	MediaCard(
		image = if (landscape) api.landscapeImage(item) else api.posterImage(item),
		title = titleFor(item),
		subtitle = subtitleFor(item),
		aspect = if (landscape) 16f / 9f else 2f / 3f,
		progress = item.userData?.playedPercentage?.toFloat()?.div(100f),
		modifier = modifier.width(width),
		onClick = { navigator.open(Route.Item(item.id)) },
	)
}

/** Plain words for where a Seerr request stands. */
fun SeerrRequestStatus.label(context: Context): String = when (this) {
	is SeerrRequestStatus.Downloading -> context.getString(R.string.seerr_status_downloading, percent)
	SeerrRequestStatus.Waiting -> context.getString(R.string.seerr_status_waiting)
	SeerrRequestStatus.Unreleased -> context.getString(R.string.seerr_status_unreleased)
	SeerrRequestStatus.Failed -> context.getString(R.string.seerr_status_failed)
	SeerrRequestStatus.InLibrary -> context.getString(R.string.seerr_status_in_library)
	SeerrRequestStatus.Arriving -> context.getString(R.string.seerr_status_arriving)
	SeerrRequestStatus.PartlyInLibrary -> context.getString(R.string.seerr_status_partly_in_library)
	SeerrRequestStatus.AwaitingApproval -> context.getString(R.string.seerr_status_awaiting_approval)
	SeerrRequestStatus.Declined -> context.getString(R.string.seerr_status_declined)
	SeerrRequestStatus.Requested -> context.getString(R.string.seerr_status_requested)
}

/** A Seerr title: opens the library page once it's arrived, otherwise its Seerr page. */
@Composable
fun SeerrCard(item: SeerrItem, landscape: Boolean, width: Dp, modifier: Modifier = Modifier) {
	val navigator = LocalNavigator.current
	val context = LocalContext.current
	MediaCard(
		image = if (landscape) item.imageUrl ?: item.posterUrl else item.posterUrl ?: item.imageUrl,
		title = item.name,
		subtitle = item.requestStatus?.label(context) ?: item.year?.toString(),
		aspect = if (landscape) 16f / 9f else 2f / 3f,
		modifier = modifier.width(width),
		onClick = {
			val libraryId = item.jellyfinItemId
			navigator.open(if (libraryId != null) Route.Item(libraryId) else Route.Seerr(item.mediaType, item.mediaId))
		},
	)
}

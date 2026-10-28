package io.github.bagodees.lumafin.core.toprated

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.util.UUID

/** Audience scores this high only come from a handful of votes; the best-loved films sit just below it. */
private const val UNRELIABLE_COMMUNITY_RATING = 9.0
/** What an unreliable audience score counts as: an ordinary, decent title. */
private const val NEUTRAL_COMMUNITY_RATING = 7.0
/** A perfect critic score usually means only a few reviews, so it counts as a very good one. */
private const val PERFECT_CRITIC_RATING_COUNTS_AS = 90.0
/** How many titles are fetched by each score before ranking. */
private const val POOL_SIZE = 150

/**
 * A title's place in Top Rated, out of 100: the audience score (out of 10) and the critic score (a
 * percentage) averaged when both exist. A title with only one of them counts a little lower, so one
 * strong number alone doesn't beat two good ones. Null when the title has neither.
 */
fun topRatedScore(communityRating: Float?, criticRating: Float?): Double? {
	val community = communityRating?.toDouble()?.takeIf { it > 0 }
		?.let { if (it >= UNRELIABLE_COMMUNITY_RATING) NEUTRAL_COMMUNITY_RATING else it }
	val critic = criticRating?.toDouble()?.takeIf { it > 0 }?.let { if (it >= 100) PERFECT_CRITIC_RATING_COUNTS_AS else it }
	return when {
		community != null && critic != null -> (community * 10 + critic) / 2
		critic != null -> critic * 0.9
		community != null -> community * 10 * 0.85
		else -> null
	}
}

/** The best [limit] of [items] by [topRatedScore], each title once. */
fun rankTopRated(items: List<BaseItemDto>, limit: Int): List<BaseItemDto> = items
	.distinctBy { it.id }
	.mapNotNull { item -> topRatedScore(item.communityRating, item.criticRating)?.let { item to it } }
	.sortedByDescending { it.second }
	.take(limit)
	.map { it.first }

/**
 * Top Rated for a library (#92). Sorting by the audience score alone put titles rated 10.0 by a few
 * people first, and the server has no vote counts to filter them, so the best titles by audience and by
 * critics are fetched and ranked together here.
 */
suspend fun loadTopRated(api: ApiClient, parentId: UUID, type: BaseItemKind, limit: Int, fields: Collection<ItemFields>? = null): List<BaseItemDto> =
	withContext(Dispatchers.IO) {
		fun request(sort: ItemSortBy) = GetItemsRequest(
			fields = fields,
			parentId = parentId,
			includeItemTypes = setOf(type),
			recursive = true,
			sortBy = setOf(sort),
			sortOrder = setOf(SortOrder.DESCENDING),
			imageTypeLimit = 1,
			limit = POOL_SIZE,
			enableTotalRecordCount = false,
		)
		coroutineScope {
			val byAudience = async { api.itemsApi.getItems(request(ItemSortBy.COMMUNITY_RATING)).content.items }
			val byCritics = async { api.itemsApi.getItems(request(ItemSortBy.CRITIC_RATING)).content.items }
			rankTopRated(byAudience.await() + byCritics.await(), limit)
		}
	}

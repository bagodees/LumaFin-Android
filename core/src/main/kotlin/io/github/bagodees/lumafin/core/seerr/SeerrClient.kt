package io.github.bagodees.lumafin.core.seerr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.serializer.toUUID
import timber.log.Timber
import java.time.LocalDate
import java.util.UUID

/** A title from Seerr that may not be in the library yet. [mediaId] is Seerr's (TMDB) id. */
data class SeerrItem(
	val name: String,
	val mediaType: String,
	val mediaId: Int,
	/** Landscape artwork; replaced with the titled English backdrop when SeerrFin has one. */
	val imageUrl: String?,
	val posterUrl: String? = null,
	val year: Int?,
	val rating: Float? = null,
	val tmdbId: Int? = null,
	/** My Requests: where the request stands, as words for a card (each app words [requestStatus] its own way). */
	val statusLabel: String? = null,
	/** My Requests: the library item once the title has arrived. */
	val jellyfinItemId: UUID? = null,
	/** My Requests: where the request stands. */
	val requestStatus: SeerrRequestStatus? = null,
) {
	/** A stand-in library item with the title, year, rating and kind, for previews and info rows. */
	fun previewItem(overview: String? = null) = BaseItemDto(
		id = UUID.nameUUIDFromBytes("seerr|$mediaType|$mediaId".toByteArray()),
		name = name,
		type = kind,
		productionYear = year,
		communityRating = rating,
		overview = overview,
	)

	/** The library item type this title would be. */
	val kind get() = if (mediaType == "tv") BaseItemKind.SERIES else BaseItemKind.MOVIE
}

/** Where one of the user's requests stands, in plain terms rather than Seerr's and the download apps' own labels. */
sealed interface SeerrRequestStatus {
	data class Downloading(val percent: Int) : SeerrRequestStatus
	data object Waiting : SeerrRequestStatus
	data object Unreleased : SeerrRequestStatus
	data object Failed : SeerrRequestStatus
	data object InLibrary : SeerrRequestStatus
	data object Arriving : SeerrRequestStatus
	data object PartlyInLibrary : SeerrRequestStatus
	data object AwaitingApproval : SeerrRequestStatus
	data object Declined : SeerrRequestStatus
	data object Requested : SeerrRequestStatus
}

/**
 * Seerr through the SeerrFin plugin on the Jellyfin server: discover rows and requests use the
 * signed-in Jellyfin session, so there's no Seerr address or login in LumaFin. Calls return null
 * (or false) when the server doesn't have the plugin.
 */
object SeerrClient {
	private val json = Json { ignoreUnknownKeys = true; isLenient = true }

	/** Discover rows LumaFin shows, in order: a key for the row title and the SeerrFin path. */
	val discoverRows = listOf(
		"trending_movies" to "discover/movies/trending",
		"trending_shows" to "discover/tv/trending",
		"upcoming_movies" to "discover/movies/upcoming",
		"upcoming_shows" to "discover/tv/upcoming",
	)

	private suspend fun get(api: ApiClient, path: String, query: Map<String, Any?> = emptyMap()) = withContext(Dispatchers.IO) {
		api.request(HttpMethod.GET, "/SeerrFin/$path", emptyMap(), query, null)
	}

	private fun ApiClient.absolute(url: String?) =
		url?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("/")) baseUrl?.trimEnd('/') + it else it }

	suspend fun discover(api: ApiClient, path: String): List<SeerrItem>? = items(api, path, mapOf("limit" to 20))

	/** Titles Seerr finds for [query], for adding to search results; empty without SeerrFin. */
	suspend fun search(api: ApiClient, query: String): List<SeerrItem> = items(api, "search", mapOf("query" to query)).orEmpty()

	private suspend fun items(api: ApiClient, path: String, query: Map<String, Any?>): List<SeerrItem>? = try {
		val response = get(api, path, query)
		if (response.status !in 200..299) null
		else json.decodeFromString(BaseItemDtoQueryResult.serializer(), response.body.decodeToString()).items.mapNotNull { item ->
			val ids = item.providerIds.orEmpty()
			val id = ids["Jellyseerr"]?.toIntOrNull() ?: return@mapNotNull null
			val image = ids["JellyseerrBackdrop"]?.takeIf { it.isNotBlank() } ?: ids["JellyseerrPoster"]?.takeIf { it.isNotBlank() }
			SeerrItem(
				name = item.name.orEmpty(),
				mediaType = item.sourceType ?: if (path.contains("/tv/")) "tv" else "movie",
				mediaId = id,
				// Cached images are served by the Jellyfin server
				imageUrl = api.absolute(image),
				posterUrl = api.absolute(ids["JellyseerrPoster"]),
				year = item.premiereDate?.year?.takeIf { it > 1970 },
				rating = item.communityRating,
				tmdbId = ids["Tmdb"]?.toIntOrNull(),
			)
		// Seerr's pages can repeat a title
		}.distinctBy { it.mediaType to it.mediaId }
	} catch (error: CancellationException) {
		throw error
	} catch (error: Exception) {
		Timber.d(error, "SeerrFin not available")
		null
	}

	/** The English backdrop with the title on it; null without one. */
	suspend fun titledBackdrop(api: ApiClient, item: SeerrItem): String? = try {
		val tmdbId = item.tmdbId
		if (tmdbId == null) null else {
			val response = get(api, "backdrop/${item.mediaType}/$tmdbId")
			if (response.status !in 200..299) null
			else json.parseToJsonElement(response.body.decodeToString()).jsonObject.let { body ->
				if (body["hasEnglishBackdrop"]?.jsonPrimitive?.content != "true") null
				else api.absolute(body["backdropUrl"]?.jsonPrimitive?.content)
			}
		}
	} catch (error: CancellationException) { throw error } catch (error: Exception) { null }

	/** Seerr's details for a title as raw JSON, for callers that read single fields. */
	suspend fun detailsJson(api: ApiClient, mediaType: String, mediaId: Int): JsonObject? = try {
		val response = get(api, "details/$mediaType/$mediaId")
		if (response.status !in 200..299) null else json.parseToJsonElement(response.body.decodeToString()).jsonObject
	} catch (error: CancellationException) { throw error } catch (error: Exception) { null }

	/** Seerr's status for a title: 5 available, 4 partly available, 2 or 3 requested, otherwise not requested. */
	suspend fun status(api: ApiClient, item: SeerrItem): Int? =
		(detailsJson(api, item.mediaType, item.mediaId)?.get("mediaInfo") as? JsonObject)?.get("status")?.jsonPrimitive?.intOrNull

	/** The signed-in user's requests, newest first, with where each one stands. Null when SeerrFin can't answer. */
	suspend fun myRequests(api: ApiClient): List<SeerrItem>? = try {
		val response = get(api, "requests", mapOf("take" to 20))
		if (response.status !in 200..299) null
		else parseRequests(json.parseToJsonElement(response.body.decodeToString()).jsonObject)
	} catch (error: CancellationException) {
		throw error
	} catch (error: Exception) {
		Timber.d(error, "SeerrFin requests not available")
		null
	}

	/** Aired seasons of a show that Seerr can still get; see [missingSeasons]. */
	suspend fun missingSeasons(api: ApiClient, tmdbId: Int): List<Int>? =
		detailsJson(api, "tv", tmdbId)?.let { missingSeasons(it, LocalDate.now()) }

	/** Requests a title; a show is requested with all seasons unless [seasons] names some. True when Seerr accepted it. */
	suspend fun request(api: ApiClient, item: SeerrItem, seasons: List<Int> = emptyList()): Boolean = try {
		val body = buildJsonObject {
			put("MediaType", item.mediaType); put("MediaId", item.mediaId)
			if (seasons.isNotEmpty()) put("Seasons", JsonArray(seasons.map { JsonPrimitive(it) }))
		}
		withContext(Dispatchers.IO) {
			api.request(HttpMethod.POST, "/SeerrFin/request", emptyMap(), emptyMap(), body)
		}.status in 200..299
	} catch (error: CancellationException) {
		throw error
	} catch (error: Exception) {
		Timber.w(error, "Seerr request failed")
		false
	}
}

/** The requests in SeerrFin's /requests answer. */
fun parseRequests(body: JsonObject): List<SeerrItem> = (body["requests"] as? JsonArray).orEmpty()
	.mapNotNull { element ->
		val request = element as? JsonObject ?: return@mapNotNull null
		fun text(key: String) = request[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
		val tmdbId = request["tmdbId"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
		val progress = request["servarrProgress"] as? JsonObject
		val percent = progress?.get("percent")?.jsonPrimitive?.intOrNull ?: 0
		val inLibrary = text("jellyfinItemId") != null
		val status = when (progress?.get("statusKey")?.jsonPrimitive?.contentOrNull) {
			"queued" -> if (percent in 1..99) SeerrRequestStatus.Downloading(percent) else SeerrRequestStatus.Waiting
			"unreleased" -> SeerrRequestStatus.Unreleased
			"failed" -> SeerrRequestStatus.Failed
			"downloaded-monitored", "downloaded-unmonitored" -> if (inLibrary) SeerrRequestStatus.InLibrary else SeerrRequestStatus.Arriving
			else -> when (text("mediaStatusLabel")) {
				"Available" -> SeerrRequestStatus.InLibrary
				"Partially Available" -> SeerrRequestStatus.PartlyInLibrary
				"Pending Approval" -> SeerrRequestStatus.AwaitingApproval
				"Declined" -> SeerrRequestStatus.Declined
				"Failed" -> SeerrRequestStatus.Failed
				// Gone from Seerr: shown without a status
				"Deleted", "Blocklisted" -> null
				else -> SeerrRequestStatus.Requested
			}
		}
		SeerrItem(
			name = text("title") ?: return@mapNotNull null,
			mediaType = text("type") ?: "movie",
			mediaId = tmdbId,
			imageUrl = text("backdropUrl") ?: text("posterUrl"),
			posterUrl = text("posterUrl"),
			year = request["year"]?.jsonPrimitive?.intOrNull,
			tmdbId = tmdbId,
			jellyfinItemId = text("jellyfinItemId")?.let { runCatching { it.toUUID() }.getOrNull() },
			requestStatus = status,
		)
	}.distinctBy { it.mediaType to it.mediaId }

/**
 * Aired seasons of a show that Seerr can still get. Only for shows Seerr knows as requested, being
 * downloaded or partly here, so a show Seerr hasn't matched never offers seasons that are already in
 * the library; a show requested for only some seasons can get the rest.
 */
fun missingSeasons(details: JsonObject, today: LocalDate): List<Int> {
	val mediaInfo = details["mediaInfo"] as? JsonObject
	if (mediaInfo?.get("status")?.jsonPrimitive?.intOrNull !in 2..4) return emptyList()
	fun seasonNumbers(seasons: JsonElement?, keep: (Int?) -> Boolean) =
		(seasons as? JsonArray).orEmpty().mapNotNull { season ->
			val obj = season as? JsonObject ?: return@mapNotNull null
			obj["seasonNumber"]?.jsonPrimitive?.intOrNull?.takeIf { keep(obj["status"]?.jsonPrimitive?.intOrNull) }
		}
	// Seasons Seerr has requested (2), is getting (3) or has in part or in full (4, 5)
	val known = seasonNumbers(mediaInfo!!["seasons"]) { it in 2..5 }.toSet() +
		// and seasons in requests that weren't declined (3) or failed (4)
		(mediaInfo["requests"] as? JsonArray).orEmpty().flatMap { request ->
			val obj = request as? JsonObject ?: return@flatMap emptyList()
			if (obj["status"]?.jsonPrimitive?.intOrNull in setOf(3, 4)) emptyList() else seasonNumbers(obj["seasons"]) { true }
		}
	val day = today.toString()
	return (details["seasons"] as? JsonArray).orEmpty().mapNotNull { season ->
		val obj = season as? JsonObject ?: return@mapNotNull null
		val number = obj["seasonNumber"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
		val aired = obj["airDate"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length >= 10 }?.let { it.take(10) <= day } == true
		number.takeIf { aired && it !in known }
	}.sorted()
}

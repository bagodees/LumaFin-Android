package io.github.bagodees.lumafin.core.seerr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaUrl
import org.jellyfin.sdk.model.serializer.toUUID
import timber.log.Timber
import java.time.LocalDate
import java.util.UUID

data class SeerrPerson(val name: String, val role: String?, val imageUrl: String?)

data class SeerrSeason(val number: Int, val episodes: Int?)

/** Everything the Seerr title page shows (#100), read from Seerr through the SeerrFin plugin. */
data class SeerrDetails(
	val item: SeerrItem,
	val overview: String?,
	val genres: List<String>,
	val credits: String?,
	val runtimeMinutes: Int?,
	val releaseDate: LocalDate?,
	val certification: String?,
	val seriesStatus: String?,
	val criticRating: Float?,
	/** Seerr's status: 5 in the library, 4 partly, 2 or 3 requested, otherwise not requested. */
	val status: Int?,
	val jellyfinItemId: UUID?,
	val trailerUrl: String?,
	val cast: List<SeerrPerson>,
	/** Aired seasons Seerr can still get; empty for movies. */
	val requestableSeasons: List<SeerrSeason>,
	val backdropUrl: String?,
) {
	val requested get() = status == 2 || status == 3
	val inLibrary get() = status == 5

	/** A stand-in library item, so the page can reuse the info row and title logo of the other details pages. */
	fun previewItem() = item.previewItem(overview).copy(
		criticRating = criticRating,
		premiereDate = releaseDate?.atStartOfDay(),
		runTimeTicks = runtimeMinutes?.let { it * 60L * 10_000_000L },
		officialRating = certification,
		status = seriesStatus,
		genres = genres,
		remoteTrailers = trailerUrl?.let { listOf(MediaUrl(url = it)) },
	)
}

private val json = Json { ignoreUnknownKeys = true; isLenient = true }
private const val TMDB_IMAGES = "https://image.tmdb.org/t/p"

private suspend fun ApiClient.seerrGet(path: String) = withContext(Dispatchers.IO) {
	request(HttpMethod.GET, "/SeerrFin/$path", emptyMap(), emptyMap(), null)
}

private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
private fun JsonObject.array(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

/** Seerr's page for a movie ("movie") or show ("tv"); null when SeerrFin can't answer. */
suspend fun loadSeerrDetails(api: ApiClient, mediaType: String, mediaId: Int): SeerrDetails? = try {
	val response = api.seerrGet("details/$mediaType/$mediaId")
	if (response.status !in 200..299) null
	else parseSeerrDetails(json.parseToJsonElement(response.body.decodeToString()).jsonObject, mediaType, mediaId, LocalDate.now())
		?.let { details ->
			// Seerr's own score has no critics; its Rotten Tomatoes lookup does
			val critic = runCatching {
				val ratings = api.seerrGet("jellyseerr/$mediaType/$mediaId/ratings")
				if (ratings.status !in 200..299) null
				else json.parseToJsonElement(ratings.body.decodeToString()).jsonObject["criticsScore"]?.jsonPrimitive?.floatOrNull
			}.getOrNull()
			if (critic != null && critic > 0) details.copy(criticRating = critic) else details
		}
} catch (error: CancellationException) {
	throw error
} catch (error: Exception) {
	Timber.w(error, "Seerr details didn't load for %s/%d", mediaType, mediaId)
	null
}

fun parseSeerrDetails(body: JsonObject, mediaType: String, mediaId: Int, today: LocalDate): SeerrDetails? {
	val name = body.text("title") ?: body.text("name") ?: return null
	val tv = mediaType == "tv"
	val release = (body.text("releaseDate") ?: body.text("firstAirDate"))?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
	val credits = body["credits"] as? JsonObject
	val people = if (tv) body.array("createdBy").mapNotNull { it.text("name") }
		else credits?.array("crew").orEmpty().filter { it.text("job") == "Director" }.mapNotNull { it.text("name") }
	// US rating first, like the library's
	val certification = if (tv) body["contentRatings"]?.let { it as? JsonObject }?.array("results").orEmpty()
			.sortedByDescending { it.text("iso_3166_1") == "US" }.firstNotNullOfOrNull { it.text("rating") }
		else body["releases"]?.let { it as? JsonObject }?.array("results").orEmpty()
			.sortedByDescending { it.text("iso_3166_1") == "US" }
			.flatMap { it.array("release_dates") }.firstNotNullOfOrNull { it.text("certification") }
	val mediaInfo = body["mediaInfo"] as? JsonObject
	val trailer = body.array("relatedVideos").firstOrNull { it.text("type") == "Trailer" && it.text("site") == "YouTube" }
		?.let { it.text("url") ?: it.text("key")?.let { key -> "https://www.youtube.com/watch?v=$key" } }
	val seasons = if (!tv) emptyList() else {
		val requestable = if (mediaInfo == null) body.array("seasons").mapNotNull { season ->
			// Seerr doesn't know the show yet: every aired season can be requested
			val number = season["seasonNumber"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
			number.takeIf { season.text("airDate")?.take(10)?.let { it <= today.toString() } == true }
		} else missingSeasons(body, today)
		body.array("seasons").mapNotNull { season ->
			val number = season["seasonNumber"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
			SeerrSeason(number, season["episodeCount"]?.jsonPrimitive?.intOrNull).takeIf { number in requestable }
		}
	}
	val backdrop = body.text("backdropPath")
	return SeerrDetails(
		item = SeerrItem(
			name = name,
			mediaType = mediaType,
			mediaId = mediaId,
			imageUrl = backdrop?.let { "$TMDB_IMAGES/w780$it" },
			posterUrl = body.text("posterPath")?.let { "$TMDB_IMAGES/w342$it" },
			year = release?.year,
			rating = body["voteAverage"]?.jsonPrimitive?.floatOrNull?.takeIf { it > 0 },
			tmdbId = mediaId,
		),
		overview = body.text("overview"),
		genres = body.array("genres").mapNotNull { it.text("name") },
		credits = people.take(2).joinToString(", ").ifEmpty { null },
		runtimeMinutes = body["runtime"]?.jsonPrimitive?.intOrNull
			?: (body["episodeRunTime"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.intOrNull,
		releaseDate = release,
		certification = certification,
		seriesStatus = if (!tv) null else when (body.text("status")) {
			"Ended", "Canceled" -> "Ended"
			null -> null
			else -> "Continuing"
		},
		criticRating = null,
		status = mediaInfo?.get("status")?.jsonPrimitive?.intOrNull,
		jellyfinItemId = body.text("jellyfinItemId")?.let { runCatching { it.toUUID() }.getOrNull() },
		trailerUrl = trailer,
		cast = credits?.array("cast").orEmpty().take(20).mapNotNull { person ->
			SeerrPerson(
				name = person.text("name") ?: return@mapNotNull null,
				role = person.text("character"),
				imageUrl = person.text("profilePath")?.let { "$TMDB_IMAGES/w185$it" },
			)
		},
		requestableSeasons = seasons,
		backdropUrl = backdrop?.let { "$TMDB_IMAGES/w1280$it" },
	)
}

/** Seerr's recommendations for a title, as cards for a row; similar titles when it has none. */
suspend fun loadSeerrRecommendations(api: ApiClient, mediaType: String, mediaId: Int): List<SeerrItem> {
	suspend fun load(kind: String): List<SeerrItem> = try {
		val response = api.seerrGet("jellyseerr/$mediaType/$mediaId/$kind")
		if (response.status !in 200..299) emptyList()
		else json.parseToJsonElement(response.body.decodeToString()).jsonObject.array("results").mapNotNull { result ->
			val id = result["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
			val type = result.text("mediaType") ?: mediaType
			if (type != "movie" && type != "tv") return@mapNotNull null
			SeerrItem(
				name = result.text("title") ?: result.text("name") ?: return@mapNotNull null,
				mediaType = type,
				mediaId = id,
				imageUrl = (result.text("backdropPath") ?: result.text("posterPath"))?.let { "$TMDB_IMAGES/w780$it" },
				posterUrl = result.text("posterPath")?.let { "$TMDB_IMAGES/w342$it" },
				year = (result.text("releaseDate") ?: result.text("firstAirDate"))?.take(4)?.toIntOrNull(),
				rating = result["voteAverage"]?.jsonPrimitive?.floatOrNull?.takeIf { it > 0 },
				tmdbId = id,
			)
		}
	} catch (error: CancellationException) {
		throw error
	} catch (error: Exception) {
		Timber.d(error, "No Seerr %s for %s/%d", kind, mediaType, mediaId)
		emptyList()
	}
	return load("recommendations").ifEmpty { load("similar") }.take(20)
}

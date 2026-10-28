package io.github.bagodees.lumafin.core.seerr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate

class SeerrDetailsParseTests : FunSpec({
	val today = LocalDate.of(2026, 10, 28)
	fun parse(type: String, text: String) = parseSeerrDetails(Json.parseToJsonElement(text).jsonObject, type, 42, today)!!

	test("a movie reads its rating, director, runtime, trailer and cast") {
		val movie = parse("movie", """
			{"title":"Ghost Soldier","overview":"Fitz returns.","runtime":90,"releaseDate":"2026-11-04","voteAverage":7.3,
			 "genres":[{"name":"Action"},{"name":"Thriller"}],
			 "releases":{"results":[{"iso_3166_1":"GB","release_dates":[{"certification":"15"}]},{"iso_3166_1":"US","release_dates":[{"certification":""},{"certification":"PG-13"}]}]},
			 "credits":{"cast":[{"name":"Jim Caviezel","character":"Fitz","profilePath":"/a.jpg"},{"name":"Olivia Thirlby"}],"crew":[{"job":"Director","name":"William Eubank"},{"job":"Writer","name":"Someone"}]},
			 "relatedVideos":[{"type":"Teaser","site":"YouTube","key":"t"},{"type":"Trailer","site":"YouTube","key":"abc","url":"https://www.youtube.com/watch?v=abc"}],
			 "mediaInfo":{"status":3},"backdropPath":"/b.jpg"}
		""")
		movie.item.name shouldBe "Ghost Soldier"
		movie.certification shouldBe "PG-13"
		movie.credits shouldBe "William Eubank"
		movie.runtimeMinutes shouldBe 90
		movie.genres shouldBe listOf("Action", "Thriller")
		movie.trailerUrl shouldBe "https://www.youtube.com/watch?v=abc"
		movie.cast.map { it.name } shouldBe listOf("Jim Caviezel", "Olivia Thirlby")
		movie.cast.first().imageUrl shouldBe "https://image.tmdb.org/t/p/w185/a.jpg"
		movie.requested shouldBe true
		movie.requestableSeasons shouldBe emptyList()
		movie.backdropUrl shouldBe "https://image.tmdb.org/t/p/w1280/b.jpg"
	}
	test("a show Seerr doesn't know yet offers every aired season") {
		val show = parse("tv", """
			{"name":"Last Chance U","firstAirDate":"2016-07-29","episodeRunTime":[52],"status":"Ended",
			 "createdBy":[{"name":"Greg Whiteley"}],
			 "contentRatings":{"results":[{"iso_3166_1":"US","rating":"TV-MA"}]},
			 "seasons":[{"seasonNumber":0,"airDate":"2016-01-01"},{"seasonNumber":1,"airDate":"2016-07-29","episodeCount":6},
			            {"seasonNumber":2,"airDate":"2017-07-21","episodeCount":8},{"seasonNumber":3,"airDate":"2027-01-01"}]}
		""")
		show.credits shouldBe "Greg Whiteley"
		show.runtimeMinutes shouldBe 52
		show.seriesStatus shouldBe "Ended"
		show.certification shouldBe "TV-MA"
		show.status shouldBe null
		show.requestableSeasons shouldBe listOf(SeerrSeason(1, 6), SeerrSeason(2, 8))
	}
	test("a show partly in the library only offers the seasons it's missing") {
		val show = parse("tv", """
			{"name":"The Rookie","status":"Returning Series","mediaInfo":{"status":4,"seasons":[{"seasonNumber":1,"status":5}]},
			 "seasons":[{"seasonNumber":1,"airDate":"2018-10-16","episodeCount":20},{"seasonNumber":2,"airDate":"2019-09-29","episodeCount":20}]}
		""")
		show.seriesStatus shouldBe "Continuing"
		show.requestableSeasons shouldBe listOf(SeerrSeason(2, 20))
	}
})

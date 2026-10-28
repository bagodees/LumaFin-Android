package io.github.bagodees.lumafin.core.toprated

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

class TopRatedTests : FunSpec({
	fun movie(name: String, community: Float?, critic: Float?) =
		BaseItemDto(id = UUID.randomUUID(), name = name, type = BaseItemKind.MOVIE, communityRating = community, criticRating = critic)

	test("a 10.0 from a handful of votes doesn't outrank well-reviewed classics") {
		val ranked = rankTopRated(listOf(
			movie("Adopted 2", 10f, null),
			movie("Family", 10f, 64f),
			movie("The Dark Knight", 8.5f, 94f),
			movie("Spirited Away", 8.5f, 96f),
		), 10).map { it.name }
		ranked shouldBe listOf("Spirited Away", "The Dark Knight", "Family", "Adopted 2")
	}
	test("titles with no rating are left out and each title appears once") {
		val shared = movie("Heat", 8.3f, 88f)
		rankTopRated(listOf(shared, shared, movie("Unrated", null, null)), 10).map { it.name } shouldBe listOf("Heat")
	}
	test("one strong score alone counts a little lower than two good ones") {
		(topRatedScore(8f, 80f)!! > topRatedScore(null, 85f)!!) shouldBe true
		(topRatedScore(8f, 80f)!! > topRatedScore(8.5f, null)!!) shouldBe true
	}
	test("the limit keeps only the best") {
		rankTopRated((1..5).map { movie("m$it", it.toFloat(), null) }, 2).map { it.name } shouldBe listOf("m5", "m4")
	}
})

package io.github.bagodees.lumafin.mobile.data

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import java.util.UUID

private fun ApiClient.image(id: UUID, type: ImageType, tag: String, maxWidth: Int) =
	imageApi.getItemImageUrl(itemId = id, imageType = type, tag = tag, maxWidth = maxWidth, quality = 90)

/** Wide artwork for a card: the title's thumbnail, an episode still, or a backdrop (its own or its show's). */
fun ApiClient.landscapeImage(item: BaseItemDto, maxWidth: Int = 720): String? {
	item.imageTags?.get(ImageType.THUMB)?.let { return image(item.id, ImageType.THUMB, it, maxWidth) }
	if (item.type == BaseItemKind.EPISODE) item.imageTags?.get(ImageType.PRIMARY)?.let { return image(item.id, ImageType.PRIMARY, it, maxWidth) }
	item.backdropImageTags?.firstOrNull()?.let { return image(item.id, ImageType.BACKDROP, it, maxWidth) }
	item.parentThumbItemId?.let { id -> item.parentThumbImageTag?.let { return image(id, ImageType.THUMB, it, maxWidth) } }
	item.parentBackdropItemId?.let { id -> item.parentBackdropImageTags?.firstOrNull()?.let { return image(id, ImageType.BACKDROP, it, maxWidth) } }
	return null
}

/** Full-size backdrop for the large-screen preview at the top of Home. */
fun ApiClient.backdropImage(item: BaseItemDto, maxWidth: Int = 1920): String? {
	item.backdropImageTags?.firstOrNull()?.let { return image(item.id, ImageType.BACKDROP, it, maxWidth) }
	item.parentBackdropItemId?.let { id -> item.parentBackdropImageTags?.firstOrNull()?.let { return image(id, ImageType.BACKDROP, it, maxWidth) } }
	return landscapeImage(item, maxWidth)
}

/** Portrait poster; episodes use their show's poster. */
fun ApiClient.posterImage(item: BaseItemDto, maxWidth: Int = 400): String? {
	if (item.type == BaseItemKind.EPISODE) {
		val seriesId = item.seriesId
		val tag = item.seriesPrimaryImageTag
		if (seriesId != null && tag != null) return image(seriesId, ImageType.PRIMARY, tag, maxWidth)
	}
	return item.imageTags?.get(ImageType.PRIMARY)?.let { image(item.id, ImageType.PRIMARY, it, maxWidth) }
}

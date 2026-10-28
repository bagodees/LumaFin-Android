package io.github.bagodees.lumafin.mobile.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import io.github.bagodees.lumafin.mobile.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.subtitleApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/** How a title is saved: the server's original file, or a smaller copy the server converts while it downloads. */
enum class DownloadQuality(val label: Int, val maxHeight: Int?, val videoBitrate: Int?) {
	ORIGINAL(R.string.download_quality_original, null, null),
	HIGH(R.string.download_quality_high, 1080, 8_000_000),
	MEDIUM(R.string.download_quality_medium, 720, 4_000_000),
	LOW(R.string.download_quality_low, 480, 1_500_000);

	/** Roughly how big a converted copy of [runTimeTicks] is; null for the original, whose size the server knows. */
	fun estimateBytes(runTimeTicks: Long?): Long? {
		val bitrate = videoBitrate ?: return null
		val seconds = (runTimeTicks ?: return null) / 10_000_000
		return (bitrate + AUDIO_BITRATE).toLong() * seconds / 8
	}

	companion object {
		const val AUDIO_BITRATE = 192_000
	}
}

enum class DownloadStatus { QUEUED, WAITING_FOR_WIFI, DOWNLOADING, DONE, FAILED }

data class DownloadedSubtitle(val path: String, val language: String?, val label: String)

/** One movie or episode saved on this device, with what's needed to show and play it offline. */
data class Download(
	val itemId: UUID,
	val userId: UUID,
	val isEpisode: Boolean,
	val name: String,
	val seriesId: UUID?,
	val seriesName: String?,
	val seasonNumber: Int?,
	val episodeNumber: Int?,
	val year: Int?,
	val overview: String?,
	val runTimeTicks: Long?,
	val quality: DownloadQuality,
	val file: String,
	val image: String?,
	val subtitles: List<DownloadedSubtitle>,
	val downloadId: Long,
	val status: DownloadStatus,
	val bytes: Long,
	val totalBytes: Long,
	val positionTicks: Long,
	val played: Boolean,
	/** Watched while the server couldn't be reached; sent the next time it can. */
	val unsynced: Boolean,
	val added: Long,
) {
	val title get() = if (isEpisode) listOfNotNull(seriesName, seasonNumber?.let { s -> episodeNumber?.let { "S$s E$it" } }, name).joinToString(" · ") else name
}

/**
 * Downloads for offline viewing. The system's download manager does the transfer (it carries on in
 * the background, waits for Wi-Fi when asked and shows its own progress notification); this keeps
 * the list of saved titles, their artwork and subtitles, and the resume point for each.
 * Files live in the app's own storage, so they need no storage permission and go away with the app.
 */
class DownloadRepository(private val context: Context, private val sessions: SessionRepository, private val settings: AppSettings, private val scope: CoroutineScope) {
	private val manager = context.getSystemService(DownloadManager::class.java)
	private val index = File(context.filesDir, "downloads.json")
	private val _downloads = MutableStateFlow(load())
	val downloads: StateFlow<List<Download>> = _downloads.asStateFlow()
	private var watcher: Job? = null

	init {
		watch()
	}

	fun find(itemId: UUID): Download? = _downloads.value.firstOrNull { it.itemId == itemId }

	/** Whether this account may download; servers turn it on per user. */
	suspend fun allowed(api: ApiClient): Boolean = withContext(Dispatchers.IO) {
		runCatching { api.userApi.getCurrentUser().content.policy?.enableContentDownloading != false }.getOrDefault(true)
	}

	/** Starts downloading a movie or episode. Returns false when it couldn't start. */
	suspend fun start(api: ApiClient, itemId: UUID, quality: DownloadQuality): Boolean = withContext(Dispatchers.IO) {
		val session = sessions.session.value ?: return@withContext false
		if (find(itemId) != null) return@withContext true
		try {
			val item = api.userLibraryApi.getItem(itemId).content
			val source = item.mediaSources?.firstOrNull() ?: error("No media source")
			val folder = File(context.getExternalFilesDir(DIRECTORY) ?: error("No storage"), item.id.toString()).apply { mkdirs() }
			val url: String
			val fileName: String
			if (quality == DownloadQuality.ORIGINAL) {
				url = api.libraryApi.getDownloadUrl(item.id)
				fileName = "video." + (source.path?.substringAfterLast('.', "")?.takeIf { it.length in 2..4 } ?: source.container?.substringBefore(',') ?: "mkv")
			} else {
				// A converted copy in MPEG-TS, which can be seeked without an index and plays everywhere
				url = api.videosApi.getVideoStreamByContainerUrl(
					itemId = item.id,
					container = "ts",
					static = false,
					mediaSourceId = source.id,
					deviceId = api.deviceInfo.id,
					playSessionId = UUID.randomUUID().toString(),
					videoCodec = "h264",
					audioCodec = "aac",
					enableAutoStreamCopy = false,
					allowVideoStreamCopy = false,
					allowAudioStreamCopy = false,
					videoBitRate = quality.videoBitrate,
					audioBitRate = DownloadQuality.AUDIO_BITRATE,
					maxAudioChannels = 2,
					maxHeight = quality.maxHeight,
					audioStreamIndex = source.defaultAudioStreamIndex,
				)
				fileName = "video.ts"
			}

			// Text subtitles come along as files: for the original only those kept beside the video
			// (the rest are inside it), for a converted copy all of them, since converting drops them
			val subtitles = source.mediaStreams.orEmpty()
				.filter { it.type == MediaStreamType.SUBTITLE && it.isTextSubtitleStream && (quality != DownloadQuality.ORIGINAL || it.isExternal) }
				.mapNotNull { stream ->
					val file = File(folder, "subtitle-${stream.index}.srt")
					val subtitleUrl = api.subtitleApi.getSubtitleUrl(routeItemId = item.id, routeMediaSourceId = source.id.orEmpty(), routeIndex = stream.index, routeFormat = "srt")
					if (fetch(api, subtitleUrl, file)) DownloadedSubtitle(file.path, stream.language, stream.displayTitle ?: stream.language ?: "Subtitles") else null
				}
			val imageUrl = if (item.type == BaseItemKind.EPISODE) api.landscapeImage(item, 720) else api.posterImage(item, 500)
			val image = imageUrl?.let { File(folder, "image.jpg").takeIf { file -> fetch(api, it, file) }?.path }

			val request = DownloadManager.Request(Uri.parse(url))
				.addRequestHeader("Authorization", authorization(api))
				.setTitle(listOfNotNull(item.seriesName, item.name).joinToString(" · "))
				.setDescription(context.getString(R.string.app_name))
				.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
				.setDestinationInExternalFilesDir(context, DIRECTORY, "${item.id}/$fileName")
				.setAllowedOverMetered(!settings.downloadWifiOnly)
				.setAllowedOverRoaming(false)
			val downloadId = manager.enqueue(request)
			val download = Download(
				itemId = item.id,
				userId = session.userId,
				isEpisode = item.type == BaseItemKind.EPISODE,
				name = item.name.orEmpty(),
				seriesId = item.seriesId,
				seriesName = item.seriesName,
				seasonNumber = item.parentIndexNumber,
				episodeNumber = item.indexNumber,
				year = item.productionYear,
				overview = item.overview,
				runTimeTicks = item.runTimeTicks,
				quality = quality,
				file = File(folder, fileName).path,
				image = image,
				subtitles = subtitles,
				downloadId = downloadId,
				status = DownloadStatus.QUEUED,
				bytes = 0,
				totalBytes = if (quality == DownloadQuality.ORIGINAL) source.size ?: -1 else -1,
				positionTicks = item.userData?.playbackPositionTicks ?: 0,
				played = item.userData?.played == true,
				unsynced = false,
				added = System.currentTimeMillis(),
			)
			update { it + download }
			watch()
			true
		} catch (error: Exception) {
			if (error is kotlinx.coroutines.CancellationException) throw error
			Timber.w(error, "Download didn't start")
			false
		}
	}

	/** Removes a download from the device; the title stays on the server. */
	fun delete(itemId: UUID) {
		val download = find(itemId) ?: return
		runCatching { manager.remove(download.downloadId) }
		File(download.file).parentFile?.deleteRecursively()
		update { list -> list.filter { it.itemId != itemId } }
	}

	/** Saves where you stopped in a download, and sends it to the server when it can be reached. */
	fun savePosition(api: ApiClient?, itemId: UUID, positionTicks: Long, played: Boolean) {
		update { list -> list.map { if (it.itemId == itemId) it.copy(positionTicks = if (played) 0 else positionTicks, played = played, unsynced = true) else it } }
		if (api != null) scope.launch { sync(api) }
	}

	/** Sends resume points saved while offline to the server. */
	suspend fun sync(api: ApiClient) = withContext(Dispatchers.IO) {
		val userId = sessions.session.value?.userId ?: return@withContext
		for (download in _downloads.value.filter { it.unsynced && it.userId == userId }) {
			runCatching {
				api.itemsApi.updateItemUserData(download.itemId, userId, UpdateUserItemDataDto(playbackPositionTicks = download.positionTicks, played = download.played))
			}.onSuccess {
				update { list -> list.map { if (it.itemId == download.itemId) it.copy(unsynced = false) else it } }
			}.onFailure { Timber.i("Resume point not sent yet: ${it.message}") }
		}
	}

	/** Follows running downloads until they finish. */
	private fun watch() {
		if (watcher?.isActive == true) return
		watcher = scope.launch(Dispatchers.IO) {
			while (isActive && _downloads.value.any { it.status != DownloadStatus.DONE && it.status != DownloadStatus.FAILED }) {
				refresh()
				delay(1500.milliseconds)
			}
		}
	}

	private fun refresh() {
		val running = _downloads.value.filter { it.status != DownloadStatus.DONE && it.status != DownloadStatus.FAILED }
		if (running.isEmpty()) return
		val states = mutableMapOf<Long, Triple<DownloadStatus, Long, Long>>()
		runCatching {
			manager.query(DownloadManager.Query().setFilterById(*running.map { it.downloadId }.toLongArray()))?.use { cursor ->
				val idColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
				val statusColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
				val reasonColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
				val bytesColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
				val totalColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
				while (cursor.moveToNext()) {
					val status = when (cursor.getInt(statusColumn)) {
						DownloadManager.STATUS_RUNNING -> DownloadStatus.DOWNLOADING
						DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.DONE
						DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
						DownloadManager.STATUS_PAUSED -> if (cursor.getInt(reasonColumn) == DownloadManager.PAUSED_QUEUED_FOR_WIFI) DownloadStatus.WAITING_FOR_WIFI else DownloadStatus.QUEUED
						else -> DownloadStatus.QUEUED
					}
					states[cursor.getLong(idColumn)] = Triple(status, cursor.getLong(bytesColumn), cursor.getLong(totalColumn))
				}
			}
		}.onFailure { Timber.w(it, "Couldn't read download progress") }
		update { list ->
			list.map { download ->
				if (download !in running) return@map download
				// Cleared from the system's download list (or never recorded): it can't finish any more
				val (status, bytes, total) = states[download.downloadId] ?: Triple(DownloadStatus.FAILED, download.bytes, download.totalBytes)
				val done = status == DownloadStatus.DONE
				download.copy(
					status = status,
					bytes = if (done) File(download.file).length() else bytes,
					totalBytes = when {
						done -> File(download.file).length()
						total > 0 -> total
						else -> download.totalBytes
					},
				)
			}
		}
	}

	private fun authorization(api: ApiClient) = AuthorizationHeaderBuilder.buildHeader(
		api.clientInfo.name, api.clientInfo.version, api.deviceInfo.id, api.deviceInfo.name, api.accessToken,
	)

	/** Small files (artwork, subtitles) are fetched directly. */
	private fun fetch(api: ApiClient, url: String, file: File): Boolean = runCatching {
		val connection = URL(url).openConnection() as HttpURLConnection
		connection.setRequestProperty("Authorization", authorization(api))
		connection.connectTimeout = 15_000
		connection.readTimeout = 30_000
		try {
			if (connection.responseCode !in 200..299) return@runCatching false
			connection.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
			true
		} finally {
			connection.disconnect()
		}
	}.onFailure { Timber.w(it, "Couldn't save $url") }.getOrDefault(false)

	@Synchronized
	private fun update(change: (List<Download>) -> List<Download>) {
		_downloads.value = change(_downloads.value)
		runCatching { index.writeText(JSONArray(_downloads.value.map(::toJson)).toString()) }.onFailure { Timber.w(it, "Couldn't save the download list") }
	}

	private fun load(): List<Download> = runCatching {
		if (!index.exists()) return@runCatching emptyList()
		val array = JSONArray(index.readText())
		(0 until array.length()).mapNotNull { fromJson(array.getJSONObject(it)) }
	}.onFailure { Timber.w(it, "Couldn't read the download list") }.getOrDefault(emptyList())

	private fun toJson(download: Download) = JSONObject().apply {
		put("itemId", download.itemId.toString())
		put("userId", download.userId.toString())
		put("isEpisode", download.isEpisode)
		put("name", download.name)
		put("seriesId", download.seriesId?.toString())
		put("seriesName", download.seriesName)
		put("seasonNumber", download.seasonNumber)
		put("episodeNumber", download.episodeNumber)
		put("year", download.year)
		put("overview", download.overview)
		put("runTimeTicks", download.runTimeTicks)
		put("quality", download.quality.name)
		put("file", download.file)
		put("image", download.image)
		put("subtitles", JSONArray(download.subtitles.map { JSONObject().put("path", it.path).put("language", it.language).put("label", it.label) }))
		put("downloadId", download.downloadId)
		put("status", download.status.name)
		put("bytes", download.bytes)
		put("totalBytes", download.totalBytes)
		put("positionTicks", download.positionTicks)
		put("played", download.played)
		put("unsynced", download.unsynced)
		put("added", download.added)
	}

	private fun fromJson(json: JSONObject): Download? {
		fun JSONObject.text(key: String) = if (isNull(key)) null else optString(key)
		fun JSONObject.number(key: String) = if (isNull(key) || !has(key)) null else optInt(key)
		val subtitles = json.optJSONArray("subtitles") ?: JSONArray()
		return Download(
			itemId = json.text("itemId")?.toUUIDOrNull() ?: return null,
			userId = json.text("userId")?.toUUIDOrNull() ?: return null,
			isEpisode = json.optBoolean("isEpisode"),
			name = json.optString("name"),
			seriesId = json.text("seriesId")?.toUUIDOrNull(),
			seriesName = json.text("seriesName"),
			seasonNumber = json.number("seasonNumber"),
			episodeNumber = json.number("episodeNumber"),
			year = json.number("year"),
			overview = json.text("overview"),
			runTimeTicks = if (json.isNull("runTimeTicks") || !json.has("runTimeTicks")) null else json.optLong("runTimeTicks"),
			quality = runCatching { DownloadQuality.valueOf(json.optString("quality")) }.getOrDefault(DownloadQuality.ORIGINAL),
			file = json.text("file") ?: return null,
			image = json.text("image"),
			subtitles = (0 until subtitles.length()).map { subtitles.getJSONObject(it) }.map { DownloadedSubtitle(it.optString("path"), it.text("language"), it.optString("label")) },
			downloadId = json.optLong("downloadId"),
			status = runCatching { DownloadStatus.valueOf(json.optString("status")) }.getOrDefault(DownloadStatus.FAILED),
			bytes = json.optLong("bytes"),
			totalBytes = json.optLong("totalBytes", -1),
			positionTicks = json.optLong("positionTicks"),
			played = json.optBoolean("played"),
			unsynced = json.optBoolean("unsynced"),
			added = json.optLong("added"),
		)
	}

	private companion object {
		const val DIRECTORY = "downloads"
	}
}

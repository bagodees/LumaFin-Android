package io.github.bagodees.lumafin.mobile.ui.downloads

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.bagodees.lumafin.mobile.LumaFinApplication
import io.github.bagodees.lumafin.mobile.R
import io.github.bagodees.lumafin.mobile.data.Download
import io.github.bagodees.lumafin.mobile.data.DownloadQuality
import io.github.bagodees.lumafin.mobile.data.DownloadStatus
import io.github.bagodees.lumafin.mobile.ui.common.currentMetrics
import io.github.bagodees.lumafin.mobile.ui.library.PageTitle
import io.github.bagodees.lumafin.mobile.ui.player.PlayerActivity
import io.github.bagodees.lumafin.mobile.ui.theme.LumaFinColors
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import java.io.File

/** Everything saved on this device; works without a connection. */
@Composable
fun DownloadsScreen(application: LumaFinApplication) {
	val all by application.downloads.downloads.collectAsState()
	val session by application.sessions.session.collectAsState()
	val metrics = currentMetrics()
	// Running downloads first, then shows (in episode order) and movies by name
	val downloads = all.filter { it.userId == session?.userId }.sortedWith(
		compareBy<Download> { if (it.status == DownloadStatus.DONE) 1 else 0 }
			.thenBy { (it.seriesName ?: it.name).lowercase() }
			.thenBy { it.seasonNumber ?: 0 }
			.thenBy { it.episodeNumber ?: 0 },
	)
	var deleting by remember { mutableStateOf<Download?>(null) }
	// Resume points from watching offline go to the server once it can be reached
	LaunchedEffect(Unit) { application.sessions.api?.let { application.downloads.sync(it) } }

	Column(Modifier.fillMaxSize().safeDrawingPadding()) {
		PageTitle(stringResource(R.string.nav_downloads), metrics.gutter)
		if (downloads.isEmpty()) Text(
			stringResource(R.string.downloads_empty),
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(horizontal = metrics.gutter),
		)
		else LazyVerticalGrid(
			columns = GridCells.Adaptive(360.dp),
			contentPadding = PaddingValues(start = metrics.gutter, end = metrics.gutter, bottom = 24.dp),
			horizontalArrangement = Arrangement.spacedBy(16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			items(downloads, key = { it.itemId }) { download -> DownloadRow(application, download, onDelete = { deleting = download }) }
		}
	}

	deleting?.let { download ->
		AlertDialog(
			onDismissRequest = { deleting = null },
			text = { Text(stringResource(R.string.download_delete_confirm, download.title)) },
			confirmButton = {
				TextButton(onClick = { application.downloads.delete(download.itemId); deleting = null }) { Text(stringResource(R.string.delete)) }
			},
			dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
		)
	}
}

@Composable
private fun DownloadRow(application: LumaFinApplication, download: Download, onDelete: () -> Unit) {
	val context = LocalContext.current
	// The app's scope: the row goes away while its download starts over
	val scope = application.appScope
	Row(
		Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
			when (download.status) {
				DownloadStatus.DONE -> context.startActivity(PlayerActivity.intent(context, download.itemId, offline = true))
				// A failed download starts over at the same quality
				DownloadStatus.FAILED -> application.sessions.api?.let { api ->
					val quality = download.quality
					application.downloads.delete(download.itemId)
					scope.launch {
						if (!application.downloads.start(api, download.itemId, quality)) Toast.makeText(context, R.string.download_failed_start, Toast.LENGTH_LONG).show()
					}
				}
				else -> Unit
			}
		}.padding(vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		AsyncImage(
			model = download.image?.let(::File),
			contentDescription = null,
			contentScale = ContentScale.Crop,
			modifier = Modifier.width(if (download.isEpisode) 132.dp else 64.dp)
				.aspectRatio(if (download.isEpisode) 16f / 9f else 2f / 3f)
				.clip(RoundedCornerShape(10.dp))
				.background(LumaFinColors.Raised),
		)
		Column(Modifier.weight(1f).padding(start = 14.dp)) {
			if (download.isEpisode) Text(download.seriesName.orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
			Text(
				if (download.isEpisode) listOfNotNull(download.seasonNumber?.let { s -> download.episodeNumber?.let { "S$s E$it" } }, download.name).joinToString(" · ") else download.name,
				style = MaterialTheme.typography.titleSmall,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				statusText(download),
				style = MaterialTheme.typography.bodySmall,
				color = if (download.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
			)
			if (download.status == DownloadStatus.DOWNLOADING) {
				if (download.totalBytes > 0) LinearProgressIndicator(progress = { download.bytes.toFloat() / download.totalBytes }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
				else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
			}
			// How far you got, for downloads you've started watching
			if (download.status == DownloadStatus.DONE && download.positionTicks > 0 && (download.runTimeTicks ?: 0) > 0) Box(
				Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(2.dp)).background(LumaFinColors.Raised),
			) {
				Box(Modifier.fillMaxWidth(download.positionTicks.toFloat() / download.runTimeTicks!!).padding(vertical = 1.5.dp).background(LumaFinColors.Cyan))
			}
		}
		IconButton(onClick = onDelete) {
			Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.download_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
}

@Composable
private fun statusText(download: Download): String {
	val context = LocalContext.current
	fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
	return when (download.status) {
		DownloadStatus.QUEUED -> stringResource(R.string.download_status_queued)
		DownloadStatus.WAITING_FOR_WIFI -> stringResource(R.string.download_status_wifi)
		DownloadStatus.DOWNLOADING ->
			if (download.totalBytes > 0) stringResource(R.string.download_status_progress, size(download.bytes), size(download.totalBytes))
			else stringResource(R.string.download_status_progress_unknown, size(download.bytes))
		DownloadStatus.FAILED -> stringResource(R.string.download_status_failed) + " · " + stringResource(R.string.download_retry)
		DownloadStatus.DONE -> listOf(stringResource(R.string.download_status_done, size(download.totalBytes)), stringResource(download.quality.label)).joinToString(" · ")
	}
}

/**
 * The Download button on a title page: picks a quality, then shows how the download is going.
 * Once downloaded, tapping it offers to delete the copy on this device.
 */
@Composable
fun DownloadButton(application: LumaFinApplication, item: BaseItemDto) {
	val all by application.downloads.downloads.collectAsState()
	val download = all.firstOrNull { it.itemId == item.id }
	var choosing by remember { mutableStateOf(false) }
	var deleting by remember { mutableStateOf(false) }

	OutlinedButton(onClick = { if (download == null) choosing = true else if (download.status == DownloadStatus.DONE || download.status == DownloadStatus.FAILED) deleting = true }) {
		Icon(painterResource(if (download?.status == DownloadStatus.DONE) R.drawable.ic_download_done else R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
		Text(
			when (download?.status) {
				null -> stringResource(R.string.download)
				DownloadStatus.DONE -> stringResource(R.string.download_downloaded)
				DownloadStatus.FAILED -> stringResource(R.string.download_status_failed)
				DownloadStatus.DOWNLOADING -> if (download.totalBytes > 0) "${stringResource(R.string.download_downloading)} ${download.bytes * 100 / download.totalBytes}%" else stringResource(R.string.download_downloading)
				DownloadStatus.WAITING_FOR_WIFI -> stringResource(R.string.download_status_wifi)
				DownloadStatus.QUEUED -> stringResource(R.string.download_status_queued)
			},
			modifier = Modifier.padding(start = 6.dp),
		)
	}

	if (choosing) QualityDialog(
		application,
		items = listOf(item),
		originalBytes = item.mediaSources?.firstOrNull()?.size,
		onDismiss = { choosing = false },
	)
	if (deleting && download != null) AlertDialog(
		onDismissRequest = { deleting = false },
		text = { Text(stringResource(R.string.download_delete_confirm, download.title)) },
		confirmButton = {
			TextButton(onClick = { application.downloads.delete(item.id); deleting = false }) { Text(stringResource(R.string.delete)) }
		},
		dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
	)
}

/** "Download season" under the season list: every episode in it not yet on the device. */
@Composable
fun DownloadSeasonButton(application: LumaFinApplication, episodes: List<BaseItemDto>, modifier: Modifier = Modifier) {
	val all by application.downloads.downloads.collectAsState()
	val missing = episodes.filter { episode -> all.none { it.itemId == episode.id } }
	var choosing by remember { mutableStateOf(false) }
	if (missing.isEmpty()) return
	TextButton(onClick = { choosing = true }, modifier = modifier) {
		Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
		Text(stringResource(R.string.download_season), modifier = Modifier.padding(start = 6.dp))
	}
	if (choosing) QualityDialog(application, items = missing, originalBytes = null, onDismiss = { choosing = false })
}

@Composable
private fun QualityDialog(application: LumaFinApplication, items: List<BaseItemDto>, originalBytes: Long?, onDismiss: () -> Unit) {
	val context = LocalContext.current
	// The app's scope, since the dialog closes before the downloads have started
	val scope = application.appScope
	var quality by remember { mutableStateOf(application.settings.downloadQuality) }
	val runTime = items.sumOf { it.runTimeTicks ?: 0 }.takeIf { it > 0 }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.download_choose_quality)) },
		text = {
			Column {
				if (items.size > 1) Text(pluralStringResource(R.plurals.episodes, items.size, items.size), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
				// A converted copy is only offered when it's smaller than the original
				DownloadQuality.entries.filter { option ->
					val estimate = option.estimateBytes(runTime)
					option == DownloadQuality.ORIGINAL || originalBytes == null || estimate == null || estimate < originalBytes * 0.8
				}.forEach { option ->
					Row(
						Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).selectable(selected = option == quality, onClick = { quality = option }).padding(vertical = 4.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						RadioButton(selected = option == quality, onClick = { quality = option })
						Column(Modifier.padding(start = 4.dp)) {
							Text(stringResource(option.label), style = MaterialTheme.typography.titleSmall)
							val bytes = if (option == DownloadQuality.ORIGINAL) originalBytes else option.estimateBytes(runTime)
							if (bytes != null && bytes > 0) Text(stringResource(R.string.download_size, Formatter.formatShortFileSize(context, bytes)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
						}
					}
				}
				Text(
					stringResource(if (quality == DownloadQuality.ORIGINAL) R.string.download_quality_original_detail else R.string.download_quality_converted_detail),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(top = 8.dp),
				)
			}
		},
		confirmButton = {
			TextButton(onClick = {
				onDismiss()
				val api = application.sessions.api ?: return@TextButton
				application.settings.downloadQuality = quality
				scope.launch {
					if (!application.downloads.allowed(api)) {
						Toast.makeText(context, R.string.download_not_allowed, Toast.LENGTH_LONG).show()
						return@launch
					}
					val started = items.count { application.downloads.start(api, it.id, quality) }
					if (started == 0) Toast.makeText(context, R.string.download_failed_start, Toast.LENGTH_LONG).show()
					else Toast.makeText(context, context.getString(R.string.download_started, if (items.size == 1) items.first().name.orEmpty() else context.resources.getQuantityString(R.plurals.episodes, started, started)), Toast.LENGTH_SHORT).show()
				}
			}) { Text(stringResource(R.string.download)) }
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
	)
}

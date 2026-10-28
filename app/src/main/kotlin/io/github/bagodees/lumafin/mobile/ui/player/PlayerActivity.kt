package io.github.bagodees.lumafin.mobile.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import io.github.bagodees.lumafin.mobile.LumaFinApplication
import io.github.bagodees.lumafin.mobile.R
import io.github.bagodees.lumafin.mobile.data.Download
import io.github.bagodees.lumafin.mobile.data.DownloadStatus
import io.github.bagodees.lumafin.mobile.data.PlaybackSource
import io.github.bagodees.lumafin.mobile.data.playbackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Plays a movie or episode full screen with touch controls (seek bar, audio and subtitle pickers),
 * starting where you left off and reporting progress so Continue Watching stays right on every device.
 * Folded halfway on a foldable (tabletop), the video sits on the top half and the controls on the bottom.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
	private lateinit var api: ApiClient
	private lateinit var player: ExoPlayer
	private lateinit var playerView: PlayerView
	private lateinit var tabletopControls: PlayerControlView
	private lateinit var tabletopPanel: LinearLayout
	private lateinit var tabletopTitle: TextView
	private var item: BaseItemDto? = null
	private var source: PlaybackSource? = null
	private var progressJob: Job? = null
	private var stopReported = true
	private var converted = false
	/** The download playing, when playing from this device instead of the server. */
	private var local: Download? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val application = application as LumaFinApplication
		api = application.sessions.api ?: return finish()
		val itemId = intent.getStringExtra(EXTRA_ITEM)?.toUUIDOrNull() ?: return finish()

		// Audio formats the device can't decode (surround, for example) are decoded in the app
		val renderers = DefaultRenderersFactory(this).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
		player = ExoPlayer.Builder(this, renderers).build()
		player.addListener(object : Player.Listener {
			override fun onPlaybackStateChanged(state: Int) {
				if (state == Player.STATE_ENDED) onEnded()
			}

			override fun onIsPlayingChanged(isPlaying: Boolean) = reportProgress()

			// A file the device turns out not to decode is converted by the server instead, from the same spot
			override fun onPlayerError(error: PlaybackException) {
				Timber.w(error, "Playback error")
				if (local != null) {
					Toast.makeText(this@PlayerActivity, R.string.player_failed, Toast.LENGTH_LONG).show()
					return finish()
				}
				val item = item ?: return
				if (converted) {
					Toast.makeText(this@PlayerActivity, R.string.player_failed, Toast.LENGTH_LONG).show()
					finish()
				} else {
					converted = true
					val position = player.currentPosition
					// Ends the failed session on the server before starting the converted one
					reportStopped()
					play(item.id, startMs = position, convert = true)
				}
			}
		})

		playerView = PlayerView(this).apply {
			player = this@PlayerActivity.player
			setShowSubtitleButton(true)
			setShowNextButton(false)
			setShowPreviousButton(false)
			setBackgroundColor(Color.BLACK)
			keepScreenOn = true
		}
		tabletopTitle = TextView(this).apply {
			setTextColor(Color.WHITE)
			textSize = 18f
			setPadding(48, 32, 48, 8)
		}
		tabletopControls = PlayerControlView(this).apply {
			player = this@PlayerActivity.player
			showTimeoutMs = 0
			setShowPreviousButton(false)
			setShowNextButton(false)
		}
		tabletopPanel = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setBackgroundColor(0xFF080D1C.toInt())
			gravity = Gravity.CENTER_HORIZONTAL
			addView(tabletopTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
			addView(tabletopControls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
			visibility = android.view.View.GONE
		}
		setContentView(FrameLayout(this).apply {
			setBackgroundColor(Color.BLACK)
			addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
			addView(tabletopPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		})
		hideSystemBars()
		watchFold()
		if (intent.getBooleanExtra(EXTRA_OFFLINE, false)) playDownload(itemId) else play(itemId)
	}

	/** Plays a download from this device; works without a connection. */
	private fun playDownload(itemId: UUID) {
		val downloads = (application as LumaFinApplication).downloads
		val download = downloads.find(itemId)?.takeIf { it.status == DownloadStatus.DONE } ?: return finish()
		lifecycleScope.launch {
			// The server's resume point, if it answers quickly and nothing newer from this device is waiting to be sent
			val serverData = if (download.unsynced) null else withTimeoutOrNull(3.seconds) {
				withContext(Dispatchers.IO) { runCatching { api.userLibraryApi.getItem(itemId).content.userData }.getOrNull() }
			}
			val ticks = serverData?.let { if (it.played) 0 else it.playbackPositionTicks } ?: download.positionTicks
			local = download
			item = null
			source = null
			tabletopTitle.text = download.title
			val media = MediaItem.Builder()
				.setUri(Uri.fromFile(File(download.file)))
				.setSubtitleConfigurations(download.subtitles.map {
					MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(File(it.path))).setMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage(it.language).setLabel(it.label).build()
				})
				.build()
			player.setMediaItem(media, ticks / 10_000)
			player.prepare()
			player.playWhenReady = true
		}
	}

	/** Keeps a download's resume point, and sends it to the server when it can be reached. */
	private fun saveLocal(played: Boolean = false) {
		val download = local ?: return
		(application as LumaFinApplication).downloads.savePosition(api, download.itemId, positionTicks(), played)
	}

	private fun hideSystemBars() {
		WindowCompat.setDecorFitsSystemWindows(window, false)
		WindowInsetsControllerCompat(window, window.decorView).apply {
			hide(WindowInsetsCompat.Type.systemBars())
			systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
		}
	}

	/** Tabletop: half folded with the hinge across the screen; the video moves above the hinge. */
	private fun watchFold() {
		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.STARTED) {
				WindowInfoTracker.getOrCreate(this@PlayerActivity).windowLayoutInfo(this@PlayerActivity).collect { info ->
					val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
					val tabletop = fold != null && fold.state == FoldingFeature.State.HALF_OPENED && fold.orientation == FoldingFeature.Orientation.HORIZONTAL
					if (tabletop) {
						val hinge = fold!!.bounds
						playerView.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hinge.top)
						tabletopPanel.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { topMargin = hinge.bottom }
						playerView.useController = false
						tabletopPanel.visibility = android.view.View.VISIBLE
					} else {
						playerView.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
						playerView.useController = true
						tabletopPanel.visibility = android.view.View.GONE
					}
				}
			}
		}
	}

	private fun play(itemId: UUID, startMs: Long? = null, convert: Boolean = false) {
		lifecycleScope.launch {
			try {
				val item = withContext(Dispatchers.IO) { api.userLibraryApi.getItem(itemId).content }
				val source = withContext(Dispatchers.IO) { api.playbackSource(item, convert = convert) } ?: error("No playable stream")
				this@PlayerActivity.item = item
				this@PlayerActivity.source = source
				tabletopTitle.text = listOfNotNull(item.seriesName, item.name).joinToString(" · ")
				val media = MediaItem.Builder()
					.setUri(Uri.parse(source.url))
					.apply { if (source.url.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }
					.setSubtitleConfigurations(source.externalSubtitles.map {
						MediaItem.SubtitleConfiguration.Builder(Uri.parse(it.url)).setMimeType(it.mimeType).setLanguage(it.language).setLabel(it.label).build()
					})
					.build()
				val resumeMs = startMs ?: ((item.userData?.playbackPositionTicks ?: 0) / 10_000)
				player.setMediaItem(media, resumeMs)
				player.prepare()
				player.playWhenReady = true
				stopReported = false
				report { api.playStateApi.reportPlaybackStart(PlaybackStartInfo(
					itemId = item.id, playSessionId = source.playSessionId, mediaSourceId = source.mediaSourceId,
					positionTicks = resumeMs * 10_000, canSeek = true, isPaused = false, isMuted = false,
					playMethod = source.method, repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT,
				)) }
				progressJob?.cancel()
				progressJob = lifecycleScope.launch { while (true) { delay(10.seconds); reportProgress() } }
			} catch (error: Exception) {
				if (error is kotlinx.coroutines.CancellationException) throw error
				Timber.w(error, "Playback failed")
				Toast.makeText(this@PlayerActivity, R.string.player_failed, Toast.LENGTH_LONG).show()
				finish()
			}
		}
	}

	private fun positionTicks() = player.currentPosition.coerceAtLeast(0) * 10_000

	private fun reportProgress() {
		if (local != null && !player.isPlaying) saveLocal()
		val item = item ?: return
		val source = source ?: return
		if (stopReported) return
		val ticks = positionTicks()
		val paused = !player.isPlaying
		report { api.playStateApi.reportPlaybackProgress(PlaybackProgressInfo(
			itemId = item.id, playSessionId = source.playSessionId, mediaSourceId = source.mediaSourceId,
			positionTicks = ticks, canSeek = true, isPaused = paused, isMuted = false,
			playMethod = source.method, repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT,
		)) }
	}

	private fun reportStopped() {
		if (local != null) saveLocal()
		val item = item ?: return
		val source = source ?: return
		if (stopReported) return
		stopReported = true
		progressJob?.cancel()
		val ticks = positionTicks()
		// Sent even as the screen closes, so the resume point is never lost
		(application as LumaFinApplication).appScope.launch(Dispatchers.IO) {
			runCatching { api.playStateApi.reportPlaybackStopped(PlaybackStopInfo(itemId = item.id, playSessionId = source.playSessionId, mediaSourceId = source.mediaSourceId, positionTicks = ticks, failed = false)) }
				.onFailure { Timber.w(it, "Couldn't report the stop") }
		}
	}

	private fun report(call: suspend () -> Unit) {
		lifecycleScope.launch(Dispatchers.IO) { runCatching { call() }.onFailure { Timber.w(it, "Couldn't report playback") } }
	}

	/** At the end of an episode, the next one starts when Autoplay is on. */
	private fun onEnded() {
		local?.let { download ->
			saveLocal(played = true)
			local = null
			val application = application as LumaFinApplication
			// The next downloaded episode of the same show, when Autoplay is on
			val next = if (!download.isEpisode || !application.settings.autoplayNext) null else application.downloads.downloads.value
				.filter { it.seriesId == download.seriesId && it.status == DownloadStatus.DONE }
				.sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 }))
				.firstOrNull { (it.seasonNumber ?: 0) > (download.seasonNumber ?: 0) || ((it.seasonNumber ?: 0) == (download.seasonNumber ?: 0) && (it.episodeNumber ?: 0) > (download.episodeNumber ?: 0)) }
			if (next == null) finish() else playDownload(next.itemId)
			return
		}
		val finished = item ?: return finish()
		reportStopped()
		val application = application as LumaFinApplication
		if (finished.type != BaseItemKind.EPISODE || !application.settings.autoplayNext) return finish()
		lifecycleScope.launch {
			// Give the server a moment to mark the episode watched before asking what's next
			delay(1.seconds)
			val next = withContext(Dispatchers.IO) {
				runCatching { api.tvShowsApi.getNextUp(GetNextUpRequest(seriesId = finished.seriesId, limit = 1)).content.items.firstOrNull() }.getOrNull()
			}
			converted = false
			if (next == null || next.id == finished.id) finish() else play(next.id)
		}
	}

	override fun onUserLeaveHint() {
		super.onUserLeaveHint()
		// Leaving while a video plays keeps it in a small window, where the device supports it
		if (player.isPlaying && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
			val size = player.videoSize
			val ratio = if (size.width > 0 && size.height > 0) Rational(size.width, size.height) else Rational(16, 9)
			runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ratio).build()) }
		}
	}

	override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
		super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
		playerView.useController = !isInPictureInPictureMode
	}

	override fun onStop() {
		super.onStop()
		// In picture-in-picture the video keeps playing; otherwise it pauses and the position is saved
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInPictureInPictureMode) {
			player.pause()
			reportProgress()
		}
	}

	override fun onDestroy() {
		reportStopped()
		player.release()
		super.onDestroy()
	}

	companion object {
		private const val EXTRA_ITEM = "item"
		private const val EXTRA_OFFLINE = "offline"

		/** Streams [itemId] from the server, or with [offline] plays its download from this device. */
		fun intent(context: Context, itemId: UUID, offline: Boolean = false) = Intent(context, PlayerActivity::class.java)
			.putExtra(EXTRA_ITEM, itemId.toString())
			.putExtra(EXTRA_OFFLINE, offline)
	}
}

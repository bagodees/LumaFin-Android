package io.github.bagodees.lumafin.mobile.data

import android.content.Context
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import io.github.bagodees.lumafin.mobile.MainActivity
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
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlayCommand
import org.jellyfin.sdk.model.api.PlaystateCommand
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

/** Somewhere a title can play instead of this phone. */
sealed interface CastDevice {
	val id: String
	val name: String

	/** Another Jellyfin app signed in to the same account that accepts remote control, like LumaFin on a TV. */
	data class Jellyfin(override val id: String, override val name: String, val app: String?) : CastDevice

	/** A Chromecast or a TV with Chromecast built in, playing through Jellyfin's own Cast receiver. */
	data class Chromecast(override val id: String, override val name: String) : CastDevice
}

/** What's playing on the device being cast to. */
data class CastState(val device: CastDevice, val title: String?, val playing: Boolean, val positionMs: Long, val durationMs: Long)

/**
 * Plays titles on other screens. Chromecasts get Jellyfin's Cast receiver, which streams from the
 * server itself; other Jellyfin apps are told what to play through the server. Either way this app
 * then works as the remote. Chromecast needs Google Play services, so Fire tablets only see Jellyfin apps.
 */
class CastManager(context: Context, private val sessions: SessionRepository, private val scope: CoroutineScope) {
	private val castContext: CastContext? = runCatching {
		if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) null
		else CastContext.getSharedInstance(context)
	}.onFailure { Timber.w(it, "Chromecast isn't available") }.getOrNull()
	/** False without Google Play services (Fire tablets): only Jellyfin apps can be cast to. */
	val chromecastSupported get() = castContext != null
	private val router = castContext?.let { MediaRouter.getInstance(context) }
	private val selector = MediaRouteSelector.Builder().addControlCategory(CastMediaControlIntent.categoryForCast(RECEIVER_ID)).build()

	private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
	val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()
	private val _state = MutableStateFlow<CastState?>(null)
	val state: StateFlow<CastState?> = _state.asStateFlow()

	private var discovery: Job? = null
	private var jellyfinDevices = emptyList<CastDevice>()
	private var follow: Job? = null
	private var pending: Pair<ApiClient, BaseItemDto>? = null

	private val routes = object : MediaRouter.Callback() {
		override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = publish()
		override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = publish()
		override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publish()
	}

	private val castSessions = object : SessionManagerListener<CastSession> {
		override fun onSessionStarted(session: CastSession, sessionId: String) = connected(session)
		override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = connected(session)
		override fun onSessionEnded(session: CastSession, error: Int) = disconnected()
		override fun onSessionStartFailed(session: CastSession, error: Int) = disconnected()
		override fun onSessionResumeFailed(session: CastSession, error: Int) = disconnected()
		override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
		override fun onSessionStarting(session: CastSession) = Unit
		override fun onSessionEnding(session: CastSession) = Unit
		override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
	}

	private val mediaStatus = object : RemoteMediaClient.Callback() {
		override fun onStatusUpdated() = updateChromecast()
	}

	init {
		castContext?.sessionManager?.addSessionManagerListener(castSessions, CastSession::class.java)
		castContext?.sessionManager?.currentCastSession?.let(::connected)
	}

	/** Looks for devices while the picker is open. */
	fun startDiscovery(api: ApiClient) {
		router?.addCallback(selector, routes, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
		discovery?.cancel()
		discovery = scope.launch {
			while (isActive) {
				jellyfinDevices = withContext(Dispatchers.IO) { findJellyfinDevices(api) }
				publish()
				delay(5.seconds)
			}
		}
		publish()
	}

	fun stopDiscovery() {
		router?.removeCallback(routes)
		discovery?.cancel()
	}

	private fun publish() {
		val chromecasts = router?.routes.orEmpty()
			.filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
			.map { CastDevice.Chromecast(it.id, it.name) }
		_devices.value = chromecasts + jellyfinDevices
	}

	private suspend fun findJellyfinDevices(api: ApiClient): List<CastDevice> = runCatching {
		val userId = sessions.session.value?.userId ?: return emptyList()
		api.sessionApi.getSessions(controllableByUserId = userId, activeWithinSeconds = 960).content
			.filter { it.supportsRemoteControl == true && it.deviceId != api.deviceInfo.id && it.playableMediaTypes.orEmpty().contains(MediaType.VIDEO) }
			.mapNotNull { CastDevice.Jellyfin(it.id ?: return@mapNotNull null, it.deviceName ?: it.client.orEmpty(), it.client) }
	}.onFailure { Timber.w(it, "Couldn't list Jellyfin devices") }.getOrDefault(emptyList())

	/** Plays [item] on [device], from where you left off. */
	fun play(api: ApiClient, device: CastDevice, item: BaseItemDto) {
		when (device) {
			is CastDevice.Jellyfin -> scope.launch {
				endChromecast()
				runCatching {
					withContext(Dispatchers.IO) {
						api.sessionApi.play(device.id, PlayCommand.PLAY_NOW, listOf(item.id), startPositionTicks = item.userData?.playbackPositionTicks?.takeIf { it > 0 })
					}
				}.onSuccess {
					_state.value = CastState(device, item.name, playing = true, positionMs = 0, durationMs = (item.runTimeTicks ?: 0) / 10_000)
					followJellyfin(api, device)
				}.onFailure { Timber.w(it, "Couldn't start playback on ${device.name}") }
			}

			is CastDevice.Chromecast -> {
				follow?.cancel()
				val current = castContext?.sessionManager?.currentCastSession
				if (current?.isConnected == true && router?.selectedRoute?.id == device.id) {
					send(current, api, item)
				} else {
					pending = api to item
					router?.routes?.firstOrNull { it.id == device.id }?.let { router.selectRoute(it) }
				}
			}
		}
	}

	/** Plays [item] on whatever is being cast to now; false when nothing is. */
	fun playOnCurrent(api: ApiClient, item: BaseItemDto): Boolean {
		val device = _state.value?.device ?: return false
		play(api, device, item)
		return true
	}

	fun togglePause() {
		val state = _state.value ?: return
		when (val device = state.device) {
			is CastDevice.Chromecast -> remote()?.togglePlayback()
			is CastDevice.Jellyfin -> command(device, PlaystateCommand.PLAY_PAUSE)
		}
		_state.value = state.copy(playing = !state.playing)
	}

	fun seekBy(deltaMs: Long) {
		val state = _state.value ?: return
		val position = (state.positionMs + deltaMs).coerceIn(0, state.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
		when (val device = state.device) {
			is CastDevice.Chromecast -> remote()?.seek(MediaSeekOptions.Builder().setPosition(position).build())
			is CastDevice.Jellyfin -> command(device, PlaystateCommand.SEEK, position * 10_000)
		}
		_state.value = state.copy(positionMs = position)
	}

	/** Stops playback on the other screen and returns to this phone. */
	fun stop() {
		val state = _state.value ?: return
		when (val device = state.device) {
			is CastDevice.Chromecast -> endChromecast()
			is CastDevice.Jellyfin -> command(device, PlaystateCommand.STOP)
		}
		follow?.cancel()
		_state.value = null
	}

	private fun command(device: CastDevice.Jellyfin, command: PlaystateCommand, seekTicks: Long? = null) {
		val api = sessions.api ?: return
		scope.launch(Dispatchers.IO) {
			runCatching { api.sessionApi.sendPlaystateCommand(device.id, command, seekPositionTicks = seekTicks) }
				.onFailure { Timber.w(it, "Couldn't send $command") }
		}
	}

	/** Keeps the bar in step with the other app, and clears it once that app has finished. */
	private fun followJellyfin(api: ApiClient, device: CastDevice.Jellyfin) {
		follow?.cancel()
		follow = scope.launch {
			var seenPlaying = false
			var idlePolls = 0
			while (isActive) {
				delay(2.seconds)
				val session = withContext(Dispatchers.IO) {
					runCatching { api.sessionApi.getSessions(controllableByUserId = sessions.session.value?.userId).content.firstOrNull { it.id == device.id } }.getOrNull()
				}
				val nowPlaying = session?.nowPlayingItem
				if (nowPlaying != null) {
					seenPlaying = true
					idlePolls = 0
					_state.value = CastState(
						device = device,
						title = listOfNotNull(nowPlaying.seriesName, nowPlaying.name).joinToString(" · "),
						playing = session.playState?.isPaused != true,
						positionMs = (session.playState?.positionTicks ?: 0) / 10_000,
						durationMs = (nowPlaying.runTimeTicks ?: 0) / 10_000,
					)
				} else if (++idlePolls >= if (seenPlaying) 3 else 15) {
					_state.value = null
					return@launch
				}
			}
		}
	}

	private fun remote(): RemoteMediaClient? = castContext?.sessionManager?.currentCastSession?.remoteMediaClient

	private fun connected(session: CastSession) {
		session.remoteMediaClient?.unregisterCallback(mediaStatus)
		session.remoteMediaClient?.registerCallback(mediaStatus)
		pending?.let { (api, item) -> send(session, api, item) }
		pending = null
		updateChromecast()
		// The receiver reports its position now and then; this keeps the bar's clock moving in between
		follow?.cancel()
		follow = scope.launch {
			while (isActive) {
				delay(1.seconds)
				updateChromecast()
			}
		}
	}

	private fun disconnected() {
		pending = null
		follow?.cancel()
		if (_state.value?.device is CastDevice.Chromecast) _state.value = null
	}

	private fun endChromecast() {
		castContext?.sessionManager?.endCurrentSession(true)
	}

	private fun updateChromecast() {
		val session = castContext?.sessionManager?.currentCastSession ?: return
		val remote = session.remoteMediaClient
		val name = session.castDevice?.friendlyName.orEmpty()
		val device = router?.selectedRoute?.let { CastDevice.Chromecast(it.id, it.name) } ?: CastDevice.Chromecast(name, name)
		val previous = _state.value
		_state.value = CastState(
			device = device,
			title = remote?.mediaInfo?.metadata?.getString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE) ?: previous?.title,
			playing = remote?.isPlaying == true || remote?.isBuffering == true,
			positionMs = remote?.approximateStreamPosition ?: 0,
			durationMs = remote?.streamDuration?.takeIf { it > 0 } ?: previous?.durationMs ?: 0,
		)
	}

	/** Jellyfin's receiver takes the same message the Jellyfin web app sends it. */
	private fun send(session: CastSession, api: ApiClient, item: BaseItemDto) {
		val account = sessions.session.value ?: return
		val stub = JSONObject()
			.put("Id", item.id.toString().replace("-", ""))
			.put("ServerId", item.serverId)
			.put("Name", item.name)
			.put("Type", item.type.serialName)
			.put("MediaType", "Video")
			.put("IsFolder", false)
		val message = JSONObject()
			.put("options", JSONObject().put("items", JSONArray().put(stub)).put("startPositionTicks", item.userData?.playbackPositionTicks ?: 0))
			.put("command", "PlayNow")
			.put("userId", account.userId.toString().replace("-", ""))
			.put("deviceId", api.deviceInfo.id)
			.put("accessToken", account.accessToken)
			.put("serverAddress", account.serverUrl)
			.put("serverId", item.serverId)
			.put("receiverName", session.castDevice?.friendlyName)
		_state.value = CastState(
			device = router?.selectedRoute?.let { CastDevice.Chromecast(it.id, it.name) } ?: CastDevice.Chromecast("", session.castDevice?.friendlyName.orEmpty()),
			title = listOfNotNull(item.seriesName, item.name).joinToString(" · "),
			playing = true,
			positionMs = (item.userData?.playbackPositionTicks ?: 0) / 10_000,
			durationMs = (item.runTimeTicks ?: 0) / 10_000,
		)
		session.sendMessage(NAMESPACE, message.toString()).setResultCallback { result ->
			if (!result.isSuccess) Timber.w("Chromecast didn't take the title: ${result.statusMessage}")
		}
	}

	companion object {
		/** Jellyfin's published Cast receiver, the one the Jellyfin web and Android apps use. */
		const val RECEIVER_ID = "F007D354"
		private const val NAMESPACE = "urn:x-cast:com.connectsdk"
	}
}

/** Tells the Cast library which receiver to start; named in the manifest. */
class CastOptionsProvider : OptionsProvider {
	override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
		.setReceiverApplicationId(CastManager.RECEIVER_ID)
		.setCastMediaOptions(
			CastMediaOptions.Builder()
				.setNotificationOptions(NotificationOptions.Builder().setTargetActivityClassName(MainActivity::class.java.name).build())
				.build(),
		)
		.build()

	override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

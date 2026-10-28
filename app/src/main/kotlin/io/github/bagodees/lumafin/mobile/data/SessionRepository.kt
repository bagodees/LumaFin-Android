package io.github.bagodees.lumafin.mobile.data

import android.content.Context
import io.github.bagodees.lumafin.mobile.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jellyfin.sdk.android.androidDevice
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.util.UUID

/** Who is signed in, to which server. One account at a time for now. */
data class Session(
	val serverUrl: String,
	val serverName: String,
	val userId: UUID,
	val userName: String,
	val accessToken: String,
)

class SessionRepository(context: Context) {
	private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

	val jellyfin = createJellyfin {
		this.context = context
		clientInfo = ClientInfo(
			name = if (BuildConfig.DEBUG) "LumaFin for Android (debug)" else "LumaFin for Android",
			version = BuildConfig.VERSION_NAME,
		)
		deviceInfo = androidDevice(context)
	}

	private val _session = MutableStateFlow(load())
	val session: StateFlow<Session?> = _session.asStateFlow()

	/** The signed-in API client; null when nobody is signed in. */
	var api: ApiClient? = _session.value?.let(::apiFor)
		private set

	fun apiFor(serverUrl: String): ApiClient = jellyfin.createApi(baseUrl = serverUrl)

	private fun apiFor(session: Session): ApiClient = jellyfin.createApi(baseUrl = session.serverUrl, accessToken = session.accessToken)

	fun signIn(session: Session) {
		prefs.edit()
			.putString(KEY_SERVER_URL, session.serverUrl)
			.putString(KEY_SERVER_NAME, session.serverName)
			.putString(KEY_USER_ID, session.userId.toString())
			.putString(KEY_USER_NAME, session.userName)
			.putString(KEY_TOKEN, session.accessToken)
			.apply()
		api = apiFor(session)
		_session.value = session
	}

	suspend fun signOut() {
		// Ends the session on the server too, so it disappears from the server's device list
		runCatching { api?.sessionApi?.reportSessionEnded() }.onFailure { Timber.w(it, "Couldn't end the session on the server") }
		prefs.edit().clear().apply()
		api = null
		_session.value = null
	}

	private fun load(): Session? {
		return Session(
			serverUrl = prefs.getString(KEY_SERVER_URL, null) ?: return null,
			serverName = prefs.getString(KEY_SERVER_NAME, null).orEmpty(),
			userId = prefs.getString(KEY_USER_ID, null)?.toUUIDOrNull() ?: return null,
			userName = prefs.getString(KEY_USER_NAME, null).orEmpty(),
			accessToken = prefs.getString(KEY_TOKEN, null) ?: return null,
		)
	}

	private companion object {
		const val KEY_SERVER_URL = "server_url"
		const val KEY_SERVER_NAME = "server_name"
		const val KEY_USER_ID = "user_id"
		const val KEY_USER_NAME = "user_name"
		const val KEY_TOKEN = "access_token"
	}
}

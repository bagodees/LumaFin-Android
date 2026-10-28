package io.github.bagodees.lumafin.mobile.ui.signin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bagodees.lumafin.mobile.LumaFinApplication
import io.github.bagodees.lumafin.mobile.data.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticateWithQuickConnect
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.UserDto
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

data class Server(val url: String, val name: String)

sealed interface SignInStep {
	data class ChooseServer(val address: String = "", val busy: Boolean = false, val failed: Boolean = false) : SignInStep
	data class ChooseUser(val server: Server, val users: List<UserDto>) : SignInStep
	data class Password(val server: Server, val username: String, val fixedUser: Boolean, val busy: Boolean = false, val failed: Boolean = false) : SignInStep
	data class QuickConnect(val server: Server, val code: String?, val unavailable: Boolean = false) : SignInStep
}

class SignInViewModel(application: Application) : AndroidViewModel(application) {
	private val sessions = (application as LumaFinApplication).sessions
	private val _step = MutableStateFlow<SignInStep>(SignInStep.ChooseServer())
	val step: StateFlow<SignInStep> = _step.asStateFlow()
	private var quickConnectJob: Job? = null

	fun connect(address: String) {
		val trimmed = address.trim()
		if (trimmed.isEmpty()) return
		_step.value = SignInStep.ChooseServer(trimmed, busy = true)
		viewModelScope.launch {
			// The SDK tries http/https and the default port, and scores each address it reaches
			val recommended = runCatching {
				withContext(Dispatchers.IO) {
					val candidates = sessions.jellyfin.discovery.getAddressCandidates(trimmed)
					sessions.jellyfin.discovery.getRecommendedServers(candidates).toList()
				}
			}.onFailure { if (it is CancellationException) throw it; Timber.w(it, "Server lookup failed") }.getOrDefault(emptyList())
			val best = recommended.firstOrNull { it.score == RecommendedServerInfoScore.GREAT }
				?: recommended.firstOrNull { it.score == RecommendedServerInfoScore.GOOD }
			val info = best?.systemInfo?.getOrNull()
			if (best == null || info == null) {
				_step.value = SignInStep.ChooseServer(trimmed, failed = true)
				return@launch
			}
			val server = Server(best.address, info.serverName ?: "Jellyfin")
			val users = runCatching { withContext(Dispatchers.IO) { sessions.apiFor(server.url).userApi.getPublicUsers().content } }
				.getOrDefault(emptyList())
			usersStep = users.takeIf { it.isNotEmpty() }?.let { SignInStep.ChooseUser(server, it) }
			_step.value = usersStep ?: SignInStep.Password(server, "", fixedUser = false)
		}
	}

	fun chooseUser(server: Server, user: UserDto?) {
		_step.value = SignInStep.Password(server, user?.name.orEmpty(), fixedUser = user != null)
	}

	fun signIn(server: Server, username: String, password: String) {
		val current = _step.value as? SignInStep.Password ?: return
		_step.value = current.copy(username = username, busy = true, failed = false)
		viewModelScope.launch {
			val result = runCatching {
				withContext(Dispatchers.IO) { sessions.apiFor(server.url).userApi.authenticateUserByName(username, password).content }
			}.onFailure { if (it is CancellationException) throw it; Timber.w(it, "Sign in failed") }.getOrNull()
			if (result == null || !finish(server, result)) _step.value = current.copy(username = username, busy = false, failed = true)
		}
	}

	fun startQuickConnect(server: Server) {
		quickConnectJob?.cancel()
		_step.value = SignInStep.QuickConnect(server, code = null)
		quickConnectJob = viewModelScope.launch {
			val api = sessions.apiFor(server.url)
			val started = runCatching { withContext(Dispatchers.IO) { api.quickConnectApi.initiateQuickConnect().content } }
				.onFailure { if (it is CancellationException) throw it; Timber.w(it, "Quick Connect unavailable") }.getOrNull()
			if (started == null) {
				_step.value = SignInStep.QuickConnect(server, code = null, unavailable = true)
				return@launch
			}
			_step.value = SignInStep.QuickConnect(server, started.code)
			// Checks every few seconds until someone approves the code on a signed-in device
			while (true) {
				delay(3.seconds)
				val state = runCatching { withContext(Dispatchers.IO) { api.quickConnectApi.getQuickConnectState(started.secret).content } }.getOrNull() ?: continue
				if (state.authenticated) {
					val result = runCatching { withContext(Dispatchers.IO) { api.userApi.authenticateWithQuickConnect(state.secret).content } }.getOrNull()
					if (result != null && finish(server, result)) return@launch
					_step.value = SignInStep.QuickConnect(server, code = null, unavailable = true)
					return@launch
				}
			}
		}
	}

	// The user list for the current server, so Back from a password or Quick Connect returns to it
	private var usersStep: SignInStep.ChooseUser? = null

	fun back() {
		quickConnectJob?.cancel()
		_step.value = when (val current = _step.value) {
			is SignInStep.ChooseServer -> current
			is SignInStep.ChooseUser -> SignInStep.ChooseServer(current.server.url)
			is SignInStep.Password -> usersStep?.takeIf { it.server == current.server } ?: SignInStep.ChooseServer(current.server.url)
			is SignInStep.QuickConnect -> usersStep?.takeIf { it.server == current.server } ?: SignInStep.ChooseServer(current.server.url)
		}
	}

	private fun finish(server: Server, result: AuthenticationResult): Boolean {
		val token = result.accessToken ?: return false
		val user = result.user ?: return false
		sessions.signIn(Session(server.url, server.name, user.id, user.name.orEmpty(), token))
		// After signing out, sign-in starts again from this server's users
		_step.value = usersStep ?: SignInStep.ChooseServer(server.url)
		return true
	}

	override fun onCleared() {
		quickConnectJob?.cancel()
	}
}


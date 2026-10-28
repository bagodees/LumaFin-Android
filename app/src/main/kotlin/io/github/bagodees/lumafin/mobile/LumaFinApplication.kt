package io.github.bagodees.lumafin.mobile

import android.app.Application
import io.github.bagodees.lumafin.mobile.data.AppSettings
import io.github.bagodees.lumafin.mobile.data.CastManager
import io.github.bagodees.lumafin.mobile.data.DownloadRepository
import io.github.bagodees.lumafin.mobile.data.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

class LumaFinApplication : Application() {
	lateinit var sessions: SessionRepository
		private set
	lateinit var settings: AppSettings
		private set
	lateinit var downloads: DownloadRepository
		private set
	lateinit var cast: CastManager
		private set

	/** For work that must finish even as a screen closes, like saving the playback position. */
	val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

	override fun onCreate() {
		super.onCreate()
		if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
		sessions = SessionRepository(this)
		settings = AppSettings(this)
		downloads = DownloadRepository(this, sessions, settings, appScope)
		cast = CastManager(this, sessions, appScope)
		// Resume points saved while offline go to the server once it can be reached
		sessions.api?.let { api -> appScope.launch { downloads.sync(api) } }
	}
}

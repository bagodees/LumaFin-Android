package io.github.bagodees.lumafin.mobile

import android.app.Application
import io.github.bagodees.lumafin.mobile.data.SessionRepository
import timber.log.Timber

class LumaFinApplication : Application() {
	lateinit var sessions: SessionRepository
		private set

	override fun onCreate() {
		super.onCreate()
		if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
		sessions = SessionRepository(this)
	}
}

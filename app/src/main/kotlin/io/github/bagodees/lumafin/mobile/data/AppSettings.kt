package io.github.bagodees.lumafin.mobile.data

import android.content.Context

/** The phone app's own settings, kept on the device. */
class AppSettings(context: Context) {
	private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

	/** Seerr's My Requests, Trending and Upcoming rows at the end of Home (needs SeerrFin on the server). */
	var seerrRows: Boolean
		get() = prefs.getBoolean("seerr_rows", true)
		set(value) = prefs.edit().putBoolean("seerr_rows", value).apply()

	/** Play the next episode when one ends. */
	var autoplayNext: Boolean
		get() = prefs.getBoolean("autoplay_next", true)
		set(value) = prefs.edit().putBoolean("autoplay_next", value).apply()

	/** Downloads wait for Wi-Fi instead of using mobile data. */
	var downloadWifiOnly: Boolean
		get() = prefs.getBoolean("download_wifi_only", true)
		set(value) = prefs.edit().putBoolean("download_wifi_only", value).apply()

	/** The quality picked for the last download, offered first next time. */
	var downloadQuality: DownloadQuality
		get() = runCatching { DownloadQuality.valueOf(prefs.getString("download_quality", null).orEmpty()) }.getOrDefault(DownloadQuality.ORIGINAL)
		set(value) = prefs.edit().putString("download_quality", value.name).apply()
}

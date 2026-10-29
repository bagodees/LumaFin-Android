package io.github.bagodees.lumafin.mobile.telemetry

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Crash reports only cover crashes the app catches itself. When the system ends the app instead
 * (a crash in a native decoder, a freeze long enough to be closed as not responding, or running
 * out of memory) nothing is caught. Android 11 and newer keep a record of why the app last
 * closed; on the next start this sends any such exit to the crash server, with the system's
 * trace where it has one and the app's last log lines before it ended.
 */
object ExitReporter {
	private const val PREFERENCES_NAME = "crash_server"
	private const val KEY_LAST_REPORTED = "last_reported_exit"
	private const val MAX_TRACE_CHARS = 120_000
	private const val LOG_LINES = 400

	// The first start with this feature also sends exits from the day before, so a crash seen
	// on the previous build still reaches the server
	private val FIRST_RUN_WINDOW_MS = TimeUnit.DAYS.toMillis(1)

	fun report(context: Context) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !CrashServer.isEnabled) return
		reportExits(context)
	}

	@RequiresApi(Build.VERSION_CODES.R)
	private fun reportExits(context: Context) {
		val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
		val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
		val exits = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 10)
			.filter { it.processName == context.packageName }
		val lastReported = preferences.getLong(KEY_LAST_REPORTED, 0L)
		val since = if (lastReported == 0L) System.currentTimeMillis() - FIRST_RUN_WINDOW_MS else lastReported
		// Only while the app was on screen; phones and Fire tablets often end apps in the background to free memory.
		// Crashes the app caught itself end as REASON_CRASH, which isn't listed, so they aren't sent twice.
		val toReport = exits
			.filter { it.timestamp > since && it.reason in REPORTED_REASONS && it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE }
			.sortedBy { it.timestamp }.takeLast(3)

		// Remembered before sending, so a report that fails to send isn't retried on every start
		exits.maxOfOrNull { it.timestamp }?.let { newest ->
			if (newest > lastReported) preferences.edit().putLong(KEY_LAST_REPORTED, newest).apply()
		}

		for (exit in toReport) {
			runCatching {
				CrashServer.sendExit(
					context = context,
					reason = reasonName(exit.reason),
					description = exit.description,
					timestamp = exit.timestamp,
					details = mapOf(
						"status" to exit.status.toString(),
						"was_foreground" to (exit.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND).toString(),
						"memory_pss_kb" to exit.pss.toString(),
						"memory_rss_kb" to exit.rss.toString(),
					),
					trace = trace(exit),
					logcat = CrashServer.logLines(exit.pid, LOG_LINES),
				)
			}
				.onSuccess { Timber.i("Reported the previous exit (%s) to the crash server", reasonName(exit.reason)) }
				.onFailure { Timber.w(it, "Couldn't send the exit report to the crash server") }
		}
	}

	private val REPORTED_REASONS = setOf(
		ApplicationExitInfo.REASON_CRASH_NATIVE,
		ApplicationExitInfo.REASON_ANR,
		ApplicationExitInfo.REASON_LOW_MEMORY,
		ApplicationExitInfo.REASON_SIGNALED,
		ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
		ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
		ApplicationExitInfo.REASON_UNKNOWN,
	)

	private fun reasonName(reason: Int) = when (reason) {
		ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
		ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
		ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
		ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
		ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
		ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
		ApplicationExitInfo.REASON_UNKNOWN -> "unknown"
		else -> "reason $reason"
	}

	/**
	 * The system's trace when it's text, like the dump of a freeze (ANR). Native crash tombstones
	 * from Android 12 on are binary and would only bury the log, so those are left out.
	 */
	@RequiresApi(Build.VERSION_CODES.R)
	private fun trace(exit: ApplicationExitInfo): String? = runCatching {
		exit.traceInputStream?.use { stream ->
			val text = stream.bufferedReader().readText()
			val unreadable = text.count { it == '�' || (it < ' ' && it !in "\n\r\t") }
			when {
				unreadable > text.length / 50 -> null
				text.length > MAX_TRACE_CHARS -> text.take(MAX_TRACE_CHARS) + "\n[trimmed]"
				else -> text
			}
		}
	}.getOrNull()
}

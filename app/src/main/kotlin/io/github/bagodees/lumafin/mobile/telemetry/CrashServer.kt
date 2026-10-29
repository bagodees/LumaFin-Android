package io.github.bagodees.lumafin.mobile.telemetry

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import io.github.bagodees.lumafin.mobile.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Sends crash and exit reports to LumaFin's own crash server (the same one the TV app's test
 * builds use, in its own project), so crashes from test builds can be seen without asking each
 * tester for a log. Only builds made with a crash server address (GitHub test builds) send
 * anything; store builds have none. Server addresses, IP addresses and tokens are removed before
 * a report leaves the device, and devices are told apart only by a random id created on the device.
 *
 * A crash is saved to a file before the app closes and sent from there, so a crash with no
 * network, or one that ends the app before the report is out, is sent on the next start.
 */
object CrashServer {
	private const val PREFERENCES_NAME = "crash_server"
	private const val KEY_INSTALL_ID = "install_id"
	private const val PENDING_DIR = "crash-reports"
	private const val MAX_PENDING = 10
	private const val CONNECT_TIMEOUT_MS = 10_000
	private const val READ_TIMEOUT_MS = 15_000
	private const val SEND_ON_CRASH_WAIT_MS = 4_000L
	private const val LOG_LINES = 250
	private val MAX_PENDING_AGE_MS = TimeUnit.DAYS.toMillis(7)

	private val storeUrl: String? by lazy {
		if (!isTestVersion(BuildConfig.VERSION_NAME)) null else storeUrl(BuildConfig.CRASH_SERVER_DSN)
	}
	private val publicKey: String? by lazy { publicKey(BuildConfig.CRASH_SERVER_DSN) }

	val isEnabled: Boolean get() = storeUrl != null

	/** Alpha and beta builds; final releases never report, even with an address. */
	internal fun isTestVersion(versionName: String) = versionName.contains("-alpha") || versionName.contains("-beta")

	/** The crash server's store address from a DSN like https://key@crash.example.com/2. */
	internal fun storeUrl(dsn: String): String? = runCatching {
		if (dsn.isBlank()) return null
		val uri = URI(dsn)
		val projectId = uri.path.trim('/')
		if (uri.userInfo.isNullOrBlank() || projectId.isEmpty()) return null
		val port = if (uri.port != -1) ":${uri.port}" else ""
		"${uri.scheme}://${uri.host}$port/api/$projectId/store/"
	}.getOrNull()

	private fun publicKey(dsn: String): String? = runCatching { URI(dsn).userInfo }.getOrNull()

	/** Call first in the application's onCreate. Does nothing in builds without a crash server. */
	fun init(context: Context) {
		if (!isEnabled) return
		val appContext = context.applicationContext
		val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { crashedThread, throwable ->
			runCatching { saveAndSendCrash(appContext, throwable) }
			previousHandler?.uncaughtException(crashedThread, throwable)
		}

		thread(name = "crash-reports", isDaemon = true) {
			runCatching { sendPending(appContext) }.onFailure { Timber.w(it, "Couldn't send saved crash reports") }
			runCatching { ExitReporter.report(appContext) }.onFailure { Timber.w(it, "Couldn't report the previous exit") }
		}
	}

	private fun saveAndSendCrash(context: Context, throwable: Throwable) {
		val stackTrace = throwable.stackTraceToString()
		val event = baseEvent(context).apply {
			put("exception", JSONObject().put("values", parseExceptions(stackTrace)))
			put("extra", JSONObject().apply {
				put("stack_trace", scrub(stackTrace))
				put("thread", Thread.currentThread().name)
				logLines(android.os.Process.myPid())?.let { put("logcat", scrub(it)) }
			})
		}
		val file = File(pendingDir(context), "${event.getString("event_id")}.json")
		file.writeText(event.toString())

		// Try to send it before the app closes; if that takes too long it goes on the next start
		val sender = thread(name = "crash-send") {
			runCatching { post(event) }.onSuccess { file.delete() }
		}
		sender.join(SEND_ON_CRASH_WAIT_MS)
	}

	private fun pendingDir(context: Context) = File(context.filesDir, PENDING_DIR).apply { mkdirs() }

	private fun sendPending(context: Context) {
		val files = pendingDir(context).listFiles { file -> file.extension == "json" }.orEmpty().sortedBy { it.lastModified() }
		val now = System.currentTimeMillis()
		for ((index, file) in files.withIndex()) {
			// Old or surplus reports would only pile up on a device that can't reach the server
			if (now - file.lastModified() > MAX_PENDING_AGE_MS || index < files.size - MAX_PENDING) {
				file.delete()
				continue
			}
			val event = runCatching { JSONObject(file.readText()) }.getOrNull()
			if (event == null) {
				file.delete()
				continue
			}
			runCatching { post(event) }
				.onSuccess {
					file.delete()
					Timber.i("Sent a saved crash report to the crash server")
				}
				.onFailure { Timber.w(it, "Couldn't send a saved crash report; trying again on the next start") }
		}
	}

	/** Sends an exit the system caused (low memory, a native crash, not responding). */
	fun sendExit(
		context: Context,
		reason: String,
		description: String?,
		timestamp: Long,
		details: Map<String, String>,
		trace: String?,
		logcat: String?,
	) {
		val event = baseEvent(context).apply {
			put("timestamp", Instant.ofEpochMilli(timestamp).toString())
			put("message", JSONObject().put("formatted", "App ended by the system: $reason"))
			put("fingerprint", JSONArray().put("system-exit").put(reason))
			getJSONObject("tags").put("exit_reason", reason)
			put("extra", JSONObject().apply {
				put("description", description?.let(::scrub) ?: "none")
				details.forEach { (key, value) -> put(key, value) }
				if (trace != null) put("system_trace", scrub(trace))
				if (logcat != null) put("logcat", scrub(logcat))
			})
		}
		post(event)
	}

	private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

	private fun installId(context: Context): String {
		val preferences = preferences(context)
		return preferences.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().also {
			preferences.edit().putString(KEY_INSTALL_ID, it).apply()
		}
	}

	private fun baseEvent(context: Context) = JSONObject().apply {
		put("event_id", UUID.randomUUID().toString().replace("-", ""))
		put("timestamp", Instant.now().toString())
		put("platform", "java")
		put("level", "fatal")
		put("logger", "lumafin")
		put("release", "lumafin-android@${BuildConfig.VERSION_NAME}")
		put("environment", "test")
		put("user", JSONObject().put("id", installId(context)))
		put("tags", JSONObject().apply {
			put("device_model", Build.MODEL)
			put("device_brand", Build.BRAND)
			put("android", Build.VERSION.RELEASE)
			put("low_memory_device", context.isLowRamDeviceSafe().toString())
			put("screen_width_dp", context.resources.configuration.screenWidthDp.toString())
		})
		put("contexts", JSONObject().apply {
			put("device", JSONObject().apply {
				put("type", "device")
				put("brand", Build.BRAND)
				put("model", Build.MODEL)
				put("manufacturer", Build.MANUFACTURER)
			})
			put("os", JSONObject().apply {
				put("type", "os")
				put("name", "Android")
				put("version", Build.VERSION.RELEASE)
				put("build", Build.DISPLAY)
			})
			put("app", JSONObject().apply {
				put("type", "app")
				put("app_version", BuildConfig.VERSION_NAME)
				put("app_build", BuildConfig.VERSION_CODE.toString())
			})
		})
	}

	private fun Context.isLowRamDeviceSafe() =
		runCatching { getSystemService(ActivityManager::class.java)?.isLowRamDevice == true }.getOrDefault(false)

	private fun post(event: JSONObject) {
		val url = storeUrl ?: return
		val connection = URL(url).openConnection() as HttpURLConnection
		try {
			connection.connectTimeout = CONNECT_TIMEOUT_MS
			connection.readTimeout = READ_TIMEOUT_MS
			connection.requestMethod = "POST"
			connection.doOutput = true
			connection.setRequestProperty("Content-Type", "application/json")
			connection.setRequestProperty(
				"X-Sentry-Auth",
				"Sentry sentry_version=7, sentry_client=lumafin-android/${BuildConfig.VERSION_NAME}, sentry_key=$publicKey",
			)
			connection.outputStream.use { it.write(event.toString().toByteArray()) }
			val code = connection.responseCode
			if (code !in 200..299) throw IOException("Crash server answered $code")
		} finally {
			connection.disconnect()
		}
	}

	/**
	 * The app's log lines from a process, while they're still in the system's buffer. Android 13
	 * and newer ask the user before an app reads the log, so there reports go without.
	 */
	internal fun logLines(pid: Int, lines: Int = LOG_LINES): String? =
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) null else runCatching {
			val process = ProcessBuilder("logcat", "-d", "-v", "time", "--pid=$pid", "-t", lines.toString())
				.redirectErrorStream(true)
				.start()
			val text = process.inputStream.bufferedReader().readText()
			process.waitFor(5, TimeUnit.SECONDS)
			text.takeIf { it.isNotBlank() }
		}.getOrNull()

	private val FRAME = Regex("""^\s*at ([\w$.<>\-]+)\.([\w$<>\-]+)\((.*)\)\s*$""")

	/** Turns a Java stack trace (with its "Caused by" chain) into the crash server's exception list, oldest cause first. */
	internal fun parseExceptions(stackTrace: String): JSONArray {
		val blocks = mutableListOf<Pair<String, MutableList<String>>>()
		for (line in stackTrace.lineSequence()) {
			val header = when {
				blocks.isEmpty() && line.isNotBlank() -> line
				line.startsWith("Caused by: ") -> line.removePrefix("Caused by: ")
				else -> null
			}
			if (header != null) blocks += header to mutableListOf()
			else if (FRAME.matches(line)) blocks.lastOrNull()?.second?.add(line)
		}

		val values = JSONArray()
		for ((header, lines) in blocks.asReversed()) {
			val type = header.substringBefore(':').trim()
			val value = header.substringAfter(':', "").trim()
			val frames = JSONArray()
			// The crash server lists frames oldest call first
			for (line in lines.asReversed()) {
				val match = FRAME.matchEntire(line) ?: continue
				val (module, function, location) = match.destructured
				frames.put(JSONObject().apply {
					put("module", module)
					put("function", function)
					put("filename", location.substringBefore(':'))
					location.substringAfter(':', "").toIntOrNull()?.let { put("lineno", it) }
					put("in_app", module.startsWith("io.github.bagodees") || module.startsWith("org.jellyfin"))
				})
			}
			values.put(JSONObject().apply {
				put("type", type)
				put("value", scrub(value))
				if (frames.length() > 0) put("stacktrace", JSONObject().put("frames", frames))
			})
		}
		return values
	}

	private val URL_HOST = Regex("""(https?|wss?)://[^\s/"'<>]+""", RegexOption.IGNORE_CASE)
	private val SECRET_PARAM = Regex("""((?:api_?key|access_?token|token|x-emby-token|x-mediabrowser-token|deviceid|device_id)["']?\s*[=:]\s*["']?)[^\s&"',;]+""", RegexOption.IGNORE_CASE)
	private val AUTH_HEADER = Regex("""(authorization\s*[:=]\s*).*""", RegexOption.IGNORE_CASE)
	private val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")

	/** Removes server addresses, IP addresses and tokens from text before it's sent. */
	internal fun scrub(text: String): String = text
		.replace(AUTH_HEADER) { it.groupValues[1] + "[removed]" }
		.replace(SECRET_PARAM) { it.groupValues[1] + "[removed]" }
		.replace(URL_HOST) { it.groupValues[1] + "://[server]" }
		.replace(IPV4, "[ip]")
}

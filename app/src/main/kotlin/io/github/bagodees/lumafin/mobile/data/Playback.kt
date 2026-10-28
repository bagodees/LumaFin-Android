package io.github.bagodees.lumafin.mobile.data

import android.media.MediaCodecList
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.deviceprofile.DeviceProfileBuilder
import org.jellyfin.sdk.model.deviceprofile.buildDeviceProfile

/** What to play and how the server is sending it. */
data class PlaybackSource(
	val url: String,
	val playSessionId: String?,
	val mediaSourceId: String?,
	val method: PlayMethod,
	/** Subtitle files the player loads alongside the video: url, language, title, mime type. */
	val externalSubtitles: List<ExternalSubtitle>,
)

data class ExternalSubtitle(val url: String, val language: String?, val label: String, val mimeType: String)

private val audioCodecs = arrayOf("aac", "mp3", "mp2", "ac3", "eac3", "dts", "truehd", "mlp", "flac", "alac", "opus", "vorbis", "pcm_s16le", "pcm_s24le")

/** Whether a decoder handles 10-bit HEVC (HDR and most Dolby Vision files). */
private fun hasHevcMain10() = runCatching {
	MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }.any { codec ->
		codec.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) } &&
			codec.getCapabilitiesForType("video/hevc").profileLevels.any { it.profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
	}
}.getOrDefault(false)

private fun hasDecoder(mime: String) = runCatching {
	MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) } }
}.getOrDefault(false)

/**
 * What this phone or tablet can play as is. Video the device can't decode is converted by the
 * server; audio is decoded in the app (with the FFmpeg decoder), so surround tracks play everywhere.
 */
fun phoneDeviceProfile(maxBitrate: Int) = buildDeviceProfile {
	val videoCodecs = listOfNotNull(
		"h264",
		if (hasDecoder("video/hevc")) "hevc" else null,
		if (hasDecoder("video/x-vnd.on2.vp9")) "vp9" else null,
		if (hasDecoder("video/av01")) "av1" else null,
		"mpeg4",
	).toTypedArray()

	name = "LumaFin for Android"
	maxStaticBitrate = maxBitrate
	maxStreamingBitrate = maxBitrate

	transcodingProfile {
		type = DlnaProfileType.VIDEO
		context = EncodingContext.STREAMING
		container = "ts"
		protocol = MediaStreamProtocol.HLS
		videoCodec(*listOfNotNull(if ("hevc" in videoCodecs) "hevc" else null, "h264").toTypedArray())
		audioCodec("aac", "mp3", "ac3", "eac3")
		copyTimestamps = false
		enableSubtitlesInManifest = true
	}

	directPlayProfile {
		type = DlnaProfileType.VIDEO
		container("mp4", "m4v", "mkv", "webm", "mov", "ts", "avi")
		videoCodec(*videoCodecs)
		audioCodec(*audioCodecs)
	}

	directPlayProfile {
		type = DlnaProfileType.AUDIO
		audioCodec(*audioCodecs)
	}

	// 10-bit HEVC only where a decoder supports it; otherwise the server converts it
	if ("hevc" in videoCodecs && !hasHevcMain10()) codecProfile {
		type = CodecType.VIDEO
		codec = "hevc"
		conditions {
			ProfileConditionValue.VIDEO_PROFILE inCollection listOf("main")
		}
	}

	// Text subtitles load as files; picture subtitles are burned in by the server
	subtitleProfile("vtt", embedded = true, hls = true, external = true)
	subtitleProfile("webvtt", embedded = true, hls = true, external = true)
	subtitleProfile("srt", embedded = true, external = true)
	subtitleProfile("subrip", embedded = true, external = true)
	subtitleProfile("ttml", embedded = true, external = true)
	subtitleProfile("ass", embedded = true, encode = true)
	subtitleProfile("ssa", embedded = true, encode = true)
	subtitleProfile("pgs", embedded = true, encode = true)
	subtitleProfile("pgssub", embedded = true, encode = true)
	subtitleProfile("dvdsub", encode = true)
	subtitleProfile("dvbsub", encode = true)
}

/** Asks the server how to play [item] on this device; [convert] asks for a converted stream when playing it as is failed. */
suspend fun ApiClient.playbackSource(item: BaseItemDto, maxBitrate: Int = 40_000_000, convert: Boolean = false): PlaybackSource? {
	val info = mediaInfoApi.getPostedPlaybackInfo(
		itemId = item.id,
		data = PlaybackInfoDto(
			deviceProfile = phoneDeviceProfile(maxBitrate),
			maxStreamingBitrate = maxBitrate,
			enableDirectPlay = !convert,
			enableDirectStream = !convert,
			enableTranscoding = true,
			allowVideoStreamCopy = true,
			allowAudioStreamCopy = true,
			autoOpenLiveStream = false,
		),
	).content
	val source = info.mediaSources.firstOrNull { it.protocol == MediaProtocol.FILE && !it.isRemote } ?: info.mediaSources.firstOrNull() ?: return null
	val subtitles = source.mediaStreams.orEmpty()
		.filter { it.type == MediaStreamType.SUBTITLE && it.deliveryMethod == SubtitleDeliveryMethod.EXTERNAL }
		.mapNotNull { stream ->
			val format = if (stream.deliveryUrl?.endsWith(".srt", true) == true || stream.codec.equals("subrip", true) || stream.codec.equals("srt", true)) "srt" else "vtt"
			ExternalSubtitle(
				// The server gives each subtitle file's address in the stream list
				url = createUrl(stream.deliveryUrl ?: return@mapNotNull null, ignorePathParameters = true),
				language = stream.language,
				label = stream.displayTitle ?: stream.language ?: "Subtitles",
				mimeType = if (format == "srt") "application/x-subrip" else "text/vtt",
			)
		}
	return when {
		source.supportsDirectPlay -> PlaybackSource(
			url = videosApi.getVideoStreamUrl(itemId = item.id, container = source.container, mediaSourceId = source.id, static = true, tag = source.eTag),
			playSessionId = info.playSessionId,
			mediaSourceId = source.id,
			method = PlayMethod.DIRECT_PLAY,
			externalSubtitles = subtitles,
		)
		source.transcodingUrl != null -> PlaybackSource(
			url = createUrl(source.transcodingUrl!!, ignorePathParameters = true),
			playSessionId = info.playSessionId,
			mediaSourceId = source.id,
			method = if (source.supportsDirectStream) PlayMethod.DIRECT_STREAM else PlayMethod.TRANSCODE,
			externalSubtitles = subtitles,
		)
		else -> null
	}
}

/** One subtitle format and every way this app can take it. */
private fun DeviceProfileBuilder.subtitleProfile(
	format: String,
	embedded: Boolean = false,
	external: Boolean = false,
	hls: Boolean = false,
	encode: Boolean = false,
) {
	if (embedded) subtitleProfile(format, SubtitleDeliveryMethod.EMBED)
	if (external) subtitleProfile(format, SubtitleDeliveryMethod.EXTERNAL)
	if (hls) subtitleProfile(format, SubtitleDeliveryMethod.HLS)
	if (encode) subtitleProfile(format, SubtitleDeliveryMethod.ENCODE)
}

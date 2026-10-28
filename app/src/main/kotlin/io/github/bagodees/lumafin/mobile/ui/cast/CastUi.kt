package io.github.bagodees.lumafin.mobile.ui.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bagodees.lumafin.mobile.LumaFinApplication
import io.github.bagodees.lumafin.mobile.R
import io.github.bagodees.lumafin.mobile.data.CastDevice
import io.github.bagodees.lumafin.mobile.ui.theme.LumaFinColors
import org.jellyfin.sdk.model.api.BaseItemDto

/** "Play on" next to Play: picks a Chromecast or another Jellyfin app to play [item] on. */
@Composable
fun CastButton(application: LumaFinApplication, item: BaseItemDto) {
	var choosing by remember { mutableStateOf(false) }
	OutlinedButton(onClick = { choosing = true }) {
		Icon(painterResource(R.drawable.ic_cast), contentDescription = null, modifier = Modifier.size(18.dp))
		Text(stringResource(R.string.casting), modifier = Modifier.padding(start = 6.dp))
	}
	if (choosing) CastPicker(application, onPick = { device ->
		choosing = false
		application.sessions.api?.let { api -> application.cast.play(api, device, item) }
	}, onDismiss = { choosing = false })
}

@Composable
private fun CastPicker(application: LumaFinApplication, onPick: (CastDevice) -> Unit, onDismiss: () -> Unit) {
	val cast = application.cast
	val devices by cast.devices.collectAsState()
	DisposableEffect(Unit) {
		application.sessions.api?.let(cast::startDiscovery)
		onDispose { cast.stopDiscovery() }
	}
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.casting_choose_device)) },
		text = {
			Column {
				devices.forEach { device ->
					Row(
						Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(device) }.padding(vertical = 10.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Icon(painterResource(if (device is CastDevice.Chromecast) R.drawable.ic_cast else R.drawable.ic_tv), contentDescription = null)
						Column(Modifier.padding(start = 16.dp)) {
							Text(device.name, style = MaterialTheme.typography.titleSmall)
							Text(
								when (device) {
									is CastDevice.Chromecast -> stringResource(R.string.casting_chromecast)
									is CastDevice.Jellyfin -> device.app.orEmpty()
								},
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}
				}
				if (devices.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
					CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
					Text(stringResource(R.string.casting_searching), modifier = Modifier.padding(start = 12.dp))
				}
				// Help only while nothing is found, plus a note on devices without Chromecast support
				if (devices.isEmpty() || !cast.chromecastSupported) Text(
					stringResource(if (devices.isEmpty() && cast.chromecastSupported) R.string.casting_none else R.string.casting_no_chromecast),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(top = 12.dp),
				)
			}
		},
		confirmButton = {},
		dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
	)
}

private fun clock(ms: Long): String {
	val seconds = ms / 1000
	return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}

/** While casting: what's playing and where, with remote controls. Sits above the tabs on every page. */
@Composable
fun CastBar(application: LumaFinApplication, modifier: Modifier = Modifier) {
	val cast = application.cast
	val state by cast.state.collectAsState()
	val current = state ?: return
	Column(modifier.fillMaxWidth().background(LumaFinColors.Surface)) {
		if (current.durationMs > 0) Box(Modifier.fillMaxWidth().height(2.dp).background(LumaFinColors.Raised)) {
			Box(Modifier.fillMaxWidth((current.positionMs.toFloat() / current.durationMs).coerceIn(0f, 1f)).height(2.dp).background(LumaFinColors.Cyan))
		}
		Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
			Icon(painterResource(R.drawable.ic_cast), contentDescription = null, tint = LumaFinColors.Cyan)
			Column(Modifier.weight(1f).padding(start = 12.dp)) {
				Text(current.title ?: stringResource(R.string.casting_connected_to, current.device.name), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
				Text(
					listOfNotNull(
						stringResource(R.string.casting_playing_on, current.device.name),
						if (current.durationMs > 0) "${clock(current.positionMs)} / ${clock(current.durationMs)}" else null,
					).joinToString(" · "),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
			IconButton(onClick = { cast.seekBy(-10_000) }) { Icon(painterResource(R.drawable.ic_replay_10), contentDescription = stringResource(R.string.casting_back_10)) }
			IconButton(onClick = cast::togglePause) {
				Icon(painterResource(if (current.playing) R.drawable.ic_pause else R.drawable.ic_play), contentDescription = stringResource(R.string.casting_play_pause))
			}
			IconButton(onClick = { cast.seekBy(30_000) }) { Icon(painterResource(R.drawable.ic_forward_30), contentDescription = stringResource(R.string.casting_forward_30)) }
			IconButton(onClick = cast::stop) { Icon(painterResource(R.drawable.ic_stop), contentDescription = stringResource(R.string.casting_stop)) }
		}
	}
}

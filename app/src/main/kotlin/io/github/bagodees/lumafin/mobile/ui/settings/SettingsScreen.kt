package io.github.bagodees.lumafin.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bagodees.lumafin.mobile.BuildConfig
import io.github.bagodees.lumafin.mobile.LumaFinApplication
import io.github.bagodees.lumafin.mobile.R
import io.github.bagodees.lumafin.mobile.ui.theme.LumaFinColors
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(application: LumaFinApplication) {
	val session by application.sessions.session.collectAsState()
	val settings = application.settings
	val scope = rememberCoroutineScope()
	var seerrRows by remember { mutableStateOf(settings.seerrRows) }
	var autoplay by remember { mutableStateOf(settings.autoplayNext) }
	var wifiOnly by remember { mutableStateOf(settings.downloadWifiOnly) }
	Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
		Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
			Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
			Group {
				Text(stringResource(R.string.settings_signed_in_as, session?.userName.orEmpty()), style = MaterialTheme.typography.titleMedium)
				Text(
					"${stringResource(R.string.settings_server)}: ${session?.serverName.orEmpty()} (${session?.serverUrl.orEmpty()})",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(top = 4.dp),
				)
				OutlinedButton(onClick = { scope.launch { application.sessions.signOut() } }, modifier = Modifier.padding(top = 16.dp)) {
					Icon(painterResource(R.drawable.ic_logout), contentDescription = null)
					Text(stringResource(R.string.settings_sign_out), modifier = Modifier.padding(start = 8.dp))
				}
			}
			Group {
				Toggle(stringResource(R.string.settings_autoplay), stringResource(R.string.settings_autoplay_description), autoplay) {
					autoplay = it; settings.autoplayNext = it
				}
				Toggle(stringResource(R.string.settings_seerr), stringResource(R.string.settings_seerr_description), seerrRows) {
					seerrRows = it; settings.seerrRows = it
				}
				Toggle(stringResource(R.string.settings_download_wifi_only), stringResource(R.string.settings_download_wifi_only_description), wifiOnly) {
					wifiOnly = it; settings.downloadWifiOnly = it
				}
			}
			Text(
				stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(top = 16.dp),
			)
		}
	}
}

@Composable
private fun Group(content: @Composable () -> Unit) = Column(
	Modifier.padding(top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LumaFinColors.Surface).padding(20.dp),
) { content() }

@Composable
private fun Toggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
	Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
		Column(Modifier.weight(1f).padding(end = 16.dp)) {
			Text(title, style = MaterialTheme.typography.titleSmall)
			Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
		Switch(checked = checked, onCheckedChange = onChange)
	}
}

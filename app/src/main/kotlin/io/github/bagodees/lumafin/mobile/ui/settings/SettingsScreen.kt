package io.github.bagodees.lumafin.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
	val scope = rememberCoroutineScope()
	Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
		Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(24.dp)) {
			Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
			Column(Modifier.padding(top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LumaFinColors.Surface).padding(20.dp)) {
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
			Text(
				stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(top = 16.dp),
			)
		}
	}
}

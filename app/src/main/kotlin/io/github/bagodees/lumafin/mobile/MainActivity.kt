package io.github.bagodees.lumafin.mobile

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.bagodees.lumafin.mobile.ui.shell.MainShell
import io.github.bagodees.lumafin.mobile.ui.signin.SignInScreen
import io.github.bagodees.lumafin.mobile.ui.theme.LumaFinTheme

class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		// Light status and navigation bar icons on the always-dark app
		enableEdgeToEdge(
			statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
			navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
		)
		super.onCreate(savedInstanceState)
		val application = application as LumaFinApplication
		setContent {
			LumaFinTheme {
				// A surface sets the default text color, so plain Text is light on the dark background
				Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
					val session by application.sessions.session.collectAsState()
					if (session == null) SignInScreen() else MainShell(application)
				}
			}
		}
	}
}

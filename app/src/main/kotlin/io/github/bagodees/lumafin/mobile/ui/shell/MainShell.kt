package io.github.bagodees.lumafin.mobile.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bagodees.lumafin.mobile.LumaFinApplication
import io.github.bagodees.lumafin.mobile.R
import io.github.bagodees.lumafin.mobile.ui.common.WidthClass
import io.github.bagodees.lumafin.mobile.ui.common.currentWidthClass
import io.github.bagodees.lumafin.mobile.ui.home.HomeScreen
import io.github.bagodees.lumafin.mobile.ui.settings.SettingsScreen

enum class Destination(val label: Int, val icon: Int) {
	HOME(R.string.nav_home, R.drawable.ic_house),
	SEARCH(R.string.nav_search, R.drawable.ic_search),
	LIBRARIES(R.string.nav_libraries, R.drawable.ic_grid),
	SETTINGS(R.string.nav_settings, R.drawable.ic_settings),
}

/** Bottom tabs on a phone or folded foldable, a side rail on an unfolded foldable, the full side menu on a tablet. */
fun navigationFor(width: WidthClass) = when (width) {
	WidthClass.COMPACT -> NavigationSuiteType.NavigationBar
	WidthClass.MEDIUM -> NavigationSuiteType.NavigationRail
	WidthClass.EXPANDED -> NavigationSuiteType.NavigationDrawer
}

@Composable
fun MainShell(application: LumaFinApplication) {
	// Saved, so folding, unfolding or rotating keeps the open tab
	var selected by rememberSaveable { mutableStateOf(Destination.HOME) }

	NavigationSuiteScaffold(
		layoutType = navigationFor(currentWidthClass()),
		navigationSuiteItems = {
			Destination.entries.forEach { destination ->
				item(
					selected = destination == selected,
					onClick = { selected = destination },
					icon = { Icon(painterResource(destination.icon), contentDescription = null) },
					label = { Text(stringResource(destination.label)) },
				)
			}
		},
	) {
		when (selected) {
			Destination.HOME -> HomeScreen(application)
			Destination.SETTINGS -> SettingsScreen(application)
			else -> ComingSoon(stringResource(selected.label))
		}
	}
}

@Composable
private fun ComingSoon(title: String) {
	Box(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp), contentAlignment = Alignment.Center) {
		Text("$title\n\n" + stringResource(R.string.coming_soon), color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}

package io.github.bagodees.lumafin.mobile.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bagodees.lumafin.mobile.LumaFinApplication
import io.github.bagodees.lumafin.mobile.R
import io.github.bagodees.lumafin.mobile.ui.common.LocalNavigator
import io.github.bagodees.lumafin.mobile.ui.common.Navigator
import io.github.bagodees.lumafin.mobile.ui.common.Route
import io.github.bagodees.lumafin.mobile.ui.common.WidthClass
import io.github.bagodees.lumafin.mobile.ui.common.currentWidth
import io.github.bagodees.lumafin.mobile.ui.common.widthClassFor
import io.github.bagodees.lumafin.mobile.ui.details.DetailsScreen
import io.github.bagodees.lumafin.mobile.ui.home.HomeScreen
import io.github.bagodees.lumafin.mobile.ui.library.LibrariesScreen
import io.github.bagodees.lumafin.mobile.ui.library.LibraryScreen
import io.github.bagodees.lumafin.mobile.ui.search.PersonScreen
import io.github.bagodees.lumafin.mobile.ui.search.SearchScreen
import io.github.bagodees.lumafin.mobile.ui.seerr.SeerrScreen
import io.github.bagodees.lumafin.mobile.ui.settings.SettingsScreen

enum class Destination(val label: Int, val icon: Int) {
	HOME(R.string.nav_home, R.drawable.ic_house),
	SEARCH(R.string.nav_search, R.drawable.ic_search),
	LIBRARIES(R.string.nav_libraries, R.drawable.ic_grid),
	SETTINGS(R.string.nav_settings, R.drawable.ic_settings),
}

/**
 * Bottom tabs on a phone or folded foldable, a side rail on an unfolded foldable or a tablet held
 * upright, and the full side menu only on a wide tablet, where its 360dp still leaves room for Home.
 */
fun navigationFor(width: Dp) = when {
	width >= 1200.dp -> NavigationSuiteType.NavigationDrawer
	widthClassFor(width) == WidthClass.COMPACT -> NavigationSuiteType.NavigationBar
	else -> NavigationSuiteType.NavigationRail
}

@Composable
fun MainShell(application: LumaFinApplication) {
	// Saved, so folding, unfolding or rotating keeps the open tab and page
	var selected by rememberSaveable { mutableStateOf(Destination.HOME) }
	val navigator: Navigator = viewModel()
	BackHandler(enabled = navigator.stack.isNotEmpty()) { navigator.back() }
	// Back on another tab returns to Home before leaving the app
	BackHandler(enabled = navigator.stack.isEmpty() && selected != Destination.HOME) { selected = Destination.HOME }

	CompositionLocalProvider(LocalNavigator provides navigator) {
		NavigationSuiteScaffold(
			layoutType = navigationFor(currentWidth()),
			navigationSuiteItems = {
				Destination.entries.forEach { destination ->
					item(
						selected = destination == selected,
						onClick = {
							// Tapping the open tab again returns to its start
							navigator.clear()
							selected = destination
						},
						icon = { Icon(painterResource(destination.icon), contentDescription = null) },
						label = { Text(stringResource(destination.label)) },
					)
				}
			},
		) {
			when (val page = navigator.stack.lastOrNull()) {
				is Route.Item -> DetailsScreen(application, page)
				is Route.Library -> LibraryScreen(application, page)
				is Route.Seerr -> SeerrScreen(application, page)
				is Route.Person -> PersonScreen(application, page)
				null -> when (selected) {
					Destination.HOME -> HomeScreen(application)
					Destination.SEARCH -> SearchScreen(application)
					Destination.LIBRARIES -> LibrariesScreen(application)
					Destination.SETTINGS -> SettingsScreen(application)
				}
			}
		}
	}
}

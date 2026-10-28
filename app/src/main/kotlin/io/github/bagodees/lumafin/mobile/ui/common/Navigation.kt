package io.github.bagodees.lumafin.mobile.ui.common

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import java.util.UUID

/** Pages opened on top of a tab. */
sealed interface Route {
	data class Library(val id: UUID, val name: String, val collectionType: String?) : Route
	data class Item(val id: UUID) : Route
	data class Seerr(val mediaType: String, val mediaId: Int) : Route
	data class Person(val id: UUID, val name: String) : Route
}

/**
 * The pages opened from the current tab. Kept in a view model, so folding, unfolding and rotating
 * (which resize the screen without restarting it) never lose the page you're on.
 */
class Navigator : ViewModel() {
	val stack = mutableStateListOf<Route>()

	fun open(route: Route) {
		stack += route
	}

	fun back(): Boolean = stack.removeLastOrNull() != null

	fun clear() = stack.clear()
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No navigator") }

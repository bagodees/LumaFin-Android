package io.github.bagodees.lumafin.mobile

import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.ui.unit.dp
import io.github.bagodees.lumafin.mobile.ui.common.WidthClass
import io.github.bagodees.lumafin.mobile.ui.common.widthClassFor
import io.github.bagodees.lumafin.mobile.ui.shell.navigationFor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class WidthClassTests : FunSpec({
	test("phones and folded foldables are compact, unfolded foldables medium, tablets expanded") {
		widthClassFor(411.dp) shouldBe WidthClass.COMPACT // typical phone
		widthClassFor(599.dp) shouldBe WidthClass.COMPACT
		widthClassFor(600.dp) shouldBe WidthClass.MEDIUM
		widthClassFor(673.dp) shouldBe WidthClass.MEDIUM // book-style foldable, unfolded
		widthClassFor(800.dp) shouldBe WidthClass.MEDIUM // Fire HD 10 in portrait
		widthClassFor(840.dp) shouldBe WidthClass.EXPANDED
		widthClassFor(1280.dp) shouldBe WidthClass.EXPANDED // Fire HD 10 in landscape
	}
	test("navigation follows the width") {
		navigationFor(WidthClass.COMPACT) shouldBe NavigationSuiteType.NavigationBar
		navigationFor(WidthClass.MEDIUM) shouldBe NavigationSuiteType.NavigationRail
		navigationFor(WidthClass.EXPANDED) shouldBe NavigationSuiteType.NavigationDrawer
	}
})

plugins {
	alias(libs.plugins.android.library)
}

// Logic shared by the TV app and the phone, foldable and tablet app: no screens, only server data
android {
	namespace = "io.github.bagodees.lumafin.core"
	compileSdk = libs.versions.android.compileSdk.get().toInt()

	defaultConfig {
		minSdk = libs.versions.android.minSdk.get().toInt()
	}

	compileOptions {
		isCoreLibraryDesugaringEnabled = true
	}

	lint {
		lintConfig = file("$rootDir/android-lint.xml")
		abortOnError = false
	}

	testOptions.unitTests.all {
		it.useJUnitPlatform()
	}
}

dependencies {
	api(libs.jellyfin.sdk)
	implementation(libs.kotlinx.coroutines)
	implementation(libs.kotlinx.serialization.json)
	implementation(libs.timber)

	coreLibraryDesugaring(libs.android.desugar)

	testImplementation(libs.kotest.runner.junit5)
	testImplementation(libs.kotest.assertions)
}

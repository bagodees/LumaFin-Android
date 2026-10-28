plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.compose)
}

// LumaFin for phones, foldables and tablets: one app whose layout follows the screen width
android {
	namespace = "io.github.bagodees.lumafin.mobile"
	compileSdk = libs.versions.android.compileSdk.get().toInt()

	defaultConfig {
		// Fire HD tablets run Fire OS 7 or 8 (Android 9 or 11), so keep the shared minimum
		minSdk = libs.versions.android.minSdk.get().toInt()
		targetSdk = libs.versions.android.targetSdk.get().toInt()

		// The same app ID as the TV app: one Play Store and Appstore listing, with a separate build
		// for phones and tablets (it needs a touchscreen, so TVs never get it, and the TV build needs a TV)
		applicationId = "io.github.bagodees.lumafin"
		versionName = "1.2.0-alpha.1"
		// Version codes can't repeat across the two builds, so phone builds start at 1,000,000,000;
		// TV builds stay far below it (1.2.0 is 1,020,099). The last two digits are the alpha or beta
		// number and 99 for the final release, so 1.2.0-alpha.1 is 1,001,020,001 and 1.2.0 is 1,001,020,099.
		versionCode = phoneVersionCode(versionName!!)
	}

	buildFeatures {
		buildConfig = true
		compose = true
	}

	compileOptions {
		isCoreLibraryDesugaringEnabled = true
	}

	signingConfigs {
		val keystoreFile = getProperty("keystore.file")
		val keystorePassword = getProperty("keystore.password")
		val signingKeyAlias = getProperty("signing.key.alias")
		val signingKeyPassword = getProperty("signing.key.password")

		if (keystoreFile != null && keystorePassword != null && signingKeyAlias != null && signingKeyPassword != null) {
			create("release") {
				storeFile = file(keystoreFile)
				storePassword = keystorePassword
				keyAlias = signingKeyAlias
				keyPassword = signingKeyPassword
			}
		}
	}

	buildTypes {
		release {
			isMinifyEnabled = true
			isShrinkResources = true
			proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
			signingConfig = signingConfigs.findByName("release")
		}

		debug {
			// Installs next to a release build and next to the TV app's debug build
			applicationIdSuffix = ".mobile.debug"
			versionNameSuffix = "-debug"
		}
	}

	lint {
		lintConfig = file("$rootDir/android-lint.xml")
		abortOnError = false
	}

	testOptions.unitTests.all {
		it.useJUnitPlatform()
	}
}

fun phoneVersionCode(version: String): Int {
	val (major, minor, patch) = version.substringBefore('-').split('.').map { it.toInt() }
	val preRelease = version.substringAfter('-', "").substringAfterLast('.', "").toIntOrNull()
	require(preRelease == null || preRelease in 1..98) { "Alpha or beta number must be 1 to 98" }
	return 1_000_000_000 + major * 1_000_000 + minor * 10_000 + patch * 100 + (preRelease ?: 99)
}

base.archivesName.set("LumaFin-Mobile-v${android.defaultConfig.versionName}")

dependencies {
	implementation(projects.core)
	implementation(projects.design)
	implementation(libs.jellyfin.sdk)

	implementation(libs.kotlinx.coroutines)

	implementation(libs.androidx.core)
	implementation(libs.androidx.activity.compose)
	implementation(libs.bundles.androidx.lifecycle)
	implementation(libs.androidx.lifecycle.viewmodel.compose)
	implementation(libs.bundles.androidx.compose)
	implementation(libs.androidx.compose.material3)
	implementation(libs.androidx.compose.material3.navigation.suite)
	implementation(libs.androidx.compose.material3.adaptive)
	implementation(libs.bundles.coil)
	implementation(libs.androidx.window)

	// Video
	implementation(libs.androidx.media3.exoplayer)
	implementation(libs.androidx.media3.exoplayer.hls)
	implementation(libs.androidx.media3.ui)
	implementation(libs.jellyfin.androidx.media3.ffmpeg.decoder)

	implementation(libs.timber)
	// The Jellyfin SDK logs through SLF4J; this sends it to Timber
	implementation(libs.slf4j.timber)

	coreLibraryDesugaring(libs.android.desugar)

	testImplementation(libs.kotest.runner.junit5)
	testImplementation(libs.kotest.assertions)
}

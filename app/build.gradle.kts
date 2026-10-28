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

		applicationId = "io.github.bagodees.lumafin.mobile"
		versionName = "0.1.0"
		versionCode = 1
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
			// Installs next to a release build
			applicationIdSuffix = ".debug"
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

	implementation(libs.timber)
	// The Jellyfin SDK logs through SLF4J; this sends it to Timber
	implementation(libs.slf4j.timber)

	coreLibraryDesugaring(libs.android.desugar)

	testImplementation(libs.kotest.runner.junit5)
	testImplementation(libs.kotest.assertions)
}

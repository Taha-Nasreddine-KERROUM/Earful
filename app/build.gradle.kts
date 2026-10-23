import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(21)
}

configure<ApplicationExtension> {
    namespace = "app.earful"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "app.earful"
        minSdk = 24
        targetSdk = 36
        // Automatic builds pass -Pvc=<number> so every update has a higher version code.
        versionCode = (findProperty("vc") as String?)?.toInt() ?: 16
        versionName = (findProperty("vn") as String?) ?: "2.5"
        // Where the app looks for updates (owner/repo on GitHub). Filled in by the automatic
        // build; empty in a plain local build, which simply turns the updater off.
        buildConfigField("String", "UPDATE_REPO", "\"${System.getenv("GITHUB_REPOSITORY") ?: (findProperty("updateRepo") as String? ?: "")}\"")
        // Only ship English resources from libraries — keeps the APK small.
        androidResources.localeFilters += listOf("en")
    }

    // One fixed key for every build, so updates install over the app already on the phone.
    // Locally: signing/earful.keystore. On GitHub: restored from the EARFUL_KEYSTORE secret.
    val sharedKey = file(System.getenv("EARFUL_KEYSTORE") ?: "../signing/earful.keystore")
    signingConfigs {
        if (sharedKey.exists()) create("shared") {
            storeFile = sharedKey
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("shared") ?: signingConfigs.getByName("debug")
        }
    }

    // One APK per CPU type is unnecessary: the app has no native code.
    packaging {
        resources.excludes += listOf(
            "META-INF/*.version", "META-INF/LICENSE*", "META-INF/NOTICE*",
            "kotlin/**", "DebugProbesKt.bin", "**/*.proto", "META-INF/**/*.kotlin_builtins"
        )
    }

    compileOptions {
        // NewPipeExtractor uses java.time / java.nio, which older Android versions need desugared.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // YouTube data (search, home shelves, audio stream URLs) — no Google account, no ads.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:e8f89145a830b4d6cae32412007f24ea8b36c73a")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    // Playback
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")

    // UI — Compose foundation only (no Material), our own design system on top
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.palette:palette:1.1.0")

    // Networking + images
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val localProps = Properties()
rootProject.file("local.properties").takeIf { it.exists() }
    ?.inputStream()?.use { localProps.load(it) }

android {
    namespace = "com.rhamers.osse"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.rhamers.osse"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ARM64 only — personal device target (see ADR-006)
        ndk {
            abiFilters += "arm64-v8a"
        }

        buildConfigField("String", "PODCAST_INDEX_API_KEY",    "\"${localProps["podcastindex.apiKey"]    ?: ""}\"")
        buildConfigField("String", "PODCAST_INDEX_API_SECRET", "\"${localProps["podcastindex.apiSecret"] ?: ""}\"")
    }

    // Release signing: reads from local.properties locally, env vars in CI.
    // Absent both (e.g. a fresh clone), the release build falls back to unsigned.
    val keystorePath = localProps.getProperty("keystore.path") ?: System.getenv("KEYSTORE_PATH")
    if (keystorePath != null) {
        val storePass = localProps.getProperty("keystore.password") ?: System.getenv("KEYSTORE_PASSWORD")
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = storePass
                keyAlias = localProps.getProperty("keystore.alias") ?: System.getenv("KEYSTORE_ALIAS")
                keyPassword = localProps.getProperty("keystore.keyPassword")
                    ?: System.getenv("KEYSTORE_KEY_PASSWORD")
                    ?: storePass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "FULL"
            }
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // x86_64 emulator support for development (release stays arm64-only per ADR-006)
            ndk {
                abiFilters += "x86_64"
            }
        }
        // Dedicated build type for connectedAndroidTest (see testBuildType below). Gradle's
        // AGP always uninstalls the app it instrumented once a connected test run finishes —
        // that's normal, non-optional cleanup, not a bug. Giving this variant its own
        // applicationId means that cleanup uninstall can never land on the "debug" package
        // installed for everyday personal use, even if a real phone (not the test emulator)
        // happens to be the connected device when tests run.
        create("instrumented") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".instrumented"
            signingConfig = signingConfigs.getByName("debug")
            ndk {
                abiFilters += "x86_64"
            }
        }
    }

    testBuildType = "instrumented"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        getByName("test") {
            kotlin.srcDir("src/sharedTest/java")
        }
        getByName("androidTest") {
            kotlin.srcDir("src/sharedTest/java")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

tasks.register("testAll") {
    group = "verification"
    description = "Runs unit tests (Robolectric) and instrumented tests on a connected device"
    dependsOn("test", "connectedAndroidTest")
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // "instrumented" is a near-clone of "debug" (see buildTypes) used only so
    // connectedAndroidTest's app-under-test is never the daily-use debug install; it needs
    // the same test-only manifest (hosts the empty activity Compose UI tests launch into)
    // debugImplementation provides for the debug variant, since that config doesn't apply here.
    "instrumentedImplementation"(libs.androidx.compose.ui.tooling)
    "instrumentedImplementation"(libs.androidx.compose.ui.test.manifest)

    // Media3 / ExoPlayer (see ADR-001)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)

    // Room (see ADR-005)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Image loading
    implementation(libs.coil.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Drag-to-reorder
    implementation(libs.reorderable)

    // QR code generation (release-share screen)
    implementation(libs.qrcode.kotlin)

    // NewPipeExtractor (see ADR-009 — replaces the ytaudio gomobile AAR, ADR-002/003)
    implementation(libs.newpipe.extractor)
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ABI split. F-Droid has no native support for split APKs — it ships one APK per ABI,
// each needing its own versionCode, ordered armeabi-v7a < arm64-v8a < x86 < x86_64 so
// clients resolve the right one. These offsets mirror the `VercodeOperation` entries in
// fdroid/app.mmmap.yml (10 * <base versionCode> + N).
val abiVersionCodeOffsets = mapOf(
    "armeabi-v7a" to 1,
    "arm64-v8a" to 2,
    "x86" to 3,
    "x86_64" to 4,
)

// -PabiFilter=<abi> produces a single-ABI APK; that is how each F-Droid build block is
// invoked (via gradleprops). Without the property the build stays unsplit — all four ABIs
// in one APK — so debug builds, `just install` and `just run` behave exactly as before.
val abiFilter = (findProperty("abiFilter") as? String)?.takeIf { it.isNotBlank() }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "app.mmmap"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.mmmap"
        minSdk = 26
        targetSdk = 35
        // Hardcoded so fdroidserver's regex parser can extract the version at each
        // tagged commit (its checkupdates step doesn't read gradle.properties or
        // evaluate findProperty). Bumped by `just bump-version <X.Y>`.
        versionCode = 10600
        versionName = "1.6"
        // -PversionCode / -PversionName still override (ad-hoc local builds);
        // the release workflow passes these but they match the hardcoded values.
        (findProperty("versionCode") as? String)?.toInt()?.let { versionCode = it }
        (findProperty("versionName") as? String)?.let { versionName = it }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    lint {
        disable += "NullSafeMutableLiveData"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            enableUnitTestCoverage = true
        }
    }

    splits {
        abi {
            isEnable = abiFilter != null
            reset()
            abiFilter?.let { include(it) }
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Store the gzipped SQLite seed as-is. AssetManager can't stream a *compressed*
        // asset larger than ~1 MB, and re-deflating an already-gzipped file gains nothing
        // anyway — we inflate it ourselves in DatabaseModule.
        noCompress += "seed"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE-notice.md"
        }
        // Store .so files uncompressed with 16KB-aligned offsets (Android 15+)
        jniLibs {
            useLegacyPackaging = false
        }
    }

    // AGP 8+ embeds a "Dependency metadata" signing block in release APKs
    // (intended for Play Console SBOM tracking). F-Droid's scanner rejects
    // unknown signing blocks, so disable both forms.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// Stamp each output with `10 * <base versionCode> + <ABI offset>`. An unsplit build has no
// ABI filter and gets offset 0, keeping it below every per-ABI APK of the same release.
// Reading defaultConfig here also picks up any -PversionCode override applied above.
androidComponents {
    onVariants { variant ->
        val base = android.defaultConfig.versionCode!!
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == ABI }?.identifier
            output.versionCode.set(base * 10 + (abiVersionCodeOffsets[abi] ?: 0))
        }
    }
}

kotlin {
    // Set the Kotlin compile target to 17 *without* requesting a toolchain.
    // jvmToolchain(17) would force Gradle to locate/provision a JDK 17, which
    // fails on F-Droid's build server (auto-provisioning disabled). Using
    // compilerOptions lets Kotlin compile to 17 bytecode with whatever JDK
    // is running Gradle — JDK 17 is required by compileOptions above anyway.
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.work.compiler)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // Coil
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // MapLibre
    implementation(libs.maplibre)

    // Coroutines
    implementation(libs.coroutines.android)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.okhttp.mockwebserver)
}

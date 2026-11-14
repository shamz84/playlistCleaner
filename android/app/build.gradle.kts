import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Secrets come only from the untracked android/local.properties or the environment.
// Nothing secret is ever committed; every value defaults to empty/absent.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun secret(propertyKey: String, envKey: String): String? =
    localProperties.getProperty(propertyKey)?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(envKey).orNull?.takeIf { it.isNotBlank() }

fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val driveApiKey: String = secret("drive.apiKey", "DRIVE_API_KEY").orEmpty()
// TMDB v3 key for the Trending rows (U18); empty hides Trending.
val tmdbApiKey: String = secret("tmdb.apiKey", "TMDB_API_KEY").orEmpty()

val releaseStoreFile = secret("release.storeFile", "RELEASE_STORE_FILE")
val releaseStorePassword = secret("release.storePassword", "RELEASE_STORE_PASSWORD")
val releaseKeyAlias = secret("release.keyAlias", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = secret("release.keyPassword", "RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { it != null }
val fireOs6Test = providers.gradleProperty("fireOs6Test").map(String::toBoolean).getOrElse(false)
if (fireOs6Test) {
    require(gradle.startParameter.taskNames.none { it.contains("release", ignoreCase = true) }) {
        "The Fire OS 6 compatibility configuration is for debug builds only."
    }
}

android {
    namespace = "app.playlistplayer"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.playlistplayer"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 8
        versionName = "0.1.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "DRIVE_API_KEY", driveApiKey.asBuildConfigString())
        buildConfigField("String", "TMDB_API_KEY", tmdbApiKey.asBuildConfigString())
    }

    signingConfigs {
        getByName("debug") {
            // Fire OS guidance: v1 (JAR) + v2. Note AGP only emits v1 when minSdk < 24
            // (IncrementalPackagerBuilder: `minSdk < 24 && isV1SigningEnabled`), so with
            // minSdk 25 the APK carries v2 only; every API 24+ device verifies v2.
            enableV1Signing = true
            enableV2Signing = true
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        // No applicationIdSuffix on debug: the Drive API key is restricted to the
        // app.playlistplayer package name plus signing SHA-1.
        getByName("debug") {
            if (fireOs6Test) {
                applicationIdSuffix = ".fireos6test"
                resValue("string", "app_name", "8TV Player Test")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = fireOs6Test
    }

    packaging {
        // Keep native libraries (sqlite-bundled, Media3 extensions) stored uncompressed and
        // page-aligned in the APK. This is the AGP default for minSdk >= 23; stated explicitly
        // because Fire OS 8 guidance requires uncompressed, aligned content. resources.arsc is
        // always stored uncompressed and 4-byte aligned by AGP, and zipalign runs by default.
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric 4.17 on the SDK 36 runtime needs jdk.internal.access on JDK 17+
            // (AndroidInterceptors$FileDescriptorInterceptor via ApplicationSharedMemory).
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core:model"))
    implementation(project(":core:parsing"))
    implementation(project(":core:consolidation"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    implementation(project(":core:player"))
    implementation(project(":core:sync"))
    implementation(libs.androidx.media3.ui)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.profileinstaller)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    // Mobile UI tree
    implementation(libs.androidx.compose.material3)
    // TV UI tree
    implementation(libs.androidx.tv.material)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)
    // Compose UI tests on Robolectric (createComposeRule needs the test manifest's activity).
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.bundles.unit.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // Playlist repository/onboarder tests (U15) use in-memory Room and a DataStore-backed SecureStore.
    testImplementation(libs.androidx.sqlite.bundled.jvm)
    testImplementation(libs.androidx.datastore.preferences)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}

// Unit tests run on the host JVM: swap the Android-only bundled SQLite AAR (device .so files) for
// its JVM variant, which carries desktop natives (same classes).
configurations.configureEach {
    if (name.endsWith("UnitTestRuntimeClasspath")) {
        exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
    }
}

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.playlistplayer.core.player"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time / java.util.Base64 are API 26; desugaring backs them on API 25 (Fire OS 6).
        isCoreLibraryDesugaringEnabled = true
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
    api(project(":core:model"))
    // DeviceCapabilitiesProbe returns consolidation's DeviceCapabilities; PlayableSource carries QualityTier.
    api(project(":core:consolidation"))
    implementation(project(":core:network"))
    api(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.datasource.okhttp)
    // PlayerFactory accepts a shared OkHttpClient; pins the catalog version over the transitive 4.x.
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.android)

    testImplementation(libs.bundles.unit.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.media3.test.utils)
    testImplementation(libs.androidx.media3.test.utils.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}

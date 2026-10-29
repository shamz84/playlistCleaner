plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.playlistplayer.core.sync"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

// Unit tests run on the host JVM: swap the Android-only bundled SQLite AAR (device .so files) for
// its JVM variant, which carries desktop natives (same classes).
configurations.configureEach {
    if (name.endsWith("UnitTestRuntimeClasspath")) {
        exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
    }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:parsing"))
    implementation(project(":core:consolidation"))
    // PlaylistSyncer's public API exposes PlaylistStore, PlaylistSourceFactory and RedactingLogger.
    api(project(":core:network"))
    api(project(":core:database"))
    implementation(libs.kotlinx.serialization.json)
    api(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.bundles.unit.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.sqlite.bundled.jvm)
}

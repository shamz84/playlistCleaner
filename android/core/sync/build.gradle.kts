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

dependencies {
    api(project(":core:model"))
    implementation(project(":core:parsing"))
    implementation(project(":core:consolidation"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))
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
}

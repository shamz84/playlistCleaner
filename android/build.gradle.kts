// Top-level build file. Plugins are declared here (apply false) so every module shares
// one classpath and one version of each plugin.
//
// AGP 9 has built-in Kotlin support for Android modules, so org.jetbrains.kotlin.android
// is not applied anywhere. Declaring kotlin-jvm here puts KGP (the catalog's `kotlin` version)
// on the build classpath, which AGP's built-in Kotlin then uses instead of its bundled
// default KGP version.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room3) apply false
}

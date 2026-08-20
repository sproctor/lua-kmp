// The root project builds nothing. It declares the plugin versions once so the
// modules can apply them without repeating a version, which also keeps Gradle
// from having to reconcile two versioned requests for the same AGP artifact
// (com.android.library and com.android.kotlin.multiplatform.library ship in it).
plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.mavenPublish) apply false
}

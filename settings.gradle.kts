pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "lua-kmp"

include(":lua-kmp")
include(":sample")

// The JNI module is a plain com.android.library and cannot configure at all
// without an SDK, so leave it out when there isn't one. build.gradle.kts runs
// the same test to decide whether to enable its android target, so a checkout
// either has both or neither.
val androidSdkAvailable: Boolean =
    run {
        val localProps = settingsDir.resolve("local.properties")
        val fromLocalProps =
            if (localProps.exists()) {
                java.util.Properties().apply { localProps.inputStream().use(::load) }.getProperty("sdk.dir")
            } else {
                null
            }
        (fromLocalProps ?: System.getenv("ANDROID_HOME"))?.let(::File)?.isDirectory == true
    }
if (androidSdkAvailable) {
    include(":android-jni")
}

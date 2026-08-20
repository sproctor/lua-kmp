plugins {
    // Versionless: both are declared with their versions in the root project.
    id("com.android.library")
    id("com.vanniktech.maven.publish")
}

// ---------------------------------------------------------------------------
// This module exists for one reason: com.android.kotlin.multiplatform.library,
// which the root project uses, has no externalNativeBuild. Google's guidance is
// to keep the CMake build in a standalone com.android.library and depend on it
// from androidMain, so that is all this is - an AAR with no Kotlin or Java in
// it, carrying libluakmp.so for each ABI. The JNI entry points are resolved by
// name at runtime (Java_com_seanproctor_lua_LuaJni_*), so nothing here needs to
// know about the Kotlin class that calls them.
//
// It ships as its own Maven coordinate because :lua-kmp's androidMain depends
// on it; both take their version from gradle.properties and release together.
// ---------------------------------------------------------------------------

android {
    namespace = "com.seanproctor.lua.jni"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()
    defaultConfig {
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates(group.toString(), "lua-kmp-android-jni", version.toString())
    pom {
        name.set("lua-kmp-android-jni")
        description.set("Android JNI binaries for lua-kmp")
        url.set("https://github.com/sproctor/lua-kmp")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/license/mit/")
            }
        }
        developers {
            developer {
                id.set("sproctor")
                name.set("Sean Proctor")
                email.set("sproctor@gmail.com")
            }
        }
        scm {
            url.set("https://github.com/sproctor/lua-kmp")
            connection.set("scm:git:git://github.com/sproctor/lua-kmp.git")
            developerConnection.set("scm:git:ssh://git@github.com/sproctor/lua-kmp.git")
        }
    }
}

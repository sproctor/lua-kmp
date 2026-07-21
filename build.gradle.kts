import java.util.Properties
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.mavenPublish)
}

group = "com.seanproctor"
version = "0.1.0-SNAPSHOT"

// ---------------------------------------------------------------------------
// The Android target needs an SDK; enable it only where one is available so
// desktop-only checkouts still configure. CI always has an SDK.
// ---------------------------------------------------------------------------
val androidSdkDir: File? = run {
    val localProps = file("local.properties")
    val fromLocalProps = if (localProps.exists()) {
        Properties().apply { localProps.inputStream().use(::load) }.getProperty("sdk.dir")
    } else null
    (fromLocalProps ?: System.getenv("ANDROID_HOME"))?.let(::File)?.takeIf(File::isDirectory)
}
val androidEnabled = androidSdkDir != null
if (androidEnabled) {
    apply(plugin = libs.plugins.androidLibrary.get().pluginId)
} else {
    logger.lifecycle("lua-kmp: no Android SDK found (ANDROID_HOME or local.properties sdk.dir); androidTarget disabled")
}

kotlin {
    explicitApi()

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    if (androidEnabled) {
        androidTarget {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_11)
            }
            publishLibraryVariants("release")
            // commonTest must run against the packaged .so, which only loads on a
            // device/emulator: route it to instrumented tests, not host unit tests.
            @OptIn(ExperimentalKotlinGradlePluginApi::class)
            instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)
            @OptIn(ExperimentalKotlinGradlePluginApi::class)
            unitTestVariant.sourceSetTree.set(KotlinSourceSetTree.unitTest)
        }
    }

    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    macosX64()
    linuxX64()
    mingwX64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        // JNI Kotlin shared by the desktop JVM and Android; the leaves differ
        // only in how the native library is loaded.
        val jvmShared = create("jvmShared") {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmShared)
        if (androidEnabled) {
            getByName("androidMain").dependsOn(jvmShared)
            getByName("androidInstrumentedTest").dependencies {
                implementation(libs.androidx.test.runner)
            }
        }
    }

    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.named("main") {
            cinterops.register("lua") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/lua.def"))
                includeDirs(project.file("native/lua/src"), project.file("native/shim"))
            }
        }
    }
}

if (androidEnabled) {
    extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
        namespace = "com.seanproctor.lua"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        ndkVersion = libs.versions.android.ndk.get()
        defaultConfig {
            minSdk = libs.versions.android.minSdk.get().toInt()
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
        externalNativeBuild {
            cmake {
                path = file("src/androidMain/cpp/CMakeLists.txt")
            }
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }
}

// ---------------------------------------------------------------------------
// JVM backend: compile the vendored Lua + shims into a host JNI library and
// stage it under native/<os>-<arch>/ in the jvm jar's resources. CI runs this
// on each OS and merges the staged outputs before publishing (see ci.yml).
// ---------------------------------------------------------------------------
val jniStaging: Provider<Directory> = layout.buildDirectory.dir("jniStaging")

data class JniHostSpec(val key: String, val libName: String, val compilerArgs: List<String>, val linkerArgs: List<String>)

val hostOs: OperatingSystem = OperatingSystem.current()
val hostArch: String = when (val a = System.getProperty("os.arch").lowercase()) {
    "amd64", "x86_64" -> "x86_64"
    "aarch64", "arm64" -> "aarch64"
    else -> a
}

val jniHostSpecs: List<JniHostSpec> = when {
    hostOs.isLinux -> listOf(
        JniHostSpec("linux-$hostArch", "libluakmp.so", listOf("-fPIC", "-shared", "-DLUA_USE_LINUX"), listOf("-lm", "-ldl"))
    )
    hostOs.isMacOsX -> listOf(
        JniHostSpec("macos-aarch64", "libluakmp.dylib", listOf("-dynamiclib", "-arch", "arm64", "-DLUA_USE_MACOSX"), emptyList()),
        JniHostSpec("macos-x86_64", "libluakmp.dylib", listOf("-dynamiclib", "-arch", "x86_64", "-DLUA_USE_MACOSX"), emptyList()),
    )
    else -> listOf(
        JniHostSpec("windows-x86_64", "luakmp.dll", listOf("-shared"), listOf("-lm"))
    )
}

fun javaIncludeDirs(): List<File> {
    val javaHome = File(System.getProperty("java.home"))
    val include = javaHome.resolve("include")
    val platformDir = when {
        hostOs.isMacOsX -> "darwin"
        hostOs.isWindows -> "win32"
        else -> "linux"
    }
    return listOf(include, include.resolve(platformDir))
}

val jniSources = listOf(
    file("native/shim/lua_all.c"),
    file("native/shim/lua_shim.c"),
    file("native/jni/lua_jni.c"),
)

val compileJvmJniTasks: List<TaskProvider<Exec>> = jniHostSpecs.map { spec ->
    val taskName = "compileJvmJni" + spec.key.split("-").joinToString("") { part ->
        part.replaceFirstChar(Char::uppercase)
    }
    tasks.register<Exec>(taskName) {
        group = "build"
        description = "Compiles the JVM JNI native library for ${spec.key}"
        val outFile = jniStaging.get().dir("native/${spec.key}").file(spec.libName).asFile
        inputs.dir("native/lua/src")
        inputs.dir("native/shim")
        inputs.dir("native/jni")
        outputs.file(outFile)
        doFirst { outFile.parentFile.mkdirs() }
        val cc = System.getenv("CC") ?: if (hostOs.isWindows) "gcc" else "cc"
        commandLine(buildList {
            add(cc)
            add("-O2")
            add("-std=c99")
            add("-fvisibility=hidden")
            addAll(spec.compilerArgs)
            add("-I${file("native/lua/src").absolutePath}")
            add("-I${file("native/shim").absolutePath}")
            javaIncludeDirs().forEach { add("-I${it.absolutePath}") }
            jniSources.forEach { add(it.absolutePath) }
            add("-o")
            add(outFile.absolutePath)
            addAll(spec.linkerArgs)
        })
    }
}

kotlin.sourceSets.named("jvmMain") {
    resources.srcDir(jniStaging)
}
tasks.named("jvmProcessResources") {
    dependsOn(compileJvmJniTasks)
}

// ---------------------------------------------------------------------------
// Publishing: Maven Central portal, per-target artifacts under
// com.seanproctor:lua-kmp*. Signing activates only when keys are configured.
// ---------------------------------------------------------------------------
mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates(group.toString(), "lua-kmp", version.toString())
    pom {
        name.set("lua-kmp")
        description.set("Kotlin Multiplatform bindings for the reference Lua 5.5 interpreter")
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

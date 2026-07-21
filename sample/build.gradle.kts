import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    linuxX64()
    macosArm64()
    macosX64()
    mingwX64()

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.executable {
            entryPoint = "com.seanproctor.lua.sample.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":"))
        }
    }
}

// The KMP jvm target has no application-plugin integration; wire up a plain
// JavaExec over the jvm compilation instead.
val jvmMainCompilation = kotlin.jvm().compilations.getByName("main")
tasks.register<JavaExec>("runJvm") {
    group = "application"
    description = "Runs the sample on the JVM"
    mainClass.set("com.seanproctor.lua.sample.MainKt")
    classpath(jvmMainCompilation.output.allOutputs, configurations.named("jvmRuntimeClasspath"))
}

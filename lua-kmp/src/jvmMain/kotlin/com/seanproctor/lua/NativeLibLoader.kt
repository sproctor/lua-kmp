package com.seanproctor.lua

import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal actual fun loadLuaNative() {
    NativeLibLoader.load()
}

/**
 * Extracts the bundled JNI library for the current os/arch from the jar's
 * resources to a temp file and System.loads it. Fails with a clear message
 * when no binary is bundled for this platform.
 */
internal object NativeLibLoader {

    @Volatile
    private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return

        val osName = System.getProperty("os.name").lowercase()
        val os = when {
            "mac" in osName || "darwin" in osName -> "macos"
            "win" in osName -> "windows"
            "linux" in osName -> "linux"
            else -> throw UnsatisfiedLinkError("lua-kmp: unsupported operating system: $osName")
        }
        val archName = System.getProperty("os.arch").lowercase()
        val arch = when (archName) {
            "amd64", "x86_64", "x86-64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> throw UnsatisfiedLinkError("lua-kmp: unsupported architecture: $archName")
        }
        val libName = when (os) {
            "windows" -> "luakmp.dll"
            "macos" -> "libluakmp.dylib"
            else -> "libluakmp.so"
        }

        val resource = "/native/$os-$arch/$libName"
        val stream = NativeLibLoader::class.java.getResourceAsStream(resource)
            ?: throw UnsatisfiedLinkError(
                "lua-kmp: no bundled native library for $os-$arch (missing resource $resource)"
            )
        val tempFile = Files.createTempFile("luakmp-", "-$libName")
        stream.use { Files.copy(it, tempFile, StandardCopyOption.REPLACE_EXISTING) }
        tempFile.toFile().deleteOnExit()
        System.load(tempFile.toAbsolutePath().toString())
        loaded = true
    }
}

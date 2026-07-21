# lua-kmp sample

A tour of the library's API — eval, globals, live table handles, calling Lua
from Kotlin, host functions (including from inside Lua coroutines),
sandboxing, and error mapping. The same `commonMain` code runs on every
target, and prints identical output on each backend.

```sh
# On the JVM
./gradlew :sample:runJvm

# As a native executable (pick your host's target)
./gradlew :sample:runDebugExecutableLinuxX64
./gradlew :sample:runDebugExecutableMacosArm64
./gradlew :sample:runDebugExecutableMacosX64
./gradlew :sample:runDebugExecutableMingwX64
```

The demo lives in
[`src/commonMain/kotlin/com/seanproctor/lua/sample/Main.kt`](src/commonMain/kotlin/com/seanproctor/lua/sample/Main.kt).

# lua-kmp — Implementation Spec

A Kotlin Multiplatform library that embeds the reference Lua 5.5 interpreter and exposes
an idiomatic Kotlin API across JVM, Android, and Apple/Linux/Windows native targets.

The single most important design constraint: **one interpreter, one language version, on every
target.** We vendor the reference Lua 5.5 C sources and bind them via cinterop on Kotlin/Native
and via JNI on the JVM. We do not use luaj, luajava, or any pure-Kotlin Lua port — those either
lag the language version (luaj is 5.2-era) or can't reach iOS. Building the same C sources
everywhere guarantees identical semantics across platforms.

---

## 1. Goals and non-goals

Goals
- Embed Lua 5.5 (reference implementation) and run scripts on JVM, Android, and native targets.
- A common Kotlin API surface (`commonMain`) with platform `actual` backends.
- Register Kotlin functions callable from Lua, and call Lua functions from Kotlin.
- Marshal the common value types both directions: nil, boolean, integer, float, string, table.
- Opt-in sandboxing: open a chosen subset of the standard library (e.g. omit `io`/`os`).
- Publish to Maven Central under `com.seanproctor` with per-target artifacts.

Non-goals (v1)
- No LuaJIT (it cannot follow 5.5 semantics).
- No coroutine bridging between Lua coroutines and Kotlin coroutines (Lua coroutines still work
  inside scripts; we just don't expose a Kotlin-suspend bridge in v1). Track as a later phase.
- No automatic reflective Kotlin/Java object binding (à la luajava). Host functions are explicit.
- No `kotlin-js`/`wasm` targets in v1 (would require a separate pure-Kotlin interpreter; out of scope).

---

## 2. Naming and coordinates

- Repository / Gradle root project: `lua-kmp`
- Maven group: `com.seanproctor`
- Base artifact: `lua-kmp` (KMP publishes `lua-kmp`, `lua-kmp-jvm`, `lua-kmp-iosarm64`, … automatically)
- Root package: `com.seanproctor.lua`
- License: MIT (Lua itself is MIT; bundle Lua's license and copyright notice in all distributions).

Do not name the repo `kotlin-lua` (an unrelated Kotlin-Lua-VM repo already exists) or `luakt`
(an existing luaj-based binding). `lua-kmp` is clear in both the GitHub and Maven namespaces and
advertises the differentiator: multiplatform including iOS.

---

## 3. Target platforms

Kotlin/Native (cinterop backend):
- `iosArm64`, `iosSimulatorArm64`
- `macosArm64`, `macosX64`
- `linuxX64`
- `mingwX64`

JVM (JNI backend):
- `jvm` — native libraries bundled in the jar for macOS (arm64, x64), Linux (x64, arm64),
  Windows (x64), extracted and loaded at runtime.

Android (JNI backend):
- `androidTarget` — native libraries built by the NDK and packaged into the AAR
  (`arm64-v8a`, `armeabi-v7a`, `x86_64`).

Pin the Lua version at `5.5.0` initially. Provide a re-vendor script and a follow-up task to move
to `5.5.1` once it leaves release-candidate.

---

## 4. Architecture

```
commonMain
  public API:   LuaState (interface), LuaConfig, LuaValue, LuaException hierarchy,
                LuaFunction (host-callback SAM), StdLib (sandbox selection enum)
  internal:     expect fun createLuaState(config: LuaConfig): LuaState
                shared handler-registry contract, shared value-marshalling contract

nativeMain (shared across all KN targets)
  actual createLuaState -> NativeLuaState
  cinterop bindings to vendored Lua 5.5 + C shim
  callback dispatch via staticCFunction + StableRef registry + __gc cleanup

jvmMain
  actual createLuaState -> JvmLuaState
  `external fun` declarations -> JNI shim (lua_jni.c) over the same vendored Lua 5.5
  native-library extraction + System.load

androidMain
  actual createLuaState -> AndroidLuaState (shares JNI Kotlin code with jvmMain via a
  jvmShared source set; differs only in library loading: System.loadLibrary)
```

Both backends compile the *same* vendored C sources. The only per-backend C code is the thin
binding layer (cinterop shim vs JNI shim).

Source-set hierarchy:
- `commonMain` → `nativeMain` (intermediate, holds all cinterop actuals) → each KN target
- `commonMain` → `jvmShared` → `jvmMain`, `androidMain` (shared JNI Kotlin, per-loader specialization)

---

## 5. Repository layout

```
lua-kmp/
  build.gradle.kts
  settings.gradle.kts
  gradle/libs.versions.toml
  native/
    lua/                     # vendored Lua 5.5.0 C sources (unmodified), + LICENSE, + VERSION
    shim/
      lua_shim.h             # extern wrappers for Lua macros (used by cinterop)
      lua_shim.c
    jni/
      lua_jni.c              # JNI entry points over Lua + shim
    revendor.sh              # downloads + verifies a pinned Lua release into native/lua
  src/
    commonMain/kotlin/com/seanproctor/lua/
      LuaState.kt
      LuaConfig.kt
      LuaValue.kt
      LuaException.kt
      StdLib.kt
      internal/HandlerRegistry.kt
    commonTest/kotlin/...    # the cross-platform conformance/behaviour suite
    nativeMain/kotlin/com/seanproctor/lua/
      NativeLuaState.kt
      internal/Dispatch.kt   # staticCFunction dispatcher, StableRef pinning
    nativeInterop/cinterop/lua.def
    jvmShared/kotlin/com/seanproctor/lua/
      JniLuaState.kt         # external fun declarations + marshalling
    jvmMain/kotlin/...       # JvmLuaState (resource extraction + System.load)
    androidMain/kotlin/...   # AndroidLuaState (System.loadLibrary)
    androidMain/cpp/CMakeLists.txt
  .github/workflows/ci.yml
```

---

## 6. Public API (commonMain)

Design a thin, safe core plus a small ergonomic layer. The core mirrors the Lua stack model
closely enough to be predictable; the ergonomic helpers cover the 90% path.

```kotlin
package com.seanproctor.lua

interface LuaState : AutoCloseable {
    // --- lifecycle ---
    // created via LuaState(config); close() frees the underlying lua_State and disposes
    // all pinned host-function references. Using the state after close() throws.

    // --- script execution ---
    /** Loads and runs a chunk. Returns the values the chunk returns. Throws LuaException on error. */
    fun eval(code: String, chunkName: String = "=(load)"): List<LuaValue>

    /** Loads a chunk without running it, returning it as a callable LuaValue.Function. */
    fun load(code: String, chunkName: String = "=(load)"): LuaValue.Function

    // --- globals ---
    fun getGlobal(name: String): LuaValue
    fun setGlobal(name: String, value: LuaValue)

    // --- calling ---
    /** Calls a Lua function value with args, returns results. Uses lua_pcall; maps errors. */
    fun call(function: LuaValue.Function, args: List<LuaValue> = emptyList()): List<LuaValue>

    // --- host functions ---
    /** Registers a Kotlin function as a global callable from Lua. */
    fun register(name: String, function: LuaFunction)

    /** Wraps a Kotlin function as a LuaValue.Function (for putting into tables, returning, etc.). */
    fun wrap(function: LuaFunction): LuaValue.Function
}

/** Factory. Prefer this over touching platform types. */
fun LuaState(config: LuaConfig = LuaConfig()): LuaState = createLuaState(config)

internal expect fun createLuaState(config: LuaConfig): LuaState
```

Config and sandboxing:

```kotlin
data class LuaConfig(
    /** Which standard libraries to open. Default = a safe subset (no io, no os, no package). */
    val stdlibs: Set<StdLib> = StdLib.SAFE_DEFAULT,
)

enum class StdLib {
    BASE, COROUTINE, TABLE, STRING, MATH, UTF8, IO, OS, PACKAGE, DEBUG;
    companion object {
        val ALL: Set<StdLib> = entries.toSet()
        // Safe for running untrusted-ish scripts (warlock3 user scripts): everything except
        // io, os, package, debug.
        val SAFE_DEFAULT: Set<StdLib> = setOf(BASE, COROUTINE, TABLE, STRING, MATH, UTF8)
    }
}
```

Implement `stdlibs` on native/JNI using Lua 5.5's `luaL_openselectedlibs` where possible rather
than opening all and deleting; fall back to per-lib `luaL_requiref` if finer control is needed.

Value model:

```kotlin
sealed interface LuaValue {
    data object Nil : LuaValue
    data class Bool(val value: Boolean) : LuaValue
    data class Integer(val value: Long) : LuaValue      // Lua 5.5 integer subtype
    data class Number(val value: Double) : LuaValue      // Lua float subtype
    data class Str(val value: String) : LuaValue
    /** Opaque handle to a Lua table living in the registry; provides map/list accessors. */
    interface Table : LuaValue {
        operator fun get(key: LuaValue): LuaValue
        operator fun set(key: LuaValue, value: LuaValue)
        fun toMap(): Map<LuaValue, LuaValue>
        val size: Int                                    // border length (# operator)
    }
    /** Opaque handle to a Lua function living in the registry. */
    interface Function : LuaValue
}

/** SAM for host functions. Receives already-marshalled args; returns values to push back. */
fun interface LuaFunction {
    fun invoke(args: List<LuaValue>): List<LuaValue>
}
```

`Table` and `Function` are handles backed by references in the Lua registry so they survive
across stack operations; releasing them is tied to `LuaState.close()` (v1) — document that holding
many handles pins them until close, and consider an explicit `release()` in a later phase.

Exceptions:

```kotlin
open class LuaException(message: String) : RuntimeException(message)
class LuaSyntaxError(message: String) : LuaException(message)   // load-time
class LuaRuntimeError(message: String) : LuaException(message)  // pcall runtime
class LuaMemoryError(message: String) : LuaException(message)
```

Errors from `lua_pcall`/`luaL_loadstring` are converted to these by reading the error message off
the stack (copy it out immediately) and mapping the status code.

---

## 7. Native backend (cinterop)

### 7.1 The macro problem

cinterop binds functions and simple constant macros but **silently skips function-like macros**.
Much of the day-to-day Lua C API is macros: `lua_pop`, `lua_newtable`, `lua_pushcfunction`,
`lua_tostring`, `lua_call`, `luaL_dostring`, and the `lua_is*`/`lua_to*` shorthands. If you point
cinterop straight at `lua.h`/`lauxlib.h`, those come back missing.

Fix: a shim compiled into the Lua static lib that re-exposes every needed macro as a real,
externally-linkable function. Declare them non-inline in `lua_shim.h` and define them in
`lua_shim.c` so cinterop produces callable bindings.

```c
/* native/shim/lua_shim.h */
#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"

void        kl_pop(lua_State *L, int n);
void        kl_newtable(lua_State *L);
int         kl_dostring(lua_State *L, const char *s);
const char *kl_tostring(lua_State *L, int idx);   /* wraps lua_tolstring(L, idx, NULL) */
int         kl_isnil(lua_State *L, int idx);
int         kl_isinteger(lua_State *L, int idx);
/* …one wrapper per macro actually used by the binding… */
```

Keep the shim surface minimal — only wrap what the backend calls.

### 7.2 cinterop def

```
# src/nativeInterop/cinterop/lua.def
package = com.seanproctor.lua.cinterop
headers = lua.h lauxlib.h lualib.h lua_shim.h
compilerOpts = -I../../../native/lua/src -I../../../native/shim
# link against the per-target static lib produced by the C compile step (see 11.2)
```

Wire `staticLibraries`/`libraryPaths` (or `linkerOpts`) to the per-target `liblua.a` built in the
compile step. Bindings must be regenerated per target; the KMP Gradle plugin handles this once
cinterop is declared on the shared native compilation.

### 7.3 Host-callback dispatch (the hard part)

`staticCFunction` only accepts a non-capturing function. So we cannot close over "which Kotlin
lambda this is." Use one shared dispatcher plus an integer id routed through a Lua upvalue into a
Kotlin-side registry, and pin the Kotlin handler with `StableRef`.

Model (shared conceptually with JVM; see 8.3):
1. `HandlerRegistry` (commonMain contract) maps `Int id -> LuaFunction`.
2. `register`/`wrap` allocates an id, stores the `LuaFunction`, pushes a C closure whose single
   upvalue is that id, using the shared dispatcher as the C function.
3. On call, the dispatcher reads the upvalue id, looks up the handler, marshals args off the stack
   into `List<LuaValue>`, invokes the handler, pushes results back, returns the count.

```kotlin
// nativeMain/internal/Dispatch.kt (sketch)
private val dispatcher = staticCFunction<CPointer<lua_State>?, Int> { L ->
    val id = kl_upvalue_int(L, 1)                 // shim: reads lua_upvalueindex(1) as int
    val handler = NativeHandlerRegistry.get(L, id) // registry keyed per state
    try {
        val args = popArgs(L)
        val results = handler.invoke(args)
        pushAll(L, results)
        results.size
    } catch (t: Throwable) {
        // never let a Kotlin exception cross into C: convert to a Lua error (longjmp)
        pushErrorMessage(L, t.message ?: "host function error")
        lua_error(L)                               // does not return
        0
    }
}
```

GC lifetime: for any Kotlin object we must keep alive for the interpreter's lifetime (the registry,
or per-handle state), pin with `StableRef` and dispose it deterministically. Where a Kotlin object's
lifetime should follow a Lua value, attach it to a Lua full-userdata carrying a `__gc` metamethod
whose C side disposes the `StableRef`. At minimum: dispose the whole registry (and its StableRefs)
in `close()`. A leak test (Phase 6) must prove no StableRef survives `close()`.

Thread confinement: a `lua_State` is not thread-safe. `NativeLuaState` asserts single-thread
affinity (capture the creating thread; throw if touched from another). Do not add locking in v1.

String lifetime: `lua_tolstring` returns a pointer into Lua-managed memory valid only while the
value is on the stack. Copy to a Kotlin `String` (`toKString`) immediately in the marshalling layer.

---

## 8. JVM / Android backend (JNI)

### 8.1 Shim

`native/jni/lua_jni.c` exposes one `JNIEXPORT` entry per `external fun`, operating on a
`lua_State*` handle passed as a `jlong`. It links the same vendored Lua sources and may reuse
`lua_shim.c` internally.

### 8.2 Kotlin external declarations (jvmShared)

```kotlin
// jvmShared — shared by jvmMain and androidMain
internal object LuaJni {
    external fun newState(openLibsMask: Int): Long
    external fun close(state: Long)
    external fun loadString(state: Long, code: String, chunkName: String): Int   // status
    external fun pcall(state: Long, nargs: Int, nresults: Int): Int              // status
    // stack ops, push/to for each type, table create/get/set, ref/unref, error message read…
    external fun registerDispatch(state: Long, id: Int, name: String?)          // installs closure
}
```

`JniLuaState` implements the same marshalling and registry logic as the native backend; only the
low-level calls differ (`LuaJni.*` instead of cinterop functions). Factor the marshalling and
`HandlerRegistry` use into `jvmShared` so it is written once.

### 8.3 Callback dispatch on JVM

The JVM can capture, but keep the model identical to native for consistency: the C dispatcher
(installed by `registerDispatch`) reads the integer upvalue and calls back into a single Kotlin
static method (`nativeCallback(state: Long, id: Int): Int`) via JNI. That Kotlin method does the
marshalling and registry lookup in pure Kotlin, mirroring the native dispatcher. This keeps all
value conversion in one shared place and off the C side.

### 8.4 Library loading

- Android: `androidMain/cpp/CMakeLists.txt` builds `libluakmp.so` for the configured ABIs via
  `externalNativeBuild`; `AndroidLuaState` calls `System.loadLibrary("luakmp")`. The AAR packages
  the `.so` files automatically.
- JVM: build `libluakmp.{dylib,so,dll}` per OS/arch in CI, package under
  `resources/native/<os>-<arch>/`. At first use, `JvmLuaState` extracts the correct binary to a
  temp file and `System.load`s it. Detect os/arch from system properties; fail clearly if a
  binary for the current platform isn't bundled.

---

## 9. Vendored Lua and 5.5 specifics

- Vendor unmodified Lua 5.5.0 sources under `native/lua/` with a `VERSION` file and Lua's `LICENSE`.
  `revendor.sh` downloads a pinned tarball, verifies its checksum, and unpacks `src/` only.
- Build Lua with default `luaconf.h` (64-bit integer + double). Do not enable compat flags for
  older versions — we are 5.5-only.
- Sandboxing uses `luaL_openselectedlibs` (added in 5.5) to open exactly the requested subset.
- Document 5.5 script-facing semantics for downstream users in the README: global-variable
  declarations, read-only for-loop variables, and float printing changes. These affect script
  authors, not the binding.
- `luaL_makeseed` and external strings exist in 5.5; not required for v1 but note external strings
  as a possible zero-copy optimization for large text buffers (defer; measure first).

---

## 10. Cross-cutting requirements

- Memory: every pinned Kotlin reference (native `StableRef`, JVM global ref) is disposed on
  `close()`. Handles (`Table`, `Function`) are registry-backed and released at `close()`.
- Threading: single-thread confinement, enforced with a clear exception message. No internal locks.
- Errors: script/load/call failures surface as the `LuaException` hierarchy. Host-function
  exceptions are caught at the boundary and converted to Lua errors; they must never unwind across
  C. A Lua error raised inside a host call must not leak native resources — acquire/marshal before
  any point where `lua_error` can fire.
- Strings: always copy out of Lua memory immediately; assume returned pointers are transient.
- No `println`/logging in the library path; return information via exceptions and results.

---

## 11. Build and tooling

### 11.1 Gradle

- Kotlin Multiplatform plugin, the target set from section 3, `explicitApi()` enabled.
- `commonTest` runs on all targets. Native tests run on host-compatible targets
  (Apple targets on a macOS runner, `linuxX64` on Linux, `mingwX64` on Windows).
- `libs.versions.toml` for versions; keep Kotlin/AGP/NDK pinned and documented.
- Publishing via the Maven Central portal with signing; group `com.seanproctor`, POM metadata,
  sources + javadoc(-stub) jars.

### 11.2 Compiling vendored C

- Kotlin/Native: add a Gradle step that compiles `native/lua/src/*.c` + `native/shim/lua_shim.c`
  into a per-target static `liblua.a` using the Kotlin/Native-provided clang for that target
  (correct sysroot/arch for iOS device vs simulator vs macOS vs linux vs mingw). cinterop links it.
  This is the fiddliest part of the build — isolate it in a dedicated Gradle task per target and
  make failures legible.
- Android: CMake builds Lua + shim + `lua_jni.c` into `libluakmp.so` per ABI via the NDK.
- JVM: compile Lua + shim + `lua_jni.c` into a shared library per host in CI (see 11.3), then
  stage into `resources/native/<os>-<arch>/`.

### 11.3 CI (GitHub Actions)

Matrix across runners, using `setup-gradle` with caching:
- macOS runner: build + test Apple targets (ios*, macos*); build the macOS JVM `.dylib`
  (arm64 + x64).
- Linux runner: build + test `linuxX64` and Android; build the Linux JVM `.so` (x64; arm64 via
  cross-compile or a separate arm runner).
- Windows runner: build + test `mingwX64`; build the Windows JVM `.dll` (x64).
- A publish job assembles all staged JVM native binaries into the jar and publishes all target
  artifacts together. Native binaries built on their native runners must be collected as CI
  artifacts and combined before the JVM jar is finalized.

---

## 12. Testing

Put the behavioural suite in `commonTest` so it runs identically on every backend — this is how we
prove cross-platform parity.

Cover:
- eval returning values; multiple return values; nil/boolean/integer/float/string round-trips.
- integer vs float distinction preserved (5.5 subtype: `3` is Integer, `3.0` is Number).
- getGlobal/setGlobal round-trips including tables.
- table read/write, `size`/border, `toMap`.
- call a Lua function from Kotlin with args and results.
- register a Kotlin function, call it from Lua, including passing/returning tables.
- error paths: syntax error -> LuaSyntaxError; runtime error -> LuaRuntimeError; host function
  throwing -> surfaces as a Lua error inside the script and, if uncaught by the script, as
  LuaRuntimeError to the caller.
- sandboxing: with SAFE_DEFAULT, `os`/`io` are absent; with ALL, they are present.
- lifetime/leak: create a state, register N host functions and create N handles, close(), and
  assert the registry and all pinned refs are disposed (native: no surviving StableRef; JVM: no
  surviving global refs). Run under a loop to catch leaks.

Add a small native-only test that stresses the dispatcher across many calls to catch marshalling
and GC-interaction bugs.

---

## 13. Implementation phases

Each phase is independently buildable and testable. Do not start a phase before the previous one's
acceptance checks pass.

Phase 0 — Skeleton
- KMP project with all targets declared, `explicitApi()`, empty `commonMain` API compiling, CI
  green on a trivial `commonTest`. No native code yet.

Phase 1 — Vendor + build C
- `revendor.sh` pulls pinned Lua 5.5.0. Per-target `liblua.a` builds for all KN targets; Android
  CMake and JVM host library build produce loadable binaries. Prove with a throwaway test that
  calls `lua_version`/runs `return 1+1` through the rawest possible path on one native target and
  on JVM.

Phase 2 — Core on native
- cinterop def + shim wrappers; `NativeLuaState` with lifecycle, `eval`, globals, primitive
  marshalling, error mapping. `commonTest` primitive + error tests pass on Apple/Linux native.

Phase 3 — Core on JVM/Android
- JNI shim + `external fun`s + `JniLuaState` reusing the shared marshalling. Same `commonTest`
  subset passes on jvm and androidTarget. Native-library loading works on JVM (extraction) and
  Android (loadLibrary).

Phase 4 — Tables, functions, host callbacks
- Registry + dispatcher on both backends; `register`/`wrap`/`call`, table handles, `LuaValue.Table`
  and `Function`. Full `commonTest` (minus leak stress) passes everywhere.

Phase 5 — Sandboxing + config
- `LuaConfig`/`StdLib` wired via `luaL_openselectedlibs`. Sandboxing tests pass.

Phase 6 — Lifetime hardening + leak tests
- `__gc`-tied StableRef disposal on native; global-ref cleanup on JVM; leak-loop tests pass with no
  growth. Document threading and handle-lifetime contracts.

Phase 7 — Publishing
- Maven Central publication of all target artifacts from CI, combining per-runner JVM native
  binaries. README with usage, supported platforms, 5.5 notes, and the sandboxing guide.

Later (post-v1, out of scope here): Kotlin-coroutine bridge for Lua coroutines; explicit handle
`release()`; external-string zero-copy; a confined-dispatcher wrapper exposing suspend functions.

---

## 14. Decisions left to the implementer

- Exact JVM arm64 Linux strategy (cross-compile vs dedicated runner). Prefer whatever keeps CI
  simplest; document the choice.
- Whether `mingwX64` JVM support ships in v1 or only the KN `mingwX64` target. If the JVM Windows
  `.dll` build is troublesome, ship KN Windows + JVM on mac/linux first and add JVM Windows later.
- Whether to expose a raw stack API publicly or keep it `internal`. Default: keep it internal;
  publish only the high-level surface in section 6.

---

## 15. Acceptance criteria (v1 done)

- `LuaState(config).eval("return 1+1")` returns `[Integer(2)]` on JVM, Android, an iOS target,
  linuxX64, and macOS.
- Integer/float subtypes are preserved across all backends.
- A registered Kotlin function is callable from Lua and can accept and return tables.
- With `SAFE_DEFAULT`, `os` and `io` are unavailable to scripts; with `ALL`, they are.
- Host-function exceptions surface as Lua errors and, if uncaught, as `LuaRuntimeError`.
- Leak-loop test shows no growth in pinned references after repeated create/close.
- All target artifacts publish to Maven Central under `com.seanproctor:lua-kmp*`.
- README documents supported platforms, the 5.5 script-facing semantics, and sandboxing.

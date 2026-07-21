package com.seanproctor.lua.sample

import com.seanproctor.lua.LuaConfig
import com.seanproctor.lua.LuaRuntimeError
import com.seanproctor.lua.LuaState
import com.seanproctor.lua.LuaSyntaxError
import com.seanproctor.lua.LuaValue
import com.seanproctor.lua.StdLib

/**
 * A tour of lua-kmp. The same code runs on every target: as a JVM program
 * (`./gradlew :sample:runJvm`) and as a native executable
 * (`./gradlew :sample:runDebugExecutableLinuxX64`, `...MacosArm64`, etc.).
 */
fun main() {
    section("Eval: Lua values marshal to Kotlin") {
        LuaState().use { lua ->
            println(lua.eval("return 1 + 1"))
            // Lua 5.5 has integer and float subtypes; both survive the trip.
            println(lua.eval("return 3, 3.0, 'three'"))
        }
    }

    section("Globals") {
        LuaState().use { lua ->
            lua.setGlobal("greeting", LuaValue.Str("hello from Kotlin"))
            println(lua.eval("return greeting .. '!'").single())
        }
    }

    section("Tables are live handles, not copies") {
        LuaState().use { lua ->
            val config = lua.eval("config = { retries = 3 } ; return config").single() as LuaValue.Table
            config[LuaValue.Str("timeout")] = LuaValue.Number(1.5)
            println("Lua sees the Kotlin write:   " + lua.eval("return config.timeout").single())
            lua.eval("config.retries = config.retries + 1")
            println("Kotlin sees the Lua write:   " + config[LuaValue.Str("retries")])
            println("Snapshot via toMap():        " + config.toMap())
        }
    }

    section("Calling Lua functions from Kotlin") {
        LuaState().use { lua ->
            lua.eval("function fib(n) if n < 2 then return n end return fib(n - 1) + fib(n - 2) end")
            val fib = lua.getGlobal("fib") as LuaValue.Function
            println("fib(20) = " + lua.call(fib, listOf(LuaValue.Integer(20))).single())
        }
    }

    section("Host functions: scripts call into Kotlin") {
        LuaState().use { lua ->
            lua.register("kotlinUpper") { args ->
                listOf(LuaValue.Str((args.single() as LuaValue.Str).value.uppercase()))
            }
            println(lua.eval("return kotlinUpper('shouty')").single())

            // Host exceptions become ordinary Lua errors, catchable by pcall.
            lua.register("angry") { _ -> error("kotlin says no") }
            println(lua.eval("local ok, err = pcall(angry) ; return ok, err"))
        }
    }

    section("Host functions work inside Lua coroutines") {
        LuaState().use { lua ->
            lua.register("hostDouble") { args ->
                listOf(LuaValue.Integer((args.single() as LuaValue.Integer).value * 2))
            }
            val result = lua.eval(
                """
                local co = coroutine.wrap(function(x) return hostDouble(x) end)
                return co(21)
                """.trimIndent()
            ).single()
            println("coroutine -> host -> back: $result")
        }
    }

    section("Sandboxing") {
        LuaState().use { lua ->
            println("SAFE_DEFAULT: type(os) = " + lua.eval("return type(os)").single())
        }
        LuaState(LuaConfig(stdlibs = StdLib.ALL)).use { lua ->
            println("StdLib.ALL:   os.time() = " + lua.eval("return os.time()").single())
        }
    }

    section("Errors map to a typed hierarchy") {
        LuaState().use { lua ->
            try {
                lua.eval("this is not lua")
            } catch (e: LuaSyntaxError) {
                println("LuaSyntaxError:  ${e.message}")
            }
            try {
                lua.eval("error('runtime failure')")
            } catch (e: LuaRuntimeError) {
                println("LuaRuntimeError: ${e.message}")
            }
        }
    }
}

private fun section(title: String, body: () -> Unit) {
    println()
    println("== $title ==")
    body()
}

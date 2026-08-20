package com.seanproctor.lua

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Native-only stress of the staticCFunction dispatcher: many crossings of the
 * Kotlin/C boundary with marshalling on every call, to shake out StableRef
 * and GC-interaction bugs that single calls would never hit.
 */
class DispatcherStressTest {

    @Test
    fun manyPrimitiveCrossings() {
        LuaState().use { lua ->
            lua.register("echo") { args -> args }
            lua.eval(
                """
                for i = 1, 20000 do
                  local a, b, c = echo(i, i * 0.5, 's' .. i)
                  assert(a == i and b == i * 0.5 and c == 's' .. i, 'mismatch at ' .. i)
                end
                """.trimIndent()
            )
        }
    }

    @Test
    fun manyTableCrossings() {
        LuaState().use { lua ->
            lua.register("bump") { args ->
                val t = args.single() as LuaValue.Table
                val v = (t[LuaValue.Str("v")] as LuaValue.Integer).value
                t[LuaValue.Str("v")] = LuaValue.Integer(v + 1)
                listOf(t)
            }
            assertEquals(
                listOf<LuaValue>(LuaValue.Integer(2000)),
                lua.eval(
                    """
                    local n = 0
                    for i = 1, 2000 do
                      local t = bump({v = i})
                      if t.v == i + 1 then n = n + 1 end
                    end
                    return n
                    """.trimIndent()
                ),
            )
        }
    }

    @Test
    fun interleavedHostErrorsUnderLoad() {
        LuaState().use { lua ->
            lua.register("flaky") { args ->
                val n = (args.single() as LuaValue.Integer).value
                if (n % 7 == 0L) throw RuntimeException("no sevens") else listOf(LuaValue.Integer(n))
            }
            assertEquals(
                listOf<LuaValue>(LuaValue.Integer(857)),
                lua.eval(
                    """
                    local failures = 0
                    for i = 1, 6000 do
                      local ok = pcall(flaky, i)
                      if not ok then failures = failures + 1 end
                    end
                    return failures
                    """.trimIndent()
                ),
            )
        }
    }
}

package com.seanproctor.lua.internal

import com.seanproctor.lua.LuaRuntimeError
import com.seanproctor.lua.LuaValue
import com.seanproctor.lua.NativeLuaState
import com.seanproctor.lua.cinterop.kl_getextra
import com.seanproctor.lua.cinterop.kl_pushlstring
import com.seanproctor.lua.cinterop.kl_set_callback
import com.seanproctor.lua.cinterop.kl_upvalue_int
import com.seanproctor.lua.cinterop.lua_State
import com.seanproctor.lua.cinterop.lua_checkstack
import com.seanproctor.lua.cinterop.lua_gettop
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction

/**
 * The Kotlin side of host-function dispatch. The C shim's kl_dispatch is the
 * lua_CFunction behind every registered host function; it forwards here with
 * the calling lua_State (possibly a coroutine thread — the owning state rides
 * in the extraspace, which Lua copies to threads). The handler id is the
 * closure's first upvalue.
 *
 * The contract with kl_dispatch: return the number of results pushed, or -1
 * with an error message pushed on top. This function must return normally in
 * both cases — the Lua error (a longjmp) is raised by kl_dispatch only after
 * this Kotlin frame is gone, and Kotlin exceptions never cross into C.
 */
@OptIn(ExperimentalForeignApi::class)
private fun dispatchHost(L: CPointer<lua_State>?): Int {
    if (L == null) return -1
    return try {
        val state = kl_getextra(L)?.asStableRef<NativeLuaState>()?.get()
            ?: throw LuaRuntimeError("lua-kmp: no owning state in extraspace")
        val id = kl_upvalue_int(L, 1)
        val handler = state.handlers.get(id)
            ?: throw LuaRuntimeError("lua-kmp: unknown host function id $id")
        val nargs = lua_gettop(L)
        val args = ArrayList<LuaValue>(nargs)
        for (i in 1..nargs) args.add(state.marshal(L, i))
        val results = handler.invoke(args)
        lua_checkstack(L, results.size + 1)
        for (result in results) state.push(L, result)
        results.size
    } catch (t: Throwable) {
        val message = t.message ?: "host function error"
        kl_pushlstring(L, message, message.encodeToByteArray().size)
        -1
    }
}

@OptIn(ExperimentalForeignApi::class)
private val dispatchPtr = staticCFunction(::dispatchHost)

private var callbackInstalled = false

@OptIn(ExperimentalForeignApi::class)
internal fun ensureHostCallbackInstalled() {
    if (!callbackInstalled) {
        kl_set_callback(dispatchPtr)
        callbackInstalled = true
    }
}

@kotlin.native.concurrent.ThreadLocal
private object ThreadToken {
    val token = Any()
}

internal actual fun currentThreadToken(): Any = ThreadToken.token

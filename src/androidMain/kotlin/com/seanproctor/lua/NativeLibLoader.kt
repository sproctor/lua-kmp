package com.seanproctor.lua

internal actual fun loadLuaNative() {
    // The AAR packages libluakmp.so per ABI; loadLibrary is idempotent.
    System.loadLibrary("luakmp")
}

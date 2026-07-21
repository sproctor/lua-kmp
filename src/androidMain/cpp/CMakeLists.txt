cmake_minimum_required(VERSION 3.22)
project(luakmp C)

set(NATIVE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../native)

add_library(luakmp SHARED
    ${NATIVE_DIR}/shim/lua_all.c
    ${NATIVE_DIR}/shim/lua_shim.c
    ${NATIVE_DIR}/jni/lua_jni.c)

target_include_directories(luakmp PRIVATE
    ${NATIVE_DIR}/lua/src
    ${NATIVE_DIR}/shim)

# Bionic is close enough to a regular Linux for luaconf.h's purposes.
target_compile_definitions(luakmp PRIVATE LUA_USE_LINUX)
target_compile_options(luakmp PRIVATE -O2 -fvisibility=hidden)

target_link_libraries(luakmp m dl)

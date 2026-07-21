#include "lua_shim.h"

void kl_openlibs(lua_State *L, int mask) {
  int load = 0;
  if (mask & KL_LIB_BASE)      load |= LUA_GLIBK;
  if (mask & KL_LIB_COROUTINE) load |= LUA_COLIBK;
  if (mask & KL_LIB_TABLE)     load |= LUA_TABLIBK;
  if (mask & KL_LIB_STRING)    load |= LUA_STRLIBK;
  if (mask & KL_LIB_MATH)      load |= LUA_MATHLIBK;
  if (mask & KL_LIB_UTF8)      load |= LUA_UTF8LIBK;
  if (mask & KL_LIB_IO)        load |= LUA_IOLIBK;
  if (mask & KL_LIB_OS)        load |= LUA_OSLIBK;
  if (mask & KL_LIB_PACKAGE)   load |= LUA_LOADLIBK;
  if (mask & KL_LIB_DEBUG)     load |= LUA_DBLIBK;
  luaL_openselectedlibs(L, load, 0);
}

int kl_pcall(lua_State *L, int nargs, int nresults) {
  return lua_pcall(L, nargs, nresults, 0);
}

void kl_pop(lua_State *L, int n) {
  lua_pop(L, n);
}

int kl_upvalue_int(lua_State *L, int n) {
  return (int)lua_tointeger(L, lua_upvalueindex(n));
}

const char *kl_tolstring(lua_State *L, int idx, int *len_out) {
  size_t len = 0;
  const char *s = lua_tolstring(L, idx, &len);
  if (len_out)
    *len_out = (int)len;
  return s;
}

void kl_pushlstring(lua_State *L, const char *s, int len) {
  lua_pushlstring(L, s, (size_t)len);
}

int kl_loadbuffer(lua_State *L, const char *buf, int len, const char *name) {
  /* "t": accept only text chunks; precompiled bytecode is never loaded. */
  return luaL_loadbufferx(L, buf, (size_t)len, name, "t");
}

int kl_ref(lua_State *L) {
  return luaL_ref(L, LUA_REGISTRYINDEX);
}

void kl_unref(lua_State *L, int ref) {
  luaL_unref(L, LUA_REGISTRYINDEX, ref);
}

int kl_getref(lua_State *L, int ref) {
  return lua_rawgeti(L, LUA_REGISTRYINDEX, ref);
}

void kl_setextra(lua_State *L, void *p) {
  *(void **)lua_getextraspace(L) = p;
}

void *kl_getextra(lua_State *L) {
  return *(void **)lua_getextraspace(L);
}

static kl_callback kl_host_callback = NULL;

void kl_set_callback(kl_callback cb) {
  kl_host_callback = cb;
}

/*
** The single lua_CFunction behind every host function. The Kotlin (or JNI)
** callback marshals arguments, runs the handler, and pushes results; it must
** return normally even when the handler fails, reporting the failure as a
** negative count with the message on top. Only then does this frame raise
** the Lua error, so the longjmp never crosses a Kotlin or JNI frame.
*/
static int kl_dispatch(lua_State *L) {
  int nresults;
  if (kl_host_callback == NULL) {
    lua_pushliteral(L, "lua-kmp: host callback not installed");
    return lua_error(L);
  }
  nresults = kl_host_callback(L);
  if (nresults < 0)
    return lua_error(L); /* message pushed by the callback */
  return nresults;
}

void kl_push_dispatch_closure(lua_State *L, int id) {
  lua_pushinteger(L, id);
  lua_pushcclosure(L, kl_dispatch, 1);
}

static int gettable_aux(lua_State *L) {
  lua_gettable(L, 1);
  return 1;
}

int kl_pgettable(lua_State *L) {
  lua_pushcfunction(L, gettable_aux);
  lua_insert(L, -3); /* [.., aux, table, key] */
  return lua_pcall(L, 2, 1, 0);
}

static int settable_aux(lua_State *L) {
  lua_settable(L, 1);
  return 0;
}

int kl_psettable(lua_State *L) {
  lua_pushcfunction(L, settable_aux);
  lua_insert(L, -4); /* [.., aux, table, key, value] */
  return lua_pcall(L, 3, 0, 0);
}

static int len_aux(lua_State *L) {
  lua_pushinteger(L, luaL_len(L, 1));
  return 1;
}

int kl_plen(lua_State *L) {
  lua_pushcfunction(L, len_aux);
  lua_insert(L, -2); /* [.., aux, table] */
  return lua_pcall(L, 1, 1, 0);
}

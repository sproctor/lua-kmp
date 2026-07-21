/*
** Single-translation-unit build of the vendored Lua core + libraries,
** modelled on the upstream onelua.c (which the release tarball does not
** ship). Both binding backends compile exactly this file, so every target
** runs the same interpreter build.
*/

/* feature-test macros must be defined before any system header is seen */
#include "lprefix.h"

/* setup for luaconf.h */
#define LUA_CORE
#define LUA_LIB
#define ltable_c
#define lvm_c
#include "luaconf.h"

/* do not export internal symbols */
#undef LUAI_FUNC
#undef LUAI_DDEC
#undef LUAI_DDEF
#define LUAI_FUNC	static
#define LUAI_DDEC(dec)	/* empty */
#define LUAI_DDEF	static

/* core -- used by all */
#include "lzio.c"
#include "lctype.c"
#include "lopcodes.c"
#include "lmem.c"
#include "lundump.c"
#include "ldump.c"
#include "lstate.c"
/* lgc.c's internal static getmode() clashes with the BSD getmode() that the
   macOS/iOS SDK unistd.h declares (pulled in later via liolib.c) when
   everything shares one translation unit. Rename Lua's out of the way. */
#define getmode luakmp_gc_getmode
#include "lgc.c"
#undef getmode
#include "llex.c"
#include "lcode.c"
#include "lparser.c"
#include "ldebug.c"
#include "lfunc.c"
#include "lobject.c"
#include "ltm.c"
#include "lstring.c"
#include "ltable.c"
#include "ldo.c"
#include "lvm.c"
#include "lapi.c"

/* auxiliary library */
#include "lauxlib.c"

/* standard libraries */
#include "lbaselib.c"
#include "lcorolib.c"
#include "ldblib.c"
#include "liolib.c"
#include "lmathlib.c"
#include "loadlib.c"
#include "loslib.c"
#include "lstrlib.c"
#include "ltablib.c"
#include "lutf8lib.c"
#include "linit.c"

#!/usr/bin/env bash
# pkg-config wrapper used by meson cross builds: restrict the search path to our
# own Android prefix so host libraries can never leak into the toolchain.
set -euo pipefail
: "${PREFIX:?PREFIX must be exported}"
export PKG_CONFIG_LIBDIR="${PREFIX}/lib/pkgconfig:${PREFIX}/share/pkgconfig:${PREFIX}/lib"
unset PKG_CONFIG_PATH || true
exec pkg-config "$@"
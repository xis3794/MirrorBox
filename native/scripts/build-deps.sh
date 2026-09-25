#!/usr/bin/env bash
# Builds the shared dependencies used by qemu-img: zlib, zstd, pcre2, libffi and glib.
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

build_zlib() {
  fetch "${ZLIB_URL}" "zlib-${ZLIB_VERSION}.tar.gz"
  unpack "zlib-${ZLIB_VERSION}.tar.gz" "${BUILD_DIR}/zlib"
  cd "${BUILD_DIR}/zlib"
  CHOST="${TRIPLE}" ./configure --prefix="${PREFIX}" --static
  make -j"${JOBS}"
  make install
  package_lib "${PREFIX}/lib/libz.so" || true
}

build_zstd() {
  fetch "${ZSTD_URL}" "zstd-${ZSTD_VERSION}.tar.gz"
  unpack "zstd-${ZSTD_VERSION}.tar.gz" "${BUILD_DIR}/zstd"
  cd "${BUILD_DIR}/zstd"
  make -j"${JOBS}" lib-release
  cp -f lib/libzstd.so* "${PREFIX}/lib/" 2>/dev/null || true
  mkdir -p "${PREFIX}/include"
  cp -f lib/zstd.h "${PREFIX}/include/"
  normalize_so "${PREFIX}/lib/libzstd.so.1"
  for f in "${PREFIX}"/lib/libzstd.so*; do package_lib "$f"; done
}

build_pcre2() {
  fetch "${PCRE2_URL}" "pcre2-${PCRE2_VERSION}.tar.gz"
  unpack "pcre2-${PCRE2_VERSION}.tar.gz" "${BUILD_DIR}/pcre2"
  cd "${BUILD_DIR}/pcre2"
  # A local pc/ stub is used instead of full pkg-config during cross builds.
  ./configure --host="${TRIPLE}" --prefix="${PREFIX}" \
    --disable-shared --enable-static --disable-pcre2grep --disable-pcre2test
  make -j"${JOBS}"
  make install
}

build_libffi() {
  fetch "${LIBFFI_URL}" "libffi-${LIBFFI_VERSION}.tar.gz"
  unpack "libffi-${LIBFFI_VERSION}.tar.gz" "${BUILD_DIR}/libffi"
  cd "${BUILD_DIR}/libffi"
  ./configure --host="${TRIPLE}" --prefix="${PREFIX}" --disable-shared --enable-static
  make -j"${JOBS}"
  make install
}

# GLib needs a lot of care on bionic: meson is used (autotools is deprecated),
# and the resulting .so files must be unversioned for Android packaging.
build_glib() {
  fetch "${GLIB_URL}" "glib-${GLIB_VERSION}.tar.xz"
  unpack "glib-${GLIB_VERSION}.tar.xz" "${BUILD_DIR}/glib"
  cd "${BUILD_DIR}/glib"
  cat > "${BUILD_DIR}/android-cross.ini" <<EOF
[binaries]
c = '${CC}'
cpp = '${CXX}'
ar = '${AR}'
strip = '${STRIP}'
pkg-config = '${SCRIPT_DIR}/pkg-config-wrapper.sh'

[host_machine]
system = 'android'
cpu_family = '$( [[ "${ABI}" == x86_64 ]] && echo x86_64 || echo aarch64 )'
cpu = '$( [[ "${ABI}" == x86_64 ]] && echo x86_64 || echo aarch64 )'
endian = 'little'
EOF

  export PKG_CONFIG_PATH="${PREFIX}/lib/pkgconfig:${PREFIX}/share/pkgconfig"
  export PKG_CONFIG_LIBDIR="${PKG_CONFIG_PATH}"
  export CFLAGS="${CFLAGS_COMMON} -I${PREFIX}/include"
  export LDFLAGS="${LDFLAGS_COMMON} -L${PREFIX}/lib"

  meson setup "${BUILD_DIR}/glib-build" \
    --cross-file "${BUILD_DIR}/android-cross.ini" \
    --prefix "${PREFIX}" \
    --default-library shared \
    -Dselinux=disabled -Dxattr=false -Dlibmount=disabled -Dman=false \
    -Dtests=false -Dglib_debug=disabled -Ddocumentation=false \
    > "${LOG_DIR}/glib-meson.log" 2>&1 || { cat "${LOG_DIR}/glib-meson.log" >&2; return 1; }
  ninja -C "${BUILD_DIR}/glib-build" -j"${JOBS}"
  ninja -C "${BUILD_DIR}/glib-build" install

  for f in "${PREFIX}"/lib/libglib-2.0.so* "${PREFIX}"/lib/libgobject-2.0.so* \
           "${PREFIX}"/lib/libgmodule-2.0.so* "${PREFIX}"/lib/libgio-2.0.so*; do
    [[ -e "${f}" ]] || continue
    normalize_so "${f}" >/dev/null
  done
  fix_needed "${PREFIX}/lib"
  for f in "${PREFIX}"/lib/lib*.so; do package_lib "$f"; done
}

main() {
  log "building shared dependencies for ${ABI}"
  build_zlib
  build_zstd
  build_pcre2
  build_libffi
  build_glib
  log "dependencies done"
}

main "$@"

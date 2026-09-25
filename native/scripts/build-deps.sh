#!/usr/bin/env bash
# Builds the shared dependencies used by qemu-img: zlib, zstd, pcre2, libffi and glib.
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

build_zlib() {
  fetch "${ZLIB_URL}" "zlib-${ZLIB_VERSION}.tar.gz"
  unpack "zlib-${ZLIB_VERSION}.tar.gz" "${BUILD_DIR}/zlib"
  cd "${BUILD_DIR}/zlib"
  # zlib's hand written configure only skips its compile-and-run probes when CHOST is set,
# which is essential for cross compiling (the produced binaries cannot run on the build host).
  CHOST="${TRIPLE}" CC="${CC}" AR="${AR}" RANLIB="${RANLIB}" CFLAGS="${CFLAGS_COMMON}" \
    ./configure --prefix="${PREFIX}" --static
  make -j"${JOBS}"
  make install
  write_pc "zlib" "${ZLIB_VERSION}" "-L\${libdir} -lz" "-I\${includedir}"
}

build_zstd() {
  fetch "${ZSTD_URL}" "zstd-${ZSTD_VERSION}.tar.gz"
  unpack "zstd-${ZSTD_VERSION}.tar.gz" "${BUILD_DIR}/zstd"
  cd "${BUILD_DIR}/zstd"
  make -j"${JOBS}" lib-release CC="${CC}" AR="${AR}" CFLAGS="${CFLAGS_COMMON}"
  mkdir -p "${PREFIX}/lib" "${PREFIX}/include"
  cp -f lib/libzstd.so* "${PREFIX}/lib/" 2>/dev/null || true
  cp -f lib/libzstd.a "${PREFIX}/lib/" 2>/dev/null || true
  cp -f lib/zstd.h lib/zdict.h lib/zstd_errors.h "${PREFIX}/include/" 2>/dev/null || true
  local unversioned
  unversioned="$(normalize_so "${PREFIX}/lib/libzstd.so.1")"
  # Only the unversioned name is shipped: Android only extracts lib*.so entries and the
  # packaging verifier rejects versioned leftovers.
  if [[ -f "${PREFIX}/lib/libzstd.so" ]]; then
    package_lib "${PREFIX}/lib/libzstd.so"
  elif [[ -n "${unversioned}" && -f "${unversioned}" ]]; then
    package_lib "${unversioned}"
  fi
  write_pc "libzstd" "${ZSTD_VERSION}" "-L\${libdir} -lzstd" "-I\${includedir}"
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
  # --disable-exec-static-tramp is required on Android:
  #  * static trampolines call open_temp_exec_file(), which libffi declares nowhere, and modern
  #    clang rejects implicit function declarations outright
  #  * the whole feature needs an exec-writable temporary file, which Android's W^X rules deny
  # GLib only needs ordinary closures, so disabling it is both safe and semantically right.
  ./configure --host="${TRIPLE}" --prefix="${PREFIX}" \
    --disable-shared --enable-static \
    --disable-exec-static-tramp \
    > "${LOG_DIR}/libffi-configure.log" 2>&1 || { tail -40 "${LOG_DIR}/libffi-configure.log" >&2; exit 1; }
  make -j"${JOBS}" > "${LOG_DIR}/libffi-make.log" 2>&1 || { tail -60 "${LOG_DIR}/libffi-make.log" >&2; exit 1; }
  make install
}

# GLib hard-requires a pkg-config dependency named "iconv". bionic only exports iconv from
# API 28 onwards, and this project links against API 24, so GNU libiconv is built statically
# instead (small, no runtime dependency, keeps API 24 devices working).
build_libiconv() {
  if [[ -f "${PREFIX}/lib/libiconv.a" ]]; then
    log "libiconv already built (cached)"
    return 0
  fi
  fetch "${LIBICONV_URL}" "libiconv-${LIBICONV_VERSION}.tar.gz"
  unpack "libiconv-${LIBICONV_VERSION}.tar.gz" "${BUILD_DIR}/libiconv"
  cd "${BUILD_DIR}/libiconv"
  ./configure --host="${TRIPLE}" --prefix="${PREFIX}" \
    --disable-shared --enable-static --disable-nls \
    > "${LOG_DIR}/libiconv-configure.log" 2>&1 || { tail -40 "${LOG_DIR}/libiconv-configure.log" >&2; exit 1; }
  make -j"${JOBS}" > "${LOG_DIR}/libiconv-make.log" 2>&1 || { tail -60 "${LOG_DIR}/libiconv-make.log" >&2; exit 1; }
  make install
  write_pc "iconv" "${LIBICONV_VERSION}" "-L\${libdir} -liconv" "-I\${includedir}"
}

# GLib needs a lot of care on bionic: meson is used (autotools is deprecated),
# and the resulting .so files must be unversioned for Android packaging.
build_glib() {
  if [[ -f "${PREFIX}/lib/libglib-2.0.so" ]]; then
    log "glib already built (cached)"
    return 0
  fi
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

[properties]
# Target binaries cannot be executed on the build host.
needs_exe_wrapper = true

[built-in options]
c_args = ['-O2', '-fPIC', '-fstack-protector-strong']
# -L${PREFIX}/lib is required: glib links our static libiconv/libffi through pkg-config, and
# meson does not always carry the dependency's -L over to the final link line.
c_link_args = ['-Wl,-z,max-page-size=16384', '-L${PREFIX}/lib']
EOF

  export PKG_CONFIG_PATH="${PREFIX}/lib/pkgconfig:${PREFIX}/share/pkgconfig"
  export PKG_CONFIG_LIBDIR="${PKG_CONFIG_PATH}"
  export CFLAGS="${CFLAGS_COMMON} -I${PREFIX}/include"
  export LDFLAGS="${LDFLAGS_COMMON} -L${PREFIX}/lib"

  # Option types verified against glib 2.76's meson_options.txt:
  #   feature options (selinux, libmount, libelf, nls, glib_debug) take "disabled",
  #   boolean options (xattr, man, gtk_doc, tests, installed_tests, multiarch) take false.
  meson setup "${BUILD_DIR}/glib-build" \
    --cross-file "${BUILD_DIR}/android-cross.ini" \
    --prefix "${PREFIX}" \
    --default-library shared \
    -Dselinux=disabled -Dxattr=false -Dlibmount=disabled -Dlibelf=disabled \
    -Dman=false -Dgtk_doc=false -Dtests=false -Dinstalled_tests=false \
    -Dnls=disabled -Dglib_debug=disabled -Dmultiarch=false \
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

  # glib falls back to its bundled subprojects/proxy-libintl when no system intl exists.
  # libglib-2.0.so then has DT_NEEDED=libintl.so, so that library must travel next to it.
  for cand in "${BUILD_DIR}/glib-build/subprojects/proxy-libintl/"libintl.so*; do
    [[ -e "${cand}" ]] || continue
    local unversioned_intl
    unversioned_intl="$(normalize_so "${cand}")"
    if [[ -f "${unversioned_intl}" ]]; then
      cp -f "${unversioned_intl}" "${PREFIX}/lib/libintl.so"
      package_lib "${PREFIX}/lib/libintl.so"
      log "packaged proxy libintl.so"
    fi
    break
  done
}

main() {
  log "building shared dependencies for ${ABI}"
  build_zlib
  build_zstd
  build_pcre2
  build_libffi
  build_libiconv
  build_glib
  log "dependencies done"
}

main "$@"

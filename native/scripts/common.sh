#!/usr/bin/env bash
# Shared helpers for the MirrorBox native toolchain build.
#
# Design notes
#  - Everything is cross compiled for Android (bionic) with the NDK.
#  - Shared objects MUST end in ".so" without version suffixes, otherwise the APK
#    packaging step refuses to ship them and the app cannot load them. Every library
#    therefore gets its SONAME rewritten to "lib<name>.so" and dependents are patched
#    with `patchelf --replace-needed`.
#  - Binaries are shipped as `lib<tool>.so` inside jniLibs so that Android extracts them
#    into nativeLibraryDir where they are executable (app data dirs are not, for
#    targetSdk >= 29).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NATIVE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=/dev/null
source "${NATIVE_DIR}/versions.env"

: "${ANDROID_NDK_ROOT:?ANDROID_NDK_ROOT must point at the Android NDK}"
: "${ABI:=arm64-v8a}"
: "${OUT_DIR:=${NATIVE_DIR}/out}"
: "${SRC_DIR:=${NATIVE_DIR}/src}"
: "${BUILD_DIR:=${NATIVE_DIR}/build}"
: "${PREFIX:=${OUT_DIR}/${ABI}/prefix}"
: "${JOBS:=$(nproc)}"

case "${ABI}" in
  arm64-v8a)   TRIPLE=aarch64-linux-android ;;
  armeabi-v7a) TRIPLE=armv7a-linux-androideabi ;;
  x86_64)      TRIPLE=x86_64-linux-android ;;
  *) echo "unsupported ABI: ${ABI}" >&2; exit 1 ;;
esac

TOOLCHAIN="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/linux-x86_64"
export PATH="${TOOLCHAIN}/bin:${PATH}"
export ABI OUT_DIR SRC_DIR BUILD_DIR PREFIX TRIPLE ANDROID_API NATIVE_DIR SCRIPT_DIR
export CC="${TOOLCHAIN}/bin/${TRIPLE}${ANDROID_API}-clang"
export CXX="${TOOLCHAIN}/bin/${TRIPLE}${ANDROID_API}-clang++"
export AR="${TOOLCHAIN}/bin/llvm-ar"
export RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"
export STRIP="${TOOLCHAIN}/bin/llvm-strip"
export NM="${TOOLCHAIN}/bin/llvm-nm"
export OBJCOPY="${TOOLCHAIN}/bin/llvm-objcopy"
export LD="${TOOLCHAIN}/bin/ld.lld"
# NOTE: __ANDROID_API__ is deliberately not defined here — the NDK's clang wrappers
# (aarch64-linux-android24-clang) already define it, and defining it again triggers
# `-Werror,-Wmacro-redefined` inside meson/glib's compiler probes.
# 16 KB is the page size Android requires on newer devices: every loadable segment has to be
# aligned to it. patchelf must be told the same value through --page-size (see normalize_so),
# otherwise it appends a segment that violates the ELF congruence rule and the Android linker
# maps the dynamic section from the wrong file offset (see check-elf.py).
export MAX_PAGE_SIZE=16384

export CFLAGS_COMMON="-O2 -fPIC -fstack-protector-strong"
export LDFLAGS_COMMON="-Wl,-z,max-page-size=${MAX_PAGE_SIZE}"

# Exported so that autotools based components (libffi, pcre2, e2fsprogs, mtools, …) pick up the
# cross flags without having to pass them explicitly.
export CFLAGS="${CFLAGS_COMMON}"
export CXXFLAGS="${CFLAGS_COMMON}"
export LDFLAGS="${LDFLAGS_COMMON}"

log() { printf '\033[1;36m[native]\033[0m %s\n' "$*" >&2; }
warn() { printf '\033[1;33m[native]\033[0m %s\n' "$*" >&2; }

# patchelf older than 0.18 (Ubuntu 22.04 ships 0.14.2) mis-places the loadable segment it appends
# to a library: p_vaddr and p_offset stop being congruent modulo p_align, and the Android linker
# then maps the dynamic section from the wrong file offset. The library still passes every
# packaging check but cannot be loaded on device. The PyPI wheel ships a current build:
#   python3 -m pip install --user patchelf
require_patchelf() {
  local version
  if ! command -v patchelf > /dev/null 2>&1; then
    warn "patchelf not found; install it with: python3 -m pip install --user patchelf"
    return 1
  fi
  version="$(patchelf --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+(\.[0-9]+)?' | head -n1)"
  if [[ -z "${version}" ]]; then
    warn "cannot determine the patchelf version"
    return 1
  fi
  if [[ "$(printf '%s\n%s\n' "0.18" "${version}" | sort -V | head -n1)" != "0.18" ]]; then
    warn "patchelf ${version} is too old (need >= 0.18): it produces ELF segment tables the Android linker cannot map"
    warn "install a current one with: python3 -m pip install --user patchelf"
    return 1
  fi
  return 0
}

mkdir -p "${SRC_DIR}" "${BUILD_DIR}" "${PREFIX}" "${OUT_DIR}/${ABI}/tools"

fetch() {
  local url="$1" archive="$2"
  if [[ -f "${SRC_DIR}/${archive}" ]]; then
    log "cached ${archive}"
    return 0
  fi
  log "downloading ${url}"
  curl -fL --retry 5 --retry-delay 3 --retry-all-errors -o "${SRC_DIR}/${archive}" "${url}" \
    || { warn "primary mirror failed, retrying with ghproxy"; curl -fL --retry 3 -o "${SRC_DIR}/${archive}" "https://ghproxy.net/${url}"; }
}

unpack() {
  local archive="$1" dest="$2"
  # Skip when the destination already holds an unpacked tree (CI cache / re-run).
  if [[ -d "${dest}" && -n "$(ls -A "${dest}" 2>/dev/null)" ]]; then
    return 0
  fi
  mkdir -p "${dest}"
  log "unpacking ${archive}"
  # NOTE: extract *into* ${dest} with --strip-components=1 (the tarballs have a single
  # top-level directory). Extracting into $(dirname dest) would scatter the sources into the
  # build directory — an early CI failure that looked like "configure: No such file".
  tar -xf "${SRC_DIR}/${archive}" -C "${dest}" --strip-components=1 --no-same-owner --no-overwrite-dir
}

# Writes a minimal pkg-config file; needed because QEMU detects zlib/zstd through pkg-config
# and the upstream zlib/zstd builds do not always install one.
write_pc() {
  local name="$1" version="$2" libs="$3" cflags="$4"
  local dir="${PREFIX}/lib/pkgconfig"
  mkdir -p "${dir}"
  {
    echo "prefix=${PREFIX}"
    echo "exec_prefix=\${prefix}"
    echo "libdir=\${exec_prefix}/lib"
    echo "includedir=\${prefix}/include"
    echo ""
    echo "Name: ${name}"
    echo "Description: ${name} (MirrorBox cross build)"
    echo "Version: ${version}"
    echo "Libs: ${libs}"
    echo "Cflags: ${cflags}"
  } > "${dir}/${name}.pc"
  log "wrote ${dir}/${name}.pc"
}

# Ensures a binary shipped from a toolchain still resolves $ORIGIN rpaths and
# carries no versioned SONAME.
normalize_so() {
  local file="$1"
  local base
  base="$(basename "${file}")"

  # libfoo.so.1.2.3 -> libfoo.so
  if [[ "${base}" =~ ^(lib[^/]+?\.so)(\.[0-9]+)*$ ]]; then
    local unversioned="${BASH_REMATCH[1]}"
    if [[ "${base}" != "${unversioned}" ]]; then
      local dir
      dir="$(dirname "${file}")"
      cp -f "${file}" "${dir}/${unversioned}"
      patchelf --page-size "${MAX_PAGE_SIZE}" --set-soname "${unversioned}" "${dir}/${unversioned}" || true
      log "normalized ${base} -> ${unversioned}"
      file="${dir}/${unversioned}"
    fi
  fi

  # --page-size must match the -Wl,-z,max-page-size used when linking. Without it patchelf places
  # the appended segment at a p_vaddr/p_offset pair that is not congruent modulo p_align, and the
  # Android linker then reads the dynamic section from the wrong file offset: the library looks
  # intact in every packaging check yet fails on device with
  #   "unused DT entry: unknown (type0x5858...)" / "empty/missing DT_HASH/DT_GNU_HASH".
  patchelf --page-size "${MAX_PAGE_SIZE}" --set-rpath '$ORIGIN' "${file}" 2>/dev/null || true

  # NOTE: the Limbo-era "_rwlock_* -> pthread_rwlock_*" step was done with `--replace-symbol`,
  # which patchelf does not have (the option was silently ignored, so it never did anything).
  # It is also unnecessary here: bionic exports no _rwlock_* symbols, so a reference to one would
  # have failed the link outright — and the link is clean.
  echo "${file}"
}

# Replace versioned DT_NEEDED entries of every binary in a directory so they match
# the unversioned names we ship.
fix_needed() {
  local dir="$1"
  local lib
  for lib in "${dir}"/lib*.so*; do
    [[ -e "${lib}" ]] || continue
    local unversioned
    if [[ "$(basename "${lib}")" =~ ^(lib[^/]+?\.so)(\.[0-9]+)*$ ]]; then
      unversioned="${BASH_REMATCH[1]}"
    else
      continue
    fi
    local target
    for target in "${dir}"/lib*.so*; do
      [[ -e "${target}" ]] || continue
      [[ "$(basename "${target}")" == "${unversioned}" ]] && continue
      patchelf --page-size "${MAX_PAGE_SIZE}" --replace-needed "$(basename "${target}")" "${unversioned}" "${target}" 2>/dev/null || true
    done
  done
}

# True when $1 starts with the ELF magic number.
#
# This exists to catch libtool wrapper *scripts*: libtool leaves a small shell wrapper at
# <dir>/<name> and the real ELF executable at <dir>/.libs/<name>. Copying the wrapper out of the
# build tree yields a "tool" that is a few KB of /bin/sh (ntfsprogs came out as 8 KB shells this
# way) and that cannot run inside the APK at all.
is_elf() {
  [[ -f "$1" ]] || return 1
  [[ "$(od -An -tx1 -N4 "$1" 2>/dev/null | tr -d '[:space:]')" == "7f454c46" ]]
}

# Resolves the real ELF behind a possible libtool wrapper.
real_binary() {
  local path="$1"
  if is_elf "${path}"; then
    printf '%s' "${path}"
    return 0
  fi
  local alt
  alt="$(dirname "${path}")/.libs/$(basename "${path}")"
  if is_elf "${alt}"; then
    printf '%s' "${alt}"
    return 0
  fi
  printf '%s' "${path}"
}

# Copies the finished executables into the tool naming convention used by the app
# (lib<tool>.so) and into the jniLibs staging directory.
#
# Fails loudly when the source is not an ELF executable: silently shipping a wrapper script (or a
# missing binary) produces an APK whose tools cannot run, which is far harder to diagnose.
package_tool() {
  local name="$1" src="$2"
  src="$(real_binary "${src}")"
  if ! is_elf "${src}"; then
    warn "package_tool: ${src} is not an ELF executable (libtool wrapper left behind?)"
    return 1
  fi
  local out="${OUT_DIR}/${ABI}/tools/lib${name}.so"
  cp -f "${src}" "${out}"
  chmod 755 "${out}"
  "${STRIP}" --strip-unneeded "${out}" 2>/dev/null || true
  log "packaged lib${name}.so ($(du -h "${out}" | cut -f1))"
}

package_lib() {
  local src="$1"
  local base
  base="$(basename "${src}")"
  if ! is_elf "${src}"; then
    warn "package_lib: ${src} is not a shared object"
    return 1
  fi
  local out="${OUT_DIR}/${ABI}/tools/${base}"
  cp -f "${src}" "${out}"
  chmod 755 "${out}"
  "${STRIP}" --strip-unneeded "${out}" 2>/dev/null || true
}

export LOG_DIR="${OUT_DIR}/logs"
mkdir -p "${LOG_DIR}"

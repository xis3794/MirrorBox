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
export CFLAGS_COMMON="-O2 -fPIC -fstack-protector-strong -D__ANDROID_API__=${ANDROID_API}"
export LDFLAGS_COMMON="-Wl,-z,max-page-size=16384"

log() { printf '\033[1;36m[native]\033[0m %s\n' "$*" >&2; }
warn() { printf '\033[1;33m[native]\033[0m %s\n' "$*" >&2; }

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
  [[ -d "${dest}" ]] && return 0
  mkdir -p "${dest}"
  log "unpacking ${archive}"
  tar -xf "${SRC_DIR}/${archive}" -C "$(dirname "${dest}")" --strip-components=1 --no-same-owner --no-overwrite-dir
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
      patchelf --set-soname "${unversioned}" "${dir}/${unversioned}" || true
      log "normalized ${base} -> ${unversioned}"
      file="${dir}/${unversioned}"
    fi
  fi

  patchelf --set-rpath '$ORIGIN' "${file}" 2>/dev/null || true
  # Huawei devices' linker chokes on glibc style versioned symbols coming from glib.
  patchelf --replace-symbol _rwlock_trywrlock pthread_rwlock_trywrlock "${file}" 2>/dev/null || true
  patchelf --replace-symbol _rwlock_rdlock pthread_rwlock_rdlock "${file}" 2>/dev/null || true
  patchelf --replace-symbol _rwlock_wrlock pthread_rwlock_wrlock "${file}" 2>/dev/null || true
  patchelf --replace-symbol _rwlock_unlock pthread_rwlock_unlock "${file}" 2>/dev/null || true
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
      patchelf --replace-needed "$(basename "${target}")" "${unversioned}" "${target}" 2>/dev/null || true
    done
  done
}

# Copies the finished executables into the tool naming convention used by the app
# (lib<tool>.so) and into the jniLibs staging directory.
package_tool() {
  local name="$1" src="$2"
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
  local out="${OUT_DIR}/${ABI}/tools/${base}"
  cp -f "${src}" "${out}"
  chmod 755 "${out}"
  "${STRIP}" --strip-unneeded "${out}" 2>/dev/null || true
}

export LOG_DIR="${OUT_DIR}/logs"
mkdir -p "${LOG_DIR}"

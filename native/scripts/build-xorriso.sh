#!/usr/bin/env bash
# Builds GNU xorriso for Android: ISO creation, extraction and editing
# (including El Torito BIOS/EFI boot catalogues through -as mkisofs).
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

fetch "${XORRISO_URL}" "xorriso-${XORRISO_VERSION}.tar.gz"
unpack "xorriso-${XORRISO_VERSION}.tar.gz" "${BUILD_DIR}/xorriso"
cd "${BUILD_DIR}/xorriso"

# xorriso brings its own libburn/libisofs; optical device support is irrelevant
# on Android, ACL/xattr support is unavailable on bionic.
./configure --host="${TRIPLE}" --prefix="${PREFIX}" \
  --disable-libacl --disable-xattr --disable-libreadline --disable-libedit \
  --disable-libjte \
  > "${LOG_DIR}/xorriso-configure.log" 2>&1 || { tail -60 "${LOG_DIR}/xorriso-configure.log" >&2; exit 1; }

make -j"${JOBS}" > "${LOG_DIR}/xorriso-make.log" 2>&1 || { tail -80 "${LOG_DIR}/xorriso-make.log" >&2; exit 1; }

BIN=""
for candidate in "${BUILD_DIR}/xorriso/xorriso/xorriso" "${BUILD_DIR}/xorriso/xorriso" "${BUILD_DIR}/xorriso/src/xorriso"; do
  if [[ -x "${candidate}" && -f "${candidate}" ]]; then
    BIN="${candidate}"
    break
  fi
done
if [[ -z "${BIN}" ]]; then
  warn "xorriso binary not found; searching"
  BIN="$(find "${BUILD_DIR}/xorriso" -maxdepth 3 -type f -name xorriso -perm -u+x | head -1 || true)"
fi
[[ -n "${BIN}" ]] || { warn "xorriso build produced no executable"; exit 1; }

package_tool "xorriso" "${BIN}"
log "xorriso packaged"
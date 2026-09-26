#!/usr/bin/env bash
# Builds wimlib-imagex: the DISM++-style WIM engine (info / dir / extract / apply / capture).
#
# Why this is part of MirrorBox: "释放 WIM" needs to apply a Windows image (install.wim, boot.wim,
# .esd …) to a directory *without* mounting anything. wimlib-imagex apply does exactly that; the
# app then packs the staging directory into the target partition with mke2fs -d (ext4) or mtools
# (FAT), or writes it through wimlib's own NTFS support when it is available.
#
# Deliberately built static, without libntfs-3g and without libfuse:
#  * a shared libwim.so would never reach jniLibs (Android only packages lib*.so), so the CLI has
#    to be self-contained
#  * mounting and applying straight to a block device is impossible on unrooted Android
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

build_wimlib() {
  if [[ -f "${OUT_DIR}/${ABI}/tools/libwimlib-imagex.so" ]]; then
    log "wimlib already built (cached)"
    return 0
  fi
  fetch "${WIMLIB_URL}" "wimlib-${WIMLIB_VERSION}.tar.gz"
  unpack "wimlib-${WIMLIB_VERSION}.tar.gz" "${BUILD_DIR}/wimlib"
  cd "${BUILD_DIR}/wimlib"
  ./configure --host="${TRIPLE}" --prefix="${PREFIX}" \
    --disable-shared --enable-static \
    --without-ntfs-3g --without-fuse \
    > "${LOG_DIR}/wimlib-configure.log" 2>&1 || { tail -50 "${LOG_DIR}/wimlib-configure.log" >&2; exit 1; }
  make -j"${JOBS}" > "${LOG_DIR}/wimlib-make.log" 2>&1 || { tail -60 "${LOG_DIR}/wimlib-make.log" >&2; exit 1; }

  # bin_PROGRAMS lives in the top level Makefile.am; libtool leaves the real ELF in .libs/.
  package_tool "wimlib-imagex" "${BUILD_DIR}/wimlib/wimlib-imagex"

  # wimlib-imagex dispatches on argv[0] (wiminfo, wimapply, wimextract, wimdir …). The app calls it
  # with explicit subcommands, so one binary is enough — advertise that clearly in the log.
  log "wimlib-imagex packaged (supports: info, dir, extract, apply, capture, verify)"
}

main() {
  log "building wimlib for ${ABI}"
  build_wimlib
  log "wimlib done"
}

main "$@"
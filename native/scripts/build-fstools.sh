#!/usr/bin/env bash
# Builds the filesystem toolchain: e2fsprogs (ext2/3/4), dosfstools (FAT),
# mtools (FAT editing) and ntfsprogs (NTFS).
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

build_e2fsprogs() {
  if [[ -f "${OUT_DIR}/${ABI}/tools/libmke2fs.so" ]]; then
    log "e2fsprogs already built (cached)"
    return 0
  fi
  fetch "${E2FSPROGS_URL}" "e2fsprogs-${E2FSPROGS_VERSION}.tar.xz" || \
    fetch "https://www.kernel.org/pub/linux/kernel/people/tytso/e2fsprogs/v${E2FSPROGS_VERSION}/e2fsprogs-${E2FSPROGS_VERSION}.tar.xz" "e2fsprogs-${E2FSPROGS_VERSION}.tar.xz"
  unpack "e2fsprogs-${E2FSPROGS_VERSION}.tar.xz" "${BUILD_DIR}/e2fsprogs"
  cd "${BUILD_DIR}/e2fsprogs"
  # Static linking against libext2fs keeps the shipped surface small: only the
  # executables below end up in the APK.
  # NOTE: --disable-libblkid makes configure look for an *external* blkid library
  # (util-linux) and abort with "external blkid library not found" when there is none.
  # e2fsprogs' own private copy is self-contained and is what we want here.
  ./configure --host="${TRIPLE}" --prefix="${PREFIX}" \
    --disable-elf-shlibs --disable-fuse2fs --disable-defrag \
    --disable-e2initrd-helper --disable-nls --disable-uuidd \
    --enable-libblkid --enable-libuuid \
    > "${LOG_DIR}/e2fsprogs-configure.log" 2>&1 || { tail -40 "${LOG_DIR}/e2fsprogs-configure.log" >&2; exit 1; }
  make -j"${JOBS}" > "${LOG_DIR}/e2fsprogs-make.log" 2>&1 || { tail -60 "${LOG_DIR}/e2fsprogs-make.log" >&2; exit 1; }

  package_tool "mke2fs"    "${BUILD_DIR}/e2fsprogs/misc/mke2fs"
  package_tool "e2fsck"    "${BUILD_DIR}/e2fsprogs/e2fsck/e2fsck"
  package_tool "debugfs"   "${BUILD_DIR}/e2fsprogs/debugfs/debugfs"
  package_tool "dumpe2fs"  "${BUILD_DIR}/e2fsprogs/misc/dumpe2fs"
  package_tool "resize2fs" "${BUILD_DIR}/e2fsprogs/resize/resize2fs"
  package_tool "tune2fs"   "${BUILD_DIR}/e2fsprogs/misc/tune2fs"
}

build_dosfstools() {
  if [[ -f "${OUT_DIR}/${ABI}/tools/libmkfs-fat.so" ]]; then
    log "dosfstools already built (cached)"
    return 0
  fi
  fetch "${DOSFSTOOLS_URL}" "dosfstools-${DOSFSTOOLS_VERSION}.tar.gz"
  unpack "dosfstools-${DOSFSTOOLS_VERSION}.tar.gz" "${BUILD_DIR}/dosfstools"
  cd "${BUILD_DIR}/dosfstools"
  ./autogen.sh > /dev/null 2>&1 || true
  ./configure --host="${TRIPLE}" --prefix="${PREFIX}" --disable-compat-symlinks --enable-fat=yes \
    > "${LOG_DIR}/dosfstools-configure.log" 2>&1 || { tail -40 "${LOG_DIR}/dosfstools-configure.log" >&2; exit 1; }
  make -j"${JOBS}" > "${LOG_DIR}/dosfstools-make.log" 2>&1 || { tail -60 "${LOG_DIR}/dosfstools-make.log" >&2; exit 1; }
  package_tool "mkfs-fat" "${BUILD_DIR}/dosfstools/src/mkfs.fat"
  package_tool "fsck-fat" "${BUILD_DIR}/dosfstools/src/fsck.fat"
}

build_mtools() {
  if [[ -f "${OUT_DIR}/${ABI}/tools/libmcopy.so" ]]; then
    log "mtools already built (cached)"
    return 0
  fi
  fetch "${MTOOLS_URL}" "mtools-${MTOOLS_VERSION}.tar.gz"
  unpack "mtools-${MTOOLS_VERSION}.tar.gz" "${BUILD_DIR}/mtools"
  cd "${BUILD_DIR}/mtools"
  ./configure --host="${TRIPLE}" --prefix="${PREFIX}" --disable-floppyd \
    > "${LOG_DIR}/mtools-configure.log" 2>&1 || { tail -40 "${LOG_DIR}/mtools-configure.log" >&2; exit 1; }
  make -j"${JOBS}" > "${LOG_DIR}/mtools-make.log" 2>&1 || { tail -60 "${LOG_DIR}/mtools-make.log" >&2; exit 1; }
  # mtools builds one tiny executable per command; the app ships the ones it needs.
  package_tool "mcopy" "${BUILD_DIR}/mtools/mcopy"
  package_tool "mdir"  "${BUILD_DIR}/mtools/mdir"
  package_tool "mdel"  "${BUILD_DIR}/mtools/mdel"
  package_tool "mmd"   "${BUILD_DIR}/mtools/mmd"
  package_tool "mren"  "${BUILD_DIR}/mtools/mren"
}

build_ntfsprogs() {
  if [[ -f "${OUT_DIR}/${ABI}/tools/libmkntfs.so" ]]; then
    log "ntfsprogs already built (cached)"
    return 0
  fi
  fetch "${NTFS3G_URL}" "ntfs-3g_ntfsprogs-${NTFS3G_VERSION}.tgz" || \
    fetch "https://github.com/tuxera/ntfs-3g/releases/download/${NTFS3G_VERSION}/ntfs-3g_ntfsprogs-${NTFS3G_VERSION}.tgz" "ntfs-3g_ntfsprogs-${NTFS3G_VERSION}.tgz"
  unpack "ntfs-3g_ntfsprogs-${NTFS3G_VERSION}.tgz" "${BUILD_DIR}/ntfs-3g"
  cd "${BUILD_DIR}/ntfs-3g"
  # ntfsprogs only: the FUSE mount helper is useless on unrooted Android.
  ./configure --host="${TRIPLE}" --prefix="${PREFIX}" \
    --disable-ntfs-3g --enable-ntfsprogs --disable-crypto --disable-nls \
    --enable-shared --disable-static \
    > "${LOG_DIR}/ntfs3g-configure.log" 2>&1 || { tail -40 "${LOG_DIR}/ntfs3g-configure.log" >&2; exit 1; }
  make -j"${JOBS}" > "${LOG_DIR}/ntfs3g-make.log" 2>&1 || { tail -60 "${LOG_DIR}/ntfs3g-make.log" >&2; exit 1; }

  package_tool "mkntfs"   "${BUILD_DIR}/ntfs-3g/ntfsprogs/mkntfs"
  package_tool "ntfsls"   "${BUILD_DIR}/ntfs-3g/ntfsprogs/ntfsls"
  package_tool "ntfscat"  "${BUILD_DIR}/ntfs-3g/ntfsprogs/ntfscat"
  package_tool "ntfscp"   "${BUILD_DIR}/ntfs-3g/ntfsprogs/ntfscp"
  package_tool "ntfsfix"  "${BUILD_DIR}/ntfs-3g/ntfsprogs/ntfsfix"
  package_tool "ntfsinfo" "${BUILD_DIR}/ntfs-3g/ntfsprogs/ntfsinfo"

  for f in "${PREFIX}"/lib/libntfs-3g.so*; do
    [[ -e "${f}" ]] || continue
    normalize_so "${f}" > /dev/null
  done
  fix_needed "${PREFIX}/lib"
  for f in "${PREFIX}"/lib/libntfs-3g.so; do [[ -e "${f}" ]] && package_lib "${f}"; done
}

main() {
  log "building filesystem tools for ${ABI}"
  build_e2fsprogs
  build_dosfstools
  build_mtools
  build_ntfsprogs
  log "filesystem tools done"
}

main "$@"
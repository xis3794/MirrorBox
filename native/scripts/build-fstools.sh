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

  # bionic only declares hasmntopt() from API 26 onwards and this toolchain targets API 24,
  # so the one call site (lib/ext2fs/ismounted.c, used to detect a read-only mount) cannot
  # compile. The shim reproduces bionic's own implementation: a substring search over the
  # option list of a struct mntent obtained from getmntent() (available since API 21).
  python3 - <<'PY'
import pathlib
f = pathlib.Path('lib/ext2fs/ismounted.c')
if f.exists():
    text = f.read_text()
    if 'mirrorbox_hasmntopt_shim' not in text:
        anchor = '#include "ext2fsP.h"'
        if anchor not in text:
            raise SystemExit('lib/ext2fs/ismounted.c: cannot find ' + anchor)
        shim = anchor + '''

/* mirrorbox_hasmntopt_shim: match a whole comma separated option (a bare strstr() would also
 * match "ro" inside "errors=remount-ro" and wrongly report a read/write filesystem as read-only). */
#if defined(HAVE_MNTENT_H) && defined(__ANDROID__) && (!defined(__ANDROID_API__) || __ANDROID_API__ < 26)
char *hasmntopt(const struct mntent *mnt, const char *opt)
{
    const char *p = mnt->mnt_opts;
    size_t len = strlen(opt);

    while (p && *p) {
        while (*p == ',') {
            p++;
        }
        if (!*p) {
            break;
        }
        if (!strncmp(p, opt, len) && (p[len] == 0 || p[len] == ',')) {
            return (char *)p;
        }
        p = strchr(p, ',');
        if (!p) {
            break;
        }
    }
    return NULL;
}
#endif'''
        f.write_text(text.replace(anchor, shim, 1))
        print('patched lib/ext2fs/ismounted.c: hasmntopt shim for API < 26')
PY

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

  # bionic only declares nl_langinfo() from API 26 onwards while this build targets API 24, but
  # dosfstools calls it (inside its HAVE_ICONV block) to learn the local charset used for FAT
  # long file names. Android's locale charset is always UTF-8, so reporting that keeps the
  # CP<codepage> <-> UTF-8 conversions correct.
  python3 - <<'PY'
import pathlib
f = pathlib.Path('src/charconv.c')
if not f.exists():
    raise SystemExit('dosfstools: src/charconv.c not found')
text = f.read_text()
if 'mirrorbox_android_nl_langinfo' not in text:
    anchor = '#include <langinfo.h>'
    if anchor not in text:
        raise SystemExit('dosfstools: cannot find ' + anchor)
    shim = anchor + '''

/* mirrorbox_android_nl_langinfo: see the note in build-fstools.sh */
#if defined(__ANDROID__) && (!defined(__ANDROID_API__) || __ANDROID_API__ < 26)
char *nl_langinfo(nl_item item)
{
    return item == CODESET ? (char *)"UTF-8" : (char *)"";
}
#endif'''
    f.write_text(text.replace(anchor, shim, 1))
    print('patched src/charconv.c: nl_langinfo shim for API < 26')
PY

  ./autogen.sh > /dev/null 2>&1 || true
  # dosfstools converts FAT long file names through iconv, and bionic only exports iconv from
  # API 28. The static GNU libiconv built by the dependency stage is therefore linked in
  # explicitly: without -I/-L the AM_ICONV probe cannot see it and the tools silently fall back
  # to the internal CP850 table (no Chinese/European file names on FAT).
  # NOTE: dosfstools 4.2 has no --enable-fat option any more (FAT support is always built).
  CFLAGS="${CFLAGS_COMMON} -I${PREFIX}/include" \
    LDFLAGS="${LDFLAGS_COMMON} -L${PREFIX}/lib" \
    LIBS="-liconv" \
    am_cv_func_iconv=yes am_cv_lib_iconv=yes am_cv_func_iconv_works=yes \
    ./configure --host="${TRIPLE}" --prefix="${PREFIX}" \
    --disable-compat-symlinks \
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

  # Same API 24 gap as dosfstools: mtools' iconv path (charsetConv.c) calls nl_langinfo(CODESET)
  # to find the local charset for FAT long file names. Android is always UTF-8.
  python3 - <<'PY'
import pathlib
f = pathlib.Path('charsetConv.c')
if not f.exists():
    raise SystemExit('mtools: charsetConv.c not found')
text = f.read_text()
if 'mirrorbox_android_nl_langinfo' not in text:
    anchor = '#include <langinfo.h>'
    if anchor not in text:
        raise SystemExit('mtools: cannot find ' + anchor)
    shim = anchor + '''

/* mirrorbox_android_nl_langinfo: see the note in build-fstools.sh */
#if defined(__ANDROID__) && (!defined(__ANDROID_API__) || __ANDROID_API__ < 26)
char *nl_langinfo(nl_item item)
{
    return item == CODESET ? (char *)"UTF-8" : (char *)"";
}
#endif'''
    f.write_text(text.replace(anchor, shim, 1))
    print('patched charsetConv.c: nl_langinfo shim for API < 26')
PY

  # AC_CHECK_LIB(iconv, iconv) only succeeds when the static GNU libiconv built by the dependency
  # stage is reachable. Without -L it never adds -liconv to LIBS, yet HAVE_ICONV_H is still
  # defined (bionic does ship an <iconv.h>, it merely hides the declarations below API 28), so
  # the link would fail on iconv_open. -I also makes the unguarded GNU <iconv.h> win over
  # bionic's API 28 annotated one.
  CFLAGS="${CFLAGS_COMMON} -I${PREFIX}/include" \
    LDFLAGS="${LDFLAGS_COMMON} -L${PREFIX}/lib" \
    LIBS="-liconv" \
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
  # The stamp is only written once every binary has been verified to be a real ELF executable.
  # An earlier revision of this script packaged libtool wrapper *scripts* (8 KB /bin/sh files)
  # as libmkntfs.so and friends, so "does libmkntfs.so exist" is not a valid cache test here.
  # The stamp lives outside tools/ on purpose: every entry in tools/ must be named lib*.so and be
  # an ELF file (see verify-package.sh).
  if [[ -f "${OUT_DIR}/${ABI}/.ntfsprogs-ok" ]]; then
    log "ntfsprogs already built (cached)"
    return 0
  fi
  fetch "${NTFS3G_URL}" "ntfs-3g_ntfsprogs-${NTFS3G_VERSION}.tgz" || \
    fetch "https://github.com/tuxera/ntfs-3g/releases/download/${NTFS3G_VERSION}/ntfs-3g_ntfsprogs-${NTFS3G_VERSION}.tgz" "ntfs-3g_ntfsprogs-${NTFS3G_VERSION}.tgz"
  unpack "ntfs-3g_ntfsprogs-${NTFS3G_VERSION}.tgz" "${BUILD_DIR}/ntfs-3g"
  cd "${BUILD_DIR}/ntfs-3g"
  # ntfsprogs only: the FUSE mount helper is useless on unrooted Android.
  # The build stays shared so that libntfs-3g.so can travel inside jniLibs; $ORIGIN is burned
  # into the binaries because that library ends up next to them in nativeLibraryDir.
  CFLAGS="${CFLAGS_COMMON} -I${PREFIX}/include" \
    LDFLAGS="${LDFLAGS_COMMON} -L${PREFIX}/lib -Wl,-rpath,\$ORIGIN" \
    ./configure --host="${TRIPLE}" --prefix="${PREFIX}" \
    --disable-ntfs-3g --enable-ntfsprogs --disable-crypto --disable-nls \
    --enable-shared --disable-static \
    > "${LOG_DIR}/ntfs3g-configure.log" 2>&1 || { tail -40 "${LOG_DIR}/ntfs3g-configure.log" >&2; exit 1; }
  make -j"${JOBS}" > "${LOG_DIR}/ntfs3g-make.log" 2>&1 || { tail -60 "${LOG_DIR}/ntfs3g-make.log" >&2; exit 1; }

  # package_tool() transparently picks the real binary out of .libs/ when libtool left a wrapper.
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
  if [[ ! -f "${PREFIX}/lib/libntfs-3g.so" ]]; then
    warn "ntfs-3g did not install libntfs-3g.so, which the ntfsprogs binaries need"
    exit 1
  fi
  package_lib "${PREFIX}/lib/libntfs-3g.so"
  touch "${OUT_DIR}/${ABI}/.ntfsprogs-ok"
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
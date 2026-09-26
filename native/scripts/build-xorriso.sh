#!/usr/bin/env bash
# Builds GNU xorriso for Android: ISO creation, extraction and editing
# (including El Torito BIOS/EFI boot catalogues through -as mkisofs).
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

fetch "${XORRISO_URL}" "xorriso-${XORRISO_VERSION}.tar.gz"
unpack "xorriso-${XORRISO_VERSION}.tar.gz" "${BUILD_DIR}/xorriso"

if [[ -f "${OUT_DIR}/${ABI}/tools/libxorriso.so" ]]; then
  log "xorriso already built (cached)"
  exit 0
fi
cd "${BUILD_DIR}/xorriso"

# xorriso asks nl_langinfo(CODESET) for the local charset used in ISO/Joliet name conversion
# (libisofs/util.c, xorriso/text_io.c and xorriso/lib_mgt.c). bionic declares that function only
# from API 26 onwards and Android's charset is always UTF-8, so every user gets the same shim.
# The definition must be "static": two of those files end up in the same binary.
python3 - <<'PY'
import pathlib

marker = 'mirrorbox_android_nl_langinfo'
shim = '''

/* mirrorbox_android_nl_langinfo: see the note in build-xorriso.sh */
#if defined(__ANDROID__) && (!defined(__ANDROID_API__) || __ANDROID_API__ < 26)
#include <langinfo.h>
static char *nl_langinfo(nl_item item)
{
    return item == CODESET ? (char *)"UTF-8" : (char *)"";
}
#endif'''

patched = 0
for path in sorted(pathlib.Path('.').rglob('*.c')):
    text = path.read_text(errors='ignore')
    if 'nl_langinfo' not in text or marker in text:
        continue
    anchor = '#include <langinfo.h>'
    if anchor not in text:
        # Fall back to the first include line so the definition still precedes every call.
        anchor = ''
        for line in text.splitlines():
            if line.startswith('#include'):
                anchor = line
                break
        if not anchor:
            raise SystemExit('cannot find an include anchor in ' + str(path))
    path.write_text(text.replace(anchor, anchor + shim, 1))
    print('patched ' + str(path) + ': nl_langinfo shim for API < 26')
    patched += 1

if patched == 0:
    raise SystemExit('xorriso: no nl_langinfo users found to patch')
PY

# xorriso brings its own libburn/libisofs; optical device support is irrelevant
# on Android, ACL/xattr support is unavailable on bionic.
#
# xorriso aborts configuration unless it can link iconv() (it uses it for Joliet/Rock Ridge
# name conversion). bionic only exports iconv from API 28, so the static GNU libiconv built by
# the dependency stage is linked instead — exactly the -I/-L/-liconv recipe its own error
# message suggests. Without it the only alternative is XORRISO_ASSUME_ICONV=yes, which would
# silently build an ISO writer with broken non-ASCII file names.
CFLAGS="${CFLAGS_COMMON} -I${PREFIX}/include" \
  CPPFLAGS="-I${PREFIX}/include" \
  LDFLAGS="${LDFLAGS_COMMON} -L${PREFIX}/lib" \
  LIBS="-liconv" \
  ./configure --host="${TRIPLE}" --prefix="${PREFIX}" \
  --disable-libacl --disable-xattr --disable-libreadline --disable-libedit \
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
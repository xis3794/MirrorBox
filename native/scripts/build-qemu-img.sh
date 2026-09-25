#!/usr/bin/env bash
# Builds qemu-img (and qemu-io) for Android: tools only, no system emulation.
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

fetch "${QEMU_URL}" "qemu-${QEMU_VERSION}.tar.xz"
unpack "qemu-${QEMU_VERSION}.tar.xz" "${BUILD_DIR}/qemu"

if [[ -f "${OUT_DIR}/${ABI}/tools/libqemu-img.so" ]]; then
  log "qemu-img already built (cached)"
  exit 0
fi

cd "${BUILD_DIR}/qemu"

# Android/bionic adaptations (the same class of fixes used by Limbo's QEMU 11 port):
#  - malloc_trim is not available on bionic
#  - several glibc-only helpers (execinfo / sys/sysmacros / ifaddrs quirks) need stubs
python3 - <<'PY'
import pathlib, re
root = pathlib.Path('.')
rcu = root / 'util/rcu.c'
if rcu.exists():
    text = rcu.read_text()
    text = text.replace('#ifdef CONFIG_MALLOC_TRIM', '#if 0 /* CONFIG_MALLOC_TRIM disabled on Android */')
    rcu.write_text(text)
osdep = root / 'util/oslib-posix.c'
if osdep.exists():
    text = osdep.read_text()
    if 'android_malloc_trim_stub' not in text:
        text = text.replace('#include <stdlib.h>', '#include <stdlib.h>\n/* android: bionic has no malloc_trim */\n#if defined(__ANDROID__)\nstatic inline int malloc_trim(size_t pad) { (void)pad; return 0; }\n#endif', 1)
        osdep.write_text(text)

# QEMU's mkvenv installs its in-tree python/qemu.qmp module with
# `pip --no-build-isolation -e`. Editable installs need a PEP 660 capable backend, which the
# runner's older setuptools does not provide; qemu-img does not care about editable semantics,
# so the flag is dropped and the module is installed normally.
mkvenv = root / 'python/scripts/mkvenv.py'
if mkvenv.exists():
    text = mkvenv.read_text()
    # '"-e"] + local_packages,' -> '] + local_packages,'  (keeps the list syntactically valid:
    # the previous element already ends with a comma)
    patched = text.replace('"-e"] + local_packages,', '] + local_packages,')
    if patched != text:
        mkvenv.write_text(patched)
        print('patched mkvenv.py: editable install downgraded to a normal install')
PY

export PKG_CONFIG_PATH="${PREFIX}/lib/pkgconfig:${PREFIX}/share/pkgconfig"
export PKG_CONFIG_LIBDIR="${PKG_CONFIG_PATH}"
export CFLAGS="${CFLAGS_COMMON} -I${PREFIX}/include -DANDROID"
export LDFLAGS="${LDFLAGS_COMMON} -L${PREFIX}/lib -Wl,-rpath,\$ORIGIN"

./configure \
  --cross-prefix="${TRIPLE}-" \
  --cc="${CC}" --cxx="${CXX}" \
  --host-cc=cc \
  --prefix="${PREFIX}" \
  --enable-tools \
  --disable-system \
  --disable-user \
  --disable-docs \
  --disable-guest-agent \
  --disable-werror \
  --disable-sdl --disable-gtk --disable-vnc \
  --disable-linux-aio --disable-linux-io-uring \
  --disable-capstone \
  --disable-gnutls --disable-nettle --disable-gcrypt \
  --enable-zstd \
  --with-coroutine=ucontext \
  --extra-cflags="-I${PREFIX}/include" \
  > "${LOG_DIR}/qemu-configure.log" 2>&1 || { tail -60 "${LOG_DIR}/qemu-configure.log" >&2; exit 1; }

make -j"${JOBS}" qemu-img qemu-io > "${LOG_DIR}/qemu-make.log" 2>&1 \
  || { tail -80 "${LOG_DIR}/qemu-make.log" >&2; exit 1; }

package_tool "qemu-img" "${BUILD_DIR}/qemu/build/qemu-img"
[[ -f "${BUILD_DIR}/qemu/build/qemu-io" ]] && package_tool "qemu-io" "${BUILD_DIR}/qemu/build/qemu-io" || true

log "qemu-img built: $("${OUT_DIR}/${ABI}/tools/libqemu-img.so" --version 2>/dev/null || echo 'cannot run on build host (expected for cross builds)')"

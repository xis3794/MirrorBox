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
# bionic deviations that are cheaper to paper over than to configure away.
#  - malloc_trim(): absent on bionic.
#  - shm_open()/shm_unlink(): bionic implements no POSIX shared memory objects at all
#    (_POSIX_SHARED_MEMORY_OBJECTS is "missing"): the symbols are absent from the headers *and*
#    from libc, so these definitions also have to satisfy the call sites. The only in-tree user,
#    qemu_shm_alloc(), creates an anonymous object, unlinks the name immediately and passes the fd
#    to a peer process — exactly what an unlinked memfd provides. Nothing in a tools-only build
#    calls it, but the symbols must still resolve when qemu-img is linked.
osdep = root / 'util/oslib-posix.c'
if osdep.exists():
    text = osdep.read_text()
    if 'mirrorbox_android_shims' not in text:
        anchor = '#include "qemu/osdep.h"'
        if anchor not in text:
            raise SystemExit('util/oslib-posix.c: cannot find ' + anchor)
        shims = anchor + '''

/* mirrorbox_android_shims */
#if defined(__ANDROID__)
#include <sys/syscall.h>
#include <asm/unistd.h>

/* bionic has no malloc_trim(). */
static inline int malloc_trim(size_t pad) { (void)pad; return 0; }

/* bionic has no shm_open()/shm_unlink(); see the note in build-qemu-img.sh. */
int shm_open(const char *name, int oflag, mode_t mode)
{
    (void)oflag;
    (void)mode;
#ifdef __NR_memfd_create
    return (int)syscall(__NR_memfd_create, name, 0);
#else
    errno = ENOSYS;
    return -1;
#endif
}

int shm_unlink(const char *name)
{
    (void)name;
    return 0;
}
#endif'''
        osdep.write_text(text.replace(anchor, shims, 1))
        print('patched util/oslib-posix.c: malloc_trim + shm_open/shm_unlink shims')

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

# QEMU generates a meson cross file that looks for '<triple>-pkg-config' (e.g.
# aarch64-linux-android-pkg-config) and refuses to continue when it is missing
# ("Pkg-config for machine host machine not found"). Ship that name in a private bin dir.
mkdir -p "${OUT_DIR}/${ABI}/bin"
for name in "${TRIPLE}-pkg-config" "aarch64-linux-android-pkg-config"; do
  cp -f "${SCRIPT_DIR}/pkg-config-wrapper.sh" "${OUT_DIR}/${ABI}/bin/${name}"
  chmod +x "${OUT_DIR}/${ABI}/bin/${name}"
done
export PATH="${OUT_DIR}/${ABI}/bin:${PATH}"
log "pkg-config shim installed: ${OUT_DIR}/${ABI}/bin/${TRIPLE}-pkg-config"

# bionic folds librt into libc, but QEMU's meson requires cc.find_library('rt').
# Provide a stub archive so the lookup succeeds; the symbols it would provide are already in libc.
RT_STUB="${PREFIX}/lib/librt.a"
if [[ ! -f "${RT_STUB}" ]]; then
  printf 'void mirrorbox_librt_stub(void) {}\n' > "${BUILD_DIR}/librt_stub.c"
  "${CC}" -c -o "${BUILD_DIR}/librt_stub.o" "${BUILD_DIR}/librt_stub.c"
  "${AR}" rcs "${RT_STUB}" "${BUILD_DIR}/librt_stub.o"
  log "created stub ${RT_STUB} (bionic has no separate librt)"
fi

# bionic has no makecontext/swapcontext, so QEMU's ucontext coroutine backend cannot work on
# Android; sigaltstack is the available (and thread-safe) alternative.
#
# The vhost stack is switched off below: libvhost-user (pulled in by qemu-storage-daemon, which
# --enable-tools builds) ships Linux UAPI copies of virtio_ring.h / virtio_types.h whose include
# guards differ from bionic's, so both definitions are visible at once and clang aborts with
# "redefinition of 'vring_desc'" / "typedef redefinition ... ('uint64_t' vs '__u64')".
# qemu-img needs none of it.
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
  --disable-vhost-user \
  --disable-vhost-user-blk-server \
  --disable-vhost-vdpa \
  --disable-vhost-kernel \
  --disable-vhost-net \
  --disable-vhost-crypto \
  --disable-sdl --disable-gtk --disable-vnc \
  --disable-linux-aio --disable-linux-io-uring \
  --disable-capstone \
  --disable-gnutls --disable-nettle --disable-gcrypt \
  --enable-zstd \
  --with-coroutine=sigaltstack \
  --extra-cflags="-I${PREFIX}/include" \
  > "${LOG_DIR}/qemu-configure.log" 2>&1 || { tail -60 "${LOG_DIR}/qemu-configure.log" >&2; exit 1; }

# Build only the two binaries we actually ship. QEMU's in-tree `GNUmakefile` delegates
# `make <target>` to the build dir but only forwards the *target list* through a wrapper whose
# default rule runs ninja without any target — i.e. `make qemu-img qemu-io` silently builds the
# entire tree (581 ninja targets, including the libvhost-user subproject that aborts on bionic).
# Invoking ninja directly with explicit targets keeps the build small and predictable.
ninja -C "${BUILD_DIR}/qemu/build" -j"${JOBS}" qemu-img qemu-io > "${LOG_DIR}/qemu-make.log" 2>&1 \
  || { tail -80 "${LOG_DIR}/qemu-make.log" >&2; exit 1; }

package_tool "qemu-img" "${BUILD_DIR}/qemu/build/qemu-img"
[[ -f "${BUILD_DIR}/qemu/build/qemu-io" ]] && package_tool "qemu-io" "${BUILD_DIR}/qemu/build/qemu-io" || true

log "qemu-img built: $("${OUT_DIR}/${ABI}/tools/libqemu-img.so" --version 2>/dev/null || echo 'cannot run on build host (expected for cross builds)')"

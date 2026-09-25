#!/usr/bin/env bash
# Builds the complete MirrorBox native toolchain for one ABI and stages it for
# both GitHub Releases (tar.gz) and APK packaging (jniLibs).
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

TARGETS="${TARGETS:-deps qemu fstools xorriso}"

mkdir -p "${OUT_DIR}/${ABI}/tools" "${LOG_DIR}"

for target in ${TARGETS}; do
  case "${target}" in
    deps)    "${SCRIPT_DIR}/build-deps.sh" ;;
    qemu)    "${SCRIPT_DIR}/build-qemu-img.sh" ;;
    fstools) "${SCRIPT_DIR}/build-fstools.sh" ;;
    xorriso) "${SCRIPT_DIR}/build-xorriso.sh" ;;
    *) warn "unknown target ${target}" ;;
  esac
done

# The dependency libraries live in PREFIX/lib and must travel with the tools.
for f in "${PREFIX}"/lib/lib*.so; do
  [[ -e "${f}" ]] || continue
  package_lib "${f}"
done

"${SCRIPT_DIR}/verify-package.sh"

TOOLS_DIR="${OUT_DIR}/${ABI}/tools"
ARCHIVE="${OUT_DIR}/mirrorbox-tools-${ABI}.tar.gz"
( cd "${TOOLS_DIR}" && tar -czf "${ARCHIVE}" . )
sha256sum "${ARCHIVE}" > "${ARCHIVE}.sha256"

JNI_DIR="${NATIVE_DIR}/../app/src/main/jniLibs/${ABI}"
mkdir -p "${JNI_DIR}"
cp -f "${TOOLS_DIR}"/lib*.so "${JNI_DIR}/"
log "staged $(ls "${JNI_DIR}" | wc -l) files into ${JNI_DIR}"
log "archive: ${ARCHIVE} ($(du -h "${ARCHIVE}" | cut -f1))"
#!/usr/bin/env bash
# Fails the build when a packaged file would not be shipped by Android:
# every entry must be named lib<name>.so (no version suffixes, no bare executables).
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

TOOLS_DIR="${OUT_DIR}/${ABI}/tools"
FAILED=0

log "verifying ${TOOLS_DIR}"
shopt -s nullglob
for f in "${TOOLS_DIR}"/*; do
  base="$(basename "${f}")"
  if [[ ! "${base}" =~ ^lib.+\.so$ ]]; then
    warn "illegal packaged name: ${base} (must match lib*.so)"
    FAILED=1
  fi
  if file "${f}" | grep -qi 'ELF'; then
    :
  else
    warn "not an ELF file: ${base}"
    FAILED=1
  fi
done

# No remaining versioned SONAME anywhere in the package.
if ls "${TOOLS_DIR}"/lib*.so.* >/dev/null 2>&1; then
  warn "versioned shared objects still present:"
  ls "${TOOLS_DIR}"/lib*.so.* >&2
  FAILED=1
fi

count=$(ls "${TOOLS_DIR}"/*.so 2>/dev/null | wc -l)
log "package contains ${count} files"
total=$(du -sh "${TOOLS_DIR}" | cut -f1)
log "total size ${total}"

if [[ "${FAILED}" == "1" ]]; then
  warn "package verification failed"
  exit 1
fi
log "package verification passed"
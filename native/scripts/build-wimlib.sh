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

  # bionic only declares glob()/globfree() from API 28 onwards, and wimlib needs them to expand
  # reference-image patterns (src/reference.c is the only user). A small implementation is enough:
  # the app passes either a plain path (which must be returned when it exists) or a single level
  # wildcard. It is static plus macro-redirected so it can never clash with libc's version.
  python3 - <<'PY'
import pathlib
f = pathlib.Path('src/reference.c')
if not f.exists():
    raise SystemExit('wimlib: src/reference.c not found')
text = f.read_text()
if 'mirrorbox_android_glob' not in text:
    anchor = '#include "wimlib/glob.h"'
    if anchor not in text:
        raise SystemExit('wimlib: cannot find ' + anchor)
    shim = anchor + '''

/* mirrorbox_android_glob: see the note in build-wimlib.sh */
#if defined(__ANDROID__) && (!defined(__ANDROID_API__) || __ANDROID_API__ < 28)
#  include <dirent.h>
#  include <fnmatch.h>
#  include <stdio.h>
#  include <stdlib.h>
#  include <string.h>
#  include <sys/stat.h>

static void mirrorbox_globfree(glob_t *result);

static int
mirrorbox_glob(const char *pattern, int flags,
	       int (*errfunc)(const char *epath, int eerrno), glob_t *result)
{
	const char *slash;
	const char *dir;
	const char *base;
	char dirbuf[4096];
	char full[4096];
	DIR *d;
	struct dirent *ent;
	char **list = NULL;
	size_t count = 0;
	size_t i;

	(void)errfunc;
	memset(result, 0, sizeof(*result));
	result->gl_flags = flags;

	slash = strrchr(pattern, '/');
	if (slash != NULL) {
		size_t n = (size_t)(slash - pattern);
		if (n == 0)
			n = 1; /* "/" */
		if (n >= sizeof(dirbuf))
			return GLOB_NOSPACE;
		memcpy(dirbuf, pattern, n);
		dirbuf[n] = '\\0';
		dir = dirbuf;
		base = slash + 1;
	} else {
		dir = ".";
		base = pattern;
	}

	/* No wildcards: the pattern is just a path that has to exist. */
	if (strpbrk(base, "*?[") == NULL) {
		struct stat st;

		if (stat(pattern, &st) != 0)
			return GLOB_NOMATCH;
		list = malloc(2 * sizeof(char *));
		if (list == NULL)
			return GLOB_NOSPACE;
		list[0] = strdup(pattern);
		if (list[0] == NULL) {
			free(list);
			return GLOB_NOSPACE;
		}
		list[1] = NULL;
		result->gl_pathc = 1;
		result->gl_pathv = list;
		return 0;
	}

	d = opendir(dir);
	if (d == NULL)
		return GLOB_NOMATCH;

	while ((ent = readdir(d)) != NULL) {
		char **grown;

		if (ent->d_name[0] == '.' && base[0] != '.')
			continue;
		if (fnmatch(base, ent->d_name, 0) != 0)
			continue;

		/* Built with explicit bounds instead of snprintf so that the compiler can prove the
		 * buffer cannot overflow (wimlib builds with -Wall). */
		{
			size_t dlen = strlen(dir);
			size_t blen = strlen(ent->d_name);
			size_t pos = 0;
			int need_sep = (slash != NULL && dlen > 0 && dir[dlen - 1] != '/');

			if (dlen + (size_t)need_sep + blen + 1 > sizeof(full))
				continue;
			if (slash != NULL) {
				memcpy(full, dir, dlen);
				pos = dlen;
				if (need_sep)
					full[pos++] = '/';
			}
			memcpy(full + pos, ent->d_name, blen);
			full[pos + blen] = 0;
		}

		grown = realloc(list, (count + 2) * sizeof(char *));
		if (grown == NULL) {
			closedir(d);
			result->gl_pathc = count;
			result->gl_pathv = list;
			mirrorbox_globfree(result);
			return GLOB_NOSPACE;
		}
		list = grown;
		list[count] = strdup(full);
		if (list[count] == NULL) {
			closedir(d);
			result->gl_pathc = count;
			result->gl_pathv = list;
			mirrorbox_globfree(result);
			return GLOB_NOSPACE;
		}
		count++;
		list[count] = NULL;
	}
	closedir(d);

	if (count == 0) {
		free(list);
		return GLOB_NOMATCH;
	}
	result->gl_pathc = count;
	result->gl_pathv = list;
	(void)i;
	return 0;
}

static void
mirrorbox_globfree(glob_t *result)
{
	size_t i;

	if (result == NULL || result->gl_pathv == NULL)
		return;
	for (i = 0; i < result->gl_pathc; i++)
		free(result->gl_pathv[i]);
	free(result->gl_pathv);
	result->gl_pathv = NULL;
	result->gl_pathc = 0;
}

#  define glob mirrorbox_glob
#  define globfree mirrorbox_globfree
#endif'''
    f.write_text(text.replace(anchor, shim, 1))
    print('patched src/reference.c: minimal glob()/globfree() for bionic (API < 28)')
PY

  # bionic only declares futimes()/lutimes() from API 26 onwards, and src/unix_apply.c calls them
  # unconditionally in its (rarely taken) fallback path -- with clang an implicit declaration is a
  # hard error under C99+. Both are thin wrappers over utimensat(), which bionic has always had
  # (futimens() since API 19, utimensat() from the start), so supply local equivalents and
  # macro-redirect the calls. They are static, so a clash with libc is impossible.
  python3 - <<'PY'
import pathlib
f = pathlib.Path('src/unix_apply.c')
if not f.exists():
    raise SystemExit('wimlib: src/unix_apply.c not found')
text = f.read_text()
if 'mirrorbox_android_futimes' not in text:
    anchor = '#include <unistd.h>'
    if anchor not in text:
        raise SystemExit('wimlib: cannot find ' + anchor)
    shim = anchor + '''

/* mirrorbox_android_futimes: see the note in build-wimlib.sh */
#if defined(__ANDROID__) && (!defined(__ANDROID_API__) || __ANDROID_API__ < 26)
static void
mirrorbox_android_timeval_to_timespec(const struct timeval tv[2],
				      struct timespec ts[2])
{
	int i;

	for (i = 0; i < 2; i++) {
		ts[i].tv_sec = tv[i].tv_sec;
		if (tv[i].tv_usec < 0) {
			/* utimensat() wants tv_nsec in [0, 1e9), while timeval
			 * allows a negative fractional part. */
			ts[i].tv_sec -= 1;
			ts[i].tv_nsec = 1000000000L + tv[i].tv_usec * 1000L;
		} else {
			ts[i].tv_nsec = tv[i].tv_usec * 1000L;
		}
	}
}

static int
mirrorbox_android_futimes(int fd, const struct timeval tv[2])
{
	struct timespec ts[2];

	if (tv == NULL)
		return futimens(fd, NULL);
	mirrorbox_android_timeval_to_timespec(tv, ts);
	return futimens(fd, ts);
}

static int
mirrorbox_android_lutimes(const char *path, const struct timeval tv[2])
{
	struct timespec ts[2];

	if (tv == NULL)
		return utimensat(AT_FDCWD, path, NULL, AT_SYMLINK_NOFOLLOW);
	mirrorbox_android_timeval_to_timespec(tv, ts);
	return utimensat(AT_FDCWD, path, ts, AT_SYMLINK_NOFOLLOW);
}

#  define futimes mirrorbox_android_futimes
#  define lutimes mirrorbox_android_lutimes
#endif'''
    f.write_text(text.replace(anchor, shim, 1))
    print('patched src/unix_apply.c: futimes()/lutimes() for bionic (API < 26)')
PY

  # Force the modern timestamp path: in a cross build the AC_CHECK_FUNCS link probe can silently
  # fail, after which wimlib falls back to futimes()/lutimes(). Both futimens() and utimensat() are
  # declared for API 24 and live in libc, so pinning the cache variables is safe and exact.
  ac_cv_func_futimens=yes ac_cv_func_utimensat=yes \
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
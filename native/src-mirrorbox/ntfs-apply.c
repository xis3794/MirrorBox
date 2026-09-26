/*
 * mirrorbox-ntfs-apply - 把一棵目录树写进 NTFS 镜像文件（不挂载、单进程，libntfs-3g）。
 *
 * 为什么需要它：
 *   Android 上普通应用不能挂载 FUSE，所以 wimlib 无法"直接释放到 NTFS 卷"；而 ntfsprogs 的
 *   ntfscp 每个文件都要起一个进程（Windows 镜像动辄十万个文件，慢到不可用）。这个工具用
 *   libntfs-3g 的库 API 在一个进程里完成：建目录、建文件、写数据、设时间戳。
 *
 * 用法：
 *   mirrorbox-ntfs-apply <ntfs-image> <source-dir> [--label-from-source]
 *
 * 输出（stderr，供 App 解析进度）：
 *   PROGRESS files=<n> bytes=<n>
 *   DONE files=<n> bytes=<n> failures=<n>
 *
 * 许可：GPL-3.0（与 MirrorBox 一致），链接 GPL 的 libntfs-3g。
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <dirent.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#include <ntfs-3g/volume.h>
#include <ntfs-3g/dir.h>
#include <ntfs-3g/attrib.h>
#include <ntfs-3g/inode.h>
#include <ntfs-3g/unistr.h>
#include <ntfs-3g/ntfstime.h>
#include <ntfs-3g/misc.h>

static ntfs_volume *vol = NULL;
static long long files_written = 0;
static long long bytes_written = 0;
static int failures = 0;
static int quiet = 0;

#define BUF_SIZE (1u << 20)

static int name_to_ucs(const char *name, ntfschar **ucs, u8 *len)
{
	int n = ntfs_mbstoucs(name, ucs);
	if (n <= 0 || n > 255) {
		fprintf(stderr, "ERR name-encode %s\n", name);
		return -1;
	}
	*len = (u8)n;
	return 0;
}

static void set_times(ntfs_inode *ni, const char *path)
{
	struct stat st;
	if (stat(path, &st) != 0)
		return;
	/*
	 * ntfs_inode_set_times() 的第二个参数不是 time_t 而是 **4 个 ntfs_time 的二进制块**
	 * （creation / last data change / MFT change / access，各 8 字节）。
	 * 之前按头文件的注释传 time_t + NULL 会在库内部 memcpy(NULL) 直接崩溃。
	 */
	ntfs_time t = timespec2ntfs(st.st_mtim);
	u64 times[4];
	times[0] = t;	/* creation */
	times[1] = t;	/* last data change */
	times[2] = t;	/* MFT change */
	times[3] = t;	/* last access */
	(void)ntfs_inode_set_times(ni, (const char *)times, sizeof(times), 0);
}

static int write_one_file(ntfs_inode *dir_ni, const char *name, const char *path)
{
	ntfschar *ucs = NULL;
	u8 ulen = 0;
	if (name_to_ucs(name, &ucs, &ulen) != 0) {
		failures++;
		return -1;
	}
	ntfs_inode *ni = ntfs_create(dir_ni, 0, ucs, ulen, S_IFREG);
	free(ucs);
	if (!ni) {
		fprintf(stderr, "ERR create-file %s: %s\n", path, strerror(errno));
		failures++;
		return -1;
	}

	ntfs_attr *na = ntfs_attr_open(ni, AT_DATA, AT_UNNAMED, 0);
	if (!na) {
		fprintf(stderr, "ERR open-data %s: %s\n", path, strerror(errno));
		failures++;
		ntfs_inode_close(ni);
		return -1;
	}

	FILE *f = fopen(path, "rb");
	if (!f) {
		fprintf(stderr, "ERR open %s: %s\n", path, strerror(errno));
		failures++;
		ntfs_attr_close(na);
		ntfs_inode_close(ni);
		return -1;
	}

	static char *buf = NULL;
	if (!buf)
		buf = malloc(BUF_SIZE);
	if (!buf) {
		fprintf(stderr, "ERR out-of-memory\n");
		fclose(f);
		ntfs_attr_close(na);
		ntfs_inode_close(ni);
		exit(3);
	}

	s64 off = 0;
	int bad = 0;
	size_t n;
	while ((n = fread(buf, 1, BUF_SIZE, f)) > 0) {
		s64 w = ntfs_attr_pwrite(na, off, (s64)n, buf);
		if (w != (s64)n) {
			fprintf(stderr, "ERR write %s at %lld\n", path, (long long)off);
			bad = 1;
			break;
		}
		off += (s64)n;
	}
	fclose(f);
	/* 数据写完要 sync 属性，让 MFT 里的 size 落盘 */
	ntfs_attr_close(na);

	if (bad) {
		failures++;
	} else {
		set_times(ni, path);
		files_written++;
		bytes_written += (long long)off;
		if (!quiet && (files_written % 500) == 0)
			fprintf(stderr, "PROGRESS files=%lld bytes=%lld\n", files_written, bytes_written);
	}

	ntfs_inode_close(ni);
	return bad ? -1 : 0;
}
/* ------------------------------------------------------------------ 单文件读写（BCD 用） */

/* 从根开始逐段解析路径（大小写不敏感，/ 与 \ 都接受）。 */
static ntfs_inode *resolve_path(const char *path)
{
	char buf[1024];
	snprintf(buf, sizeof(buf), "%s", path);

	ntfs_inode *cur = ntfs_pathname_to_inode(vol, NULL, "/");
	if (!cur)
		return NULL;

	char *save = NULL;
	char *part = strtok_r(buf, "/\\", &save);
	while (part) {
		if (!*part || !strcmp(part, ".")) {
			part = strtok_r(NULL, "/\\", &save);
			continue;
		}
		u64 inum = ntfs_inode_lookup_by_mbsname(cur, part);
		if (!inum) {
			ntfs_inode_close(cur);
			errno = ENOENT;
			return NULL;
		}
		ntfs_inode *next = ntfs_inode_open(vol, inum);
		ntfs_inode_close(cur);
		if (!next) {
			errno = EIO;
			return NULL;
		}
		cur = next;
		part = strtok_r(NULL, "/\\", &save);
	}
	return cur;
}

/* 把 guest 文件读出来写到 host 文件。 */
static int mode_dump(const char *guest, const char *host)
{
	ntfs_inode *ni = resolve_path(guest);
	if (!ni) {
		fprintf(stderr, "ERR open %s: %s\n", guest, strerror(errno));
		return 5;
	}
	ntfs_attr *na = ntfs_attr_open(ni, AT_DATA, AT_UNNAMED, 0);
	if (!na) {
		fprintf(stderr, "ERR no-data %s\n", guest);
		ntfs_inode_close(ni);
		return 5;
	}
	FILE *out = fopen(host, "wb");
	if (!out) {
		fprintf(stderr, "ERR create %s: %s\n", host, strerror(errno));
		ntfs_attr_close(na);
		ntfs_inode_close(ni);
		return 5;
	}
	char *buf = malloc(BUF_SIZE);
	s64 size = na->data_size;
	s64 off = 0;
	int rc = 0;
	while (off < size) {
		s64 want = (size - off < (s64)BUF_SIZE) ? (size - off) : (s64)BUF_SIZE;
		s64 got = ntfs_attr_pread(na, off, want, buf);
		if (got <= 0) {
			rc = 5;
			break;
		}
		if (fwrite(buf, 1, (size_t)got, out) != (size_t)got) {
			rc = 5;
			break;
		}
		off += got;
	}
	free(buf);
	fclose(out);
	ntfs_attr_close(na);
	ntfs_inode_close(ni);
	fprintf(stderr, "DUMP %s -> %s (%lld bytes)\n", guest, host, (long long)off);
	return rc;
}

/* 把 host 文件写回 guest 文件（存在则覆盖，不存在则创建）。 */
static int mode_put(const char *host, const char *guest)
{
	FILE *in = fopen(host, "rb");
	if (!in) {
		fprintf(stderr, "ERR open %s: %s\n", host, strerror(errno));
		return 6;
	}

	ntfs_inode *ni = resolve_path(guest);
	if (!ni) {
		/* 目标不存在：在父目录里创建 */
		char dirbuf[1024];
		snprintf(dirbuf, sizeof(dirbuf), "%s", guest);
		char *slash = strrchr(dirbuf, '/');
		char *bslash = strrchr(dirbuf, '\\');
		if (!slash || (bslash && bslash > slash))
			slash = bslash;
		const char *name = guest;
		ntfs_inode *dir_ni = NULL;
		if (slash) {
			*slash = 0;
			name = slash + 1;
			dir_ni = resolve_path(dirbuf);
		} else {
			dir_ni = ntfs_pathname_to_inode(vol, NULL, "/");
		}
		if (!dir_ni) {
			fprintf(stderr, "ERR parent of %s not found\n", guest);
			fclose(in);
			return 6;
		}
		ntfschar *ucs = NULL;
		int ulen = ntfs_mbstoucs(name, &ucs);
		ni = (ulen > 0) ? ntfs_create(dir_ni, 0, ucs, (u8)ulen, S_IFREG) : NULL;
		free(ucs);
		ntfs_inode_close(dir_ni);
		if (!ni) {
			fprintf(stderr, "ERR create %s\n", guest);
			fclose(in);
			return 6;
		}
	}

	ntfs_attr *na = ntfs_attr_open(ni, AT_DATA, AT_UNNAMED, 0);
	if (!na) {
		fprintf(stderr, "ERR no-data %s\n", guest);
		ntfs_inode_close(ni);
		fclose(in);
		return 6;
	}

	/* 先把长度归零，再按块写入（避免旧数据残留） */
	if (ntfs_attr_truncate(na, 0) != 0)
		fprintf(stderr, "WARN truncate failed\n");

	char *buf = malloc(BUF_SIZE);
	s64 off = 0;
	int rc = 0;
	size_t n;
	while ((n = fread(buf, 1, BUF_SIZE, in)) > 0) {
		if (ntfs_attr_pwrite(na, off, (s64)n, buf) != (s64)n) {
			fprintf(stderr, "ERR write %s at %lld\n", guest, (long long)off);
			rc = 6;
			break;
		}
		off += (s64)n;
	}
	free(buf);
	fclose(in);
	ntfs_attr_close(na);
	ntfs_inode_close(ni);
	fprintf(stderr, "PUT %s -> %s (%lld bytes)\n", host, guest, (long long)off);
	return rc;
}

/* 在内存里把 UTF-16LE 的 \Device\HarddiskVolumeN 改成 target。 */
static int patch_bcd_bytes(char *data, size_t size, int target, char *report, size_t report_size)
{
	static const char PREFIX[] = "\\Device\\HarddiskVolume";
	const size_t plen = sizeof(PREFIX) - 1;
	int changed = 0;
	size_t i = 0;

	while (i + plen * 2 + 2 <= size) {
		size_t j = 0;
		int match = 1;
		for (j = 0; j < plen; j++) {
			if ((unsigned char)data[i + j * 2] != (unsigned char)PREFIX[j] ||
			    data[i + j * 2 + 1] != 0) {
				match = 0;
				break;
			}
		}
		if (!match) {
			i += 2;
			continue;
		}
		size_t k = i + plen * 2;
		int digits = 0;
		int volume = 0;
		while (k + 1 < size && data[k + 1] == 0 && data[k] >= '0' && data[k] <= '9') {
			volume = volume * 10 + (data[k] - '0');
			digits++;
			k += 2;
		}
		if (digits == 0) {
			i += 2;
			continue;
		}
		char rep[16];
		int rlen = snprintf(rep, sizeof(rep), "%d", target);
		size_t pos = i + plen * 2;
		for (j = 0; j < (size_t)rlen && pos + 1 < size; j++) {
			data[pos] = rep[j];
			data[pos + 1] = 0;
			pos += 2;
		}
		while (pos < i + plen * 2 + (size_t)digits * 2) {
			data[pos] = 0;
			data[pos + 1] = 0;
			pos += 2;
		}
		if (!changed)
			snprintf(report, report_size, "%s%d -> %s%d", PREFIX, volume, PREFIX, target);
		else
			snprintf(report + strlen(report), report_size - strlen(report),
				 ", %s%d -> %s%d", PREFIX, volume, PREFIX, target);
		changed++;
		i = pos;
	}
	return changed;
}

/* 一步到位：读出 \Boot\BCD → 改卷号 → 写回。 */
static int mode_fix_bcd(int target)
{
	static const char *candidates[] = { "\\Boot\\BCD", "Boot\\BCD", "/Boot/BCD", "Boot/BCD" };
	ntfs_inode *ni = NULL;
	const char *used = NULL;
	for (size_t c = 0; c < sizeof(candidates) / sizeof(candidates[0]) && !ni; c++) {
		ni = resolve_path(candidates[c]);
		if (ni)
			used = candidates[c];
	}
	if (!ni) {
		fprintf(stderr, "ERR no \\Boot\\BCD in this volume\n");
		return 7;
	}

	ntfs_attr *na = ntfs_attr_open(ni, AT_DATA, AT_UNNAMED, 0);
	if (!na) {
		ntfs_inode_close(ni);
		return 7;
	}

	s64 size = na->data_size;
	if (size <= 0 || size > (s64)(64 << 20)) {
		fprintf(stderr, "ERR BCD size looks wrong (%lld)\n", (long long)size);
		ntfs_attr_close(na);
		ntfs_inode_close(ni);
		return 7;
	}

	char *data = malloc((size_t)size);
	s64 got = 0;
	while (got < size) {
		s64 r = ntfs_attr_pread(na, got, size - got, data + got);
		if (r <= 0)
			break;
		got += r;
	}
	if (got != size) {
		fprintf(stderr, "ERR short read (%lld/%lld)\n", (long long)got, (long long)size);
		free(data);
		ntfs_attr_close(na);
		ntfs_inode_close(ni);
		return 7;
	}

	char report[512] = { 0 };
	int changed = patch_bcd_bytes(data, (size_t)size, target, report, sizeof(report));
	if (changed == 0) {
		fprintf(stderr, "NO-STRINGS %s (%lld bytes): BCD uses binary device elements\n",
			used, (long long)size);
		free(data);
		ntfs_attr_close(na);
		ntfs_inode_close(ni);
		return 8;
	}

	if (ntfs_attr_pwrite(na, 0, size, data) != size) {
		fprintf(stderr, "ERR write-back failed\n");
		free(data);
		ntfs_attr_close(na);
		ntfs_inode_close(ni);
		return 7;
	}

	free(data);
	ntfs_attr_close(na);
	ntfs_inode_close(ni);
	fprintf(stderr, "FIXED %s: %s (%d refs)\n", used, report, changed);
	return 0;
}

static int walk(ntfs_inode *dir_ni, const char *src)
{
	DIR *d = opendir(src);
	if (!d) {
		fprintf(stderr, "ERR opendir %s: %s\n", src, strerror(errno));
		failures++;
		return -1;
	}
	struct dirent *e;
	while ((e = readdir(d)) != NULL) {
		if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, ".."))
			continue;

		char path[4096];
		if (snprintf(path, sizeof(path), "%s/%s", src, e->d_name) >= (int)sizeof(path)) {
			fprintf(stderr, "ERR path-too-long %s/%s\n", src, e->d_name);
			failures++;
			continue;
		}

		struct stat st;
		if (lstat(path, &st) != 0)
			continue;

		if (S_ISDIR(st.st_mode)) {
			ntfschar *ucs = NULL;
			u8 ulen = 0;
			if (name_to_ucs(e->d_name, &ucs, &ulen) != 0) {
				failures++;
				continue;
			}
			ntfs_inode *sub = ntfs_create(dir_ni, 0, ucs, ulen, S_IFDIR);
			free(ucs);
			if (!sub) {
				fprintf(stderr, "ERR mkdir %s: %s\n", path, strerror(errno));
				failures++;
				continue;
			}
			walk(sub, path);
			set_times(sub, path);
			ntfs_inode_close(sub);
		} else if (S_ISREG(st.st_mode)) {
			write_one_file(dir_ni, e->d_name, path);
		}
		/* 符号链接/设备节点：Windows 镜像里基本用不到，直接跳过 */
	}
	closedir(d);
	return 0;
}

int main(int argc, char **argv)
{
	const char *image = NULL;
	const char *source = NULL;
	const char *mode = NULL;
	const char *arg_a = NULL;
	const char *arg_b = NULL;
	int volume = 0;
	int i;

	for (i = 1; i < argc; i++) {
		if (!strcmp(argv[i], "--quiet")) {
			quiet = 1;
		} else if (!strcmp(argv[i], "--dump") && i + 2 < argc) {
			mode = "dump";
			arg_a = argv[++i];
			arg_b = argv[++i];
		} else if (!strcmp(argv[i], "--put") && i + 2 < argc) {
			mode = "put";
			arg_a = argv[++i];
			arg_b = argv[++i];
		} else if (!strcmp(argv[i], "--fix-bcd") && i + 1 < argc) {
			mode = "fixbcd";
			volume = atoi(argv[++i]);
		} else if (!image) {
			image = argv[i];
		} else if (!source) {
			source = argv[i];
		}
	}

	if (!image || (!source && !mode)) {
		fprintf(stderr,
			"usage: %s <ntfs-image> <source-dir> [--quiet]\n"
			"       %s <ntfs-image> --dump <guest-path> <host-file>\n"
			"       %s <ntfs-image> --put <host-file> <guest-path>\n"
			"       %s <ntfs-image> --fix-bcd <volume-number>\n",
			argv[0], argv[0], argv[0], argv[0]);
		return 2;
	}

	vol = ntfs_mount(image, 0);
	if (!vol) {
		fprintf(stderr, "ERR mount %s: %s\n", image, strerror(errno));
		return 3;
	}

	int rc = 0;
	if (mode) {
		if (!strcmp(mode, "dump"))
			rc = mode_dump(arg_a, arg_b);
		else if (!strcmp(mode, "put"))
			rc = mode_put(arg_a, arg_b);
		else
			rc = mode_fix_bcd(volume);
		if (ntfs_umount(vol, FALSE) != 0) {
			fprintf(stderr, "ERR umount\n");
			if (rc == 0)
				rc = 1;
		}
		return rc;
	}

	ntfs_inode *root = ntfs_pathname_to_inode(vol, NULL, "/");
	if (!root) {
		fprintf(stderr, "ERR root-inode\n");
		ntfs_umount(vol, FALSE);
		return 4;
	}

	walk(root, source);
	ntfs_inode_close(root);

	/* force=FALSE：把元数据刷盘并清掉 dirty 标志，避免 Windows 首次挂载就 chkdsk */
	if (ntfs_umount(vol, FALSE) != 0) {
		fprintf(stderr, "ERR umount\n");
		failures++;
	}

	fprintf(stderr, "DONE files=%lld bytes=%lld failures=%d\n",
		files_written, bytes_written, failures);
	return failures ? 1 : 0;
}

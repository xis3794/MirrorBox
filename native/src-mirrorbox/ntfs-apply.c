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
	int i;

	for (i = 1; i < argc; i++) {
		if (!strcmp(argv[i], "--quiet"))
			quiet = 1;
		else if (!image)
			image = argv[i];
		else if (!source)
			source = argv[i];
	}

	if (!image || !source) {
		fprintf(stderr, "usage: %s <ntfs-image> <source-dir> [--quiet]\n", argv[0]);
		return 2;
	}

	vol = ntfs_mount(image, 0);
	if (!vol) {
		fprintf(stderr, "ERR mount %s: %s\n", image, strerror(errno));
		return 3;
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

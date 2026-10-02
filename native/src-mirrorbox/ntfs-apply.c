/*
 * mirrorbox-ntfs - NTFS 目录树写入 / 单文件读取（不挂载、单进程，libntfs-3g）。
 *
 * 为什么需要它：
 *   Android 上普通应用不能挂载 FUSE，所以 wimlib 无法"直接释放到 NTFS 卷"；而 ntfsprogs 的
 *   ntfscp 每个文件都要起一个进程（Windows 镜像动辄十万个文件，慢到不可用）。这个工具用
 *   libntfs-3g 的库 API 在一个进程里完成：建目录、建文件、写数据、设时间戳。
 *
 * 用法：
 *   mirrorbox-ntfs <ntfs-image> <source-dir> [--quiet]        把目录树写进卷
 *   mirrorbox-ntfs <ntfs-image> [--regions <file>] --dump <guest-path> <host-file>
 *   mirrorbox-ntfs <ntfs-image> [--regions <file>] --put <host-file> <guest-path>
 *
 * --regions <file>：按需区域模式（只读）。文件每行 "off len"（十进制字节），表示调用方
 *   保证镜像文件里这些范围与真实卷内容一致；范围之外的数据不可信（稀疏空洞）。工具只会读
 *   这些区域，越界的读取会被记下来，退出时用 `NEED off len` 汇报并以退出码 10 结束；调用方
 *   补齐后重跑即可。这样即使卷的元数据散布在整个卷里（mkntfs 就把 $MFTMirr/$LogFile 放在卷
 *   中部、$UpCase/$Bitmap/$AttrDef 放在 1/8 处），也只提取真正需要的那一两百 KB。
 *
 * 输出（stdout/stderr 都可能，App 侧按前缀解析）：
 *   MAP <file-offset> <disk-offset> <len>   被读文件数据的物理位置（用于就地改写）
 *   NEED <off> <len>                        还需要调用方补齐的区域
 *   PROGRESS files=<n> bytes=<n>            （目录树写入进度）
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
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#include <ntfs-3g/device.h>
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
static long long links_expanded = 0; /* 符号链接 → 展开成普通文件的个数 */
static long long links_skipped = 0;  /* 指向目录的链接（junction）：跳过 */
static int failures = 0;
static int quiet = 0;

/*
 * 释放树的根目录（= 第二个命令行参数）。wimlib 的「link() 失败就建符号链接」回退有两种写法：
 *   ① 绝对路径（App 传的是绝对 staging 目录时，正常情况）；
 *   ② **相对于释放根目录**的路径（例如 `dst/Windows/inf/machine.inf`）。
 * ② 这种从链接自己所在目录去解析是**断的**（内核按链接所在目录解析相对目标），
 * 于是别名会被当成断链跳过 —— DriverStore 里的 .inf/.sys 就这么丢的。
 * resolve_link_via_root() 用「根目录 + 目标」再兜一次。
 */
static const char *g_root = "";

#define BUF_SIZE (1u << 20)

/** 镜像文件的 fd（`--regions` 按需模式与卸载后的记录直写都用它）。 */
static int dev_fd = -1;

/* ------------------------------------------------------------------ DOS 文件属性 */

/*
 * Windows 里 `desktop.ini`、`Thumbs.db` 这类文件是「隐藏+系统」属性；Explorer 只有在
 * **所在目录带「系统」属性** 时才会把它当成"文件夹视图配置"来解析。
 *
 * 我们自己的 NTFS 写入器原先完全不设 DOS 属性（wimlib 提取到暂存目录时也没法保留 ——
 * Linux 文件系统表达不了），结果释放出来的 Windows 里 `desktop.ini` 变成**可见的普通文本文件**
 * （容易被误双击打开成记事本），文件夹视图设置也不生效。所以这里补上最小的必要集合。
 */
#define MB_ATTR_READONLY 0x0001u
#define MB_ATTR_HIDDEN   0x0002u
#define MB_ATTR_SYSTEM   0x0004u

#define SI_FILE_ATTRIBUTES 0x20 /* $STANDARD_INFORMATION 数据里 file_attributes 的偏移 */

/* 定义在文件后面，这里先声明 */
static ntfs_inode *resolve_path(const char *path);
static s64 mft_record_disk_offset(u64 mft_no);

static int name_is(const char *name, const char *lower)
{
	size_t i;

	for (i = 0; name[i] && lower[i]; i++) {
		char c = name[i];

		if (c >= 'A' && c <= 'Z')
			c = (char)(c - 'A' + 'a');
		if (c != lower[i])
			return 0;
	}
	return name[i] == '\0' && lower[i] == '\0';
}

/*
 * 读/写 MFT 记录里 $STANDARD_INFORMATION 的 file_attributes。
 *
 * 不走库 API 是因为 libntfs-3g 没暴露"设置属性"的接口；记录里属性布局是固定的：
 * $SI 是常驻属性，数据偏移 0x20 起的 4 字节就是 DOS 属性位。
 */
static u32 *mb_file_attributes_ptr(ntfs_inode *ni)
{
	u8 *m;
	u16 ao;

	if (!ni || !ni->mrec)
		return NULL;
	m = (u8 *)ni->mrec;
	memcpy(&ao, m + 0x14, 2);
	while ((u32)(ao + 24) <= vol->mft_record_size) {
		u32 type, alen;
		u8 nonres, namelen;

		memcpy(&type, m + ao, 4);
		memcpy(&alen, m + ao + 4, 4);
		nonres = m[ao + 8];
		namelen = m[ao + 9];
		if (type == 0xffffffff || alen < 24)
			break;
		if (type == 0x10 && !nonres && namelen == 0) { /* $STANDARD_INFORMATION */
			u16 voff;
			memcpy(&voff, m + ao + 0x14, 2);
			return (u32 *)(m + ao + voff + SI_FILE_ATTRIBUTES);
		}
		ao = (u16)(ao + alen);
	}
	return NULL;
}

/*
 * 需要补 DOS 属性的「客户机路径」清单。
 *
 * 为什么不在写文件时顺手设：libntfs-3g 收尾（ntfs_inode_close / 卸载刷盘）会用自己的
 * 副本覆盖记录，实测写进去的属性会丢。所以先收集，等整棵树写完、卸载之前统一重开 inode
 * 再设一次。
 */
#define MB_ATTR_MAX 8192
static char *mb_attr_paths[MB_ATTR_MAX];
static u32 mb_attr_bits[MB_ATTR_MAX];
static s64 mb_attr_rec_off[MB_ATTR_MAX];
static s64 mb_attr_field_off[MB_ATTR_MAX];
static int mb_attr_n;

static void mb_attr_add(const char *guest, u32 bits)
{
	if (mb_attr_n >= MB_ATTR_MAX)
		return;
	mb_attr_paths[mb_attr_n] = strdup(guest);
	mb_attr_bits[mb_attr_n] = bits;
	mb_attr_n++;
}

static void mb_attr_resolve(void)
{
	int i;

	for (i = 0; i < mb_attr_n; i++) {
		const char *guest = mb_attr_paths[i];
		ntfs_inode *ni;
		u32 *p;

		mb_attr_rec_off[i] = -1;
		if (!guest)
			continue;
		ni = resolve_path(*guest ? guest : "/");
		if (!ni) {
			fprintf(stderr, "WARN cannot reopen %s for attributes: %s\n",
				guest, strerror(errno));
			continue;
		}
		p = mb_file_attributes_ptr(ni);
		if (p) {
			mb_attr_rec_off[i] = mft_record_disk_offset(ni->mft_no);
			mb_attr_field_off[i] = (s64)((char *)p - (char *)ni->mrec);
		}
		ntfs_inode_close(ni);
	}
}

/*
 * 卸载**之后**再改磁盘上的记录。
 *
 * 不能借着库改：libntfs-3g 收尾（inode close / 卸载刷盘）会用它自己的副本覆盖 MFT 记录，
 * 实测写进去的属性会被抹掉。而 `$STANDARD_INFORMATION` 里的这个字段位于记录前部、不受
 * USA（最后两个字节）保护，所以直接 pwrite 一组小改动是安全且确定的。
 */
static void mb_attr_write_raw(void)
{
	int i;

	for (i = 0; i < mb_attr_n; i++) {
		s64 rec = mb_attr_rec_off[i];
		u8 *buf;
		u32 val;
		s64 n;

		if (rec < 0 || !mb_attr_paths[i])
			continue;
		buf = malloc(vol ? vol->mft_record_size : 1024);
		if (!buf)
			return;
		n = pread(dev_fd, buf, vol->mft_record_size, rec);
		if (n != (s64)vol->mft_record_size) {
			fprintf(stderr, "WARN cannot read MFT record of %s: %s\n",
				mb_attr_paths[i], strerror(errno));
			free(buf);
			continue;
		}
		memcpy(&val, buf + mb_attr_field_off[i], 4);
		val |= mb_attr_bits[i];
		memcpy(buf + mb_attr_field_off[i], &val, 4);
		if (pwrite(dev_fd, buf, vol->mft_record_size, rec) != (s64)vol->mft_record_size)
			fprintf(stderr, "WARN cannot write attributes of %s: %s\n",
				mb_attr_paths[i], strerror(errno));
		free(buf);
	}
}

/** 读出 DOS 属性（`--attrs` 诊断模式用）。 */
static int mode_attrs(const char *guest)
{
	ntfs_inode *ni = resolve_path(guest);
	u32 *p;
	u32 a;

	if (!ni) {
		fprintf(stderr, "ERR open %s: %s\n", guest, strerror(errno));
		return 5;
	}
	p = mb_file_attributes_ptr(ni);
	if (!p) {
		fprintf(stderr, "ERR no $STANDARD_INFORMATION in %s\n", guest);
		ntfs_inode_close(ni);
		return 5;
	}
	a = *p;
	printf("ATTRS %s 0x%08X%s%s%s%s\n", guest, a,
	       (a & MB_ATTR_READONLY) ? " readonly" : "",
	       (a & MB_ATTR_HIDDEN) ? " hidden" : "",
	       (a & MB_ATTR_SYSTEM) ? " system" : "",
	       (a & 0x10) ? " directory" : "");
	ntfs_inode_close(ni);
	return 0;
}

/* ------------------------------------------------------------------ 列目录（诊断） */

struct ls_ctx {
	int count;
};

static int ls_cb(void *dirent, const ntfschar *name, const int name_len,
		 const int name_type, const s64 pos, const MFT_REF mref,
		 const unsigned dt_type)
{
	struct ls_ctx *c = dirent;
	char *utf8 = NULL;
	int n;

	(void)pos;
	(void)mref;
	if (name_type != FILE_NAME_POSIX) /* 只看长名（Win32），不列 8.3 */
		return 0;
	n = ntfs_ucstombs(name, name_len, &utf8, 0);
	if (n >= 0 && utf8) {
		/* 一并打印完整的 MFT 引用（低 48 位是记录号，高 16 位是序列号）： */
		/* 名字查找在个别卷上不可靠时，可以用 --dump-inode 直接读 */
		printf("LS %s%s mft=%llu\n", utf8, (dt_type == NTFS_DT_DIR) ? "/" : "",
		       (unsigned long long)mref);
		free(utf8);
		c->count++;
	}
	return 0;
}

/** `--ls <guest-dir>`：列出目录内容（诊断"文件到底在不在"用）。 */
static int mode_ls(const char *guest)
{
	ntfs_inode *ni;
	s64 pos = 0;
	struct ls_ctx c;
	int rc;

	c.count = 0;
	/* "#123" 表示直接用 MFT 号打开（绕开可能不可靠的名字查找） */
	if (guest[0] == '#')
		ni = ntfs_inode_open(vol, (MFT_REF)strtoull(guest + 1, NULL, 0));
	else
		ni = resolve_path(guest);
	if (!ni) {
		fprintf(stderr, "ERR open %s: %s\n", guest, strerror(errno));
		return 5;
	}
	rc = ntfs_readdir(ni, &pos, &c, ls_cb);
	if (rc) {
		fprintf(stderr, "ERR readdir %s: %s\n", guest, strerror(errno));
		ntfs_inode_close(ni);
		return 5;
	}
	ntfs_inode_close(ni);
	fprintf(stderr, "LSCOUNT %s %d\n", guest, c.count);
	return 0;
}

/* ------------------------------------------------------------------ 按需区域（window）模式 */

#define MAX_RANGES 8192
#define DEV_STATE_OPEN 1 /* ND_Open 位：自定义 device 已由我们打开 */

struct mb_range {
	s64 off;
	s64 len;
};

static struct mb_range have[MAX_RANGES];
static int have_n = 0;
static struct mb_range need[MAX_RANGES];
static int need_n = 0;
static s64 need_total = 0;
static int regions_on = 0; /* 未给 --regions 时整卷可用（目录树写入模式） */

static int have_covers(s64 off, s64 len)
{
	int i;
	if (len <= 0)
		return 1;
	for (i = 0; i < have_n; i++)
		if (off >= have[i].off && off + len <= have[i].off + have[i].len)
			return 1;
	return 0;
}

/* 包含 off 的那段已覆盖区域的结尾；off 未被覆盖时返回 off。 */
static s64 have_run_end(s64 off)
{
	int i;
	for (i = 0; i < have_n; i++)
		if (off >= have[i].off && off < have[i].off + have[i].len)
			return have[i].off + have[i].len;
	return off;
}

/* off 之后最近的一段已覆盖区域的起点；都没有则返回 S64_MAX。 */
static s64 have_next_start(s64 off)
{
	s64 best = (s64)1 << 62;
	int i;
	for (i = 0; i < have_n; i++)
		if (have[i].off > off && have[i].off < best)
			best = have[i].off;
	return best;
}

static void need_add(s64 off, s64 len)
{
	s64 end = off + len;
	int i;

	if (len <= 0)
		return;
	off &= ~(s64)4095;
	end = (end + 4095) & ~(s64)4095;
	if (have_covers(off, end - off))
		return; /* 已经给过了：避免死循环 */
	for (i = 0; i < need_n; i++) {
		s64 a = need[i].off;
		s64 b = need[i].off + need[i].len;
		if (off <= b && end >= a) {
			if (off < a)
				need[i].off = off;
			if (end > b)
				need[i].len = end - need[i].off;
			return;
		}
	}
	if (need_n < MAX_RANGES) {
		need[need_n].off = off;
		need[need_n].len = end - off;
		need_total += end - off;
		need_n++;
	}
}

static void load_regions(const char *path)
{
	char line[256];
	FILE *f = fopen(path, "r");
	if (!f) {
		fprintf(stderr, "WARN regions file %s unreadable: %s\n", path, strerror(errno));
		return;
	}
	while (fgets(line, sizeof(line), f)) {
		long long a = 0, b = 0;
		if (sscanf(line, "%lld %lld", &a, &b) == 2 && b > 0 && have_n < MAX_RANGES) {
			have[have_n].off = (s64)a;
			have[have_n].len = (s64)b;
			have_n++;
		}
	}
	fclose(f);
	regions_on = 1;
}

/* 调试用：MB_DEBUG=1 时打印阶段性断点（排查卡死）。 */
#define DBG(...) do { if (getenv("MB_DEBUG")) { fprintf(stderr, __VA_ARGS__); fflush(stderr); } } while (0)

/* 调试用：MB_TRACE=<file> 时记录每一次读取（排查卡死/死循环）。 */
static FILE *tracef = NULL;
static void trace_read(s64 off, s64 count)
{
	if (!tracef) {
		const char *p = getenv("MB_TRACE");
		if (!p)
			return;
		tracef = fopen(p, "w");
		if (!tracef)
			return;
	}
	fprintf(tracef, "%lld %lld\n", (long long)off, (long long)count);
	fflush(tracef);
}

/* 混合读：已覆盖的段落真读，未覆盖的段落记 NEED 并补 0（这样一轮能多收集一些请求）。 */
static s64 dev_read_at(void *buf, s64 count, s64 off)
{
	s64 done = 0;

	trace_read(off, count);
	if (count <= 0)
		return 0;
	if (!regions_on)
		return pread(dev_fd, buf, (size_t)count, off);
	while (done < count) {
		s64 cur = off + done;
		if (have_covers(cur, 1)) {
			s64 end = have_run_end(cur);
			s64 take = end - cur;
			if (take > count - done)
				take = count - done;
			if (pread(dev_fd, (char *)buf + done, (size_t)take, cur) != take)
				return done > 0 ? done : -1;
			done += take;
		} else {
			s64 end = have_next_start(cur);
			s64 take = end - cur;
			if (take > count - done)
				take = count - done;
			need_add(cur, take);
			memset((char *)buf + done, 0, (size_t)take);
			done += take;
		}
	}
	return done;
}

static int dev_open(struct ntfs_device *dev, int flags)
{
	(void)dev;
	(void)flags;
	return 0;
}

static int dev_close(struct ntfs_device *dev)
{
	(void)dev;
	return 0;
}

static s64 dev_seek(struct ntfs_device *dev, s64 offset, int whence)
{
	(void)dev;
	return lseek(dev_fd, offset, whence);
}

static s64 dev_read(struct ntfs_device *dev, void *buf, s64 count)
{
	(void)dev;
	s64 pos = lseek(dev_fd, 0, SEEK_CUR);
	if (pos < 0)
		return -1;
	return dev_read_at(buf, count, pos);
}

static s64 dev_write(struct ntfs_device *dev, const void *buf, s64 count)
{
	(void)dev;
	return write(dev_fd, buf, (size_t)count);
}

static s64 dev_pread(struct ntfs_device *dev, void *buf, s64 count, s64 offset)
{
	(void)dev;
	return dev_read_at(buf, count, offset);
}

static s64 dev_pwrite(struct ntfs_device *dev, const void *buf, s64 count, s64 offset)
{
	(void)dev;
	if (regions_on && !have_covers(offset, count)) {
		/* 只读模式下不该发生；真要写就先要求调用方补齐 */
		need_add(offset, count);
		return count;
	}
	return pwrite(dev_fd, buf, (size_t)count, offset);
}

static int dev_sync(struct ntfs_device *dev)
{
	(void)dev;
	return fsync(dev_fd);
}

static int dev_stat(struct ntfs_device *dev, struct stat *buf)
{
	(void)dev;
	return fstat(dev_fd, buf);
}

static int dev_ioctl(struct ntfs_device *dev, unsigned long request, void *argp)
{
	(void)dev;
	(void)request;
	(void)argp;
	return -1;
}

static struct ntfs_device_operations DEV_OPS = {
	.open = dev_open,
	.close = dev_close,
	.seek = dev_seek,
	.read = dev_read,
	.write = dev_write,
	.pread = dev_pread,
	.pwrite = dev_pwrite,
	.sync = dev_sync,
	.stat = dev_stat,
	.ioctl = dev_ioctl,
};

/* 只读挂载（依次尝试几种 flag 组合，尽量容忍脏卷/休眠卷）。 */
static ntfs_volume *mount_volume(const char *image)
{
	static const unsigned long CAND[] = {
		NTFS_MNT_RDONLY | NTFS_MNT_IGNORE_HIBERFILE,
		NTFS_MNT_RDONLY | NTFS_MNT_IGNORE_HIBERFILE | NTFS_MNT_FORENSIC,
		NTFS_MNT_RDONLY | NTFS_MNT_IGNORE_HIBERFILE | 0x2 /* NTFS_MNT_FORCE */,
		NTFS_MNT_IGNORE_HIBERFILE | 0x2,
		0,
	};
	size_t i;
	struct ntfs_device *dev;
	ntfs_volume *v;

	/* 非区域模式：保持原来的 stdio 路径（目录树写入需要读写挂载） */
	if (!regions_on) {
		v = ntfs_mount(image, 0);
		if (!v)
			fprintf(stderr, "ERR mount %s: %s\n", image, strerror(errno));
		return v;
	}

	dev = ntfs_device_alloc(image, DEV_STATE_OPEN, &DEV_OPS, NULL);
	if (!dev) {
		fprintf(stderr, "ERR device-alloc: %s\n", strerror(errno));
		return NULL;
	}
	for (i = 0; i < sizeof(CAND) / sizeof(CAND[0]); i++) {
		errno = 0;
		v = ntfs_device_mount(dev, CAND[i]);
		if (v) {
			if (i > 0)
				fprintf(stderr, "mount flags=0x%lx ok\n", CAND[i]);
			return v;
		}
	}
	fprintf(stderr, "ERR mount %s: %s\n", image, strerror(errno));
	return NULL;
}

/* ------------------------------------------------------------------ 名称编码 / 时间戳 */

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
/* ------------------------------------------------------------------ 目录树写入 */

/**
 * 再按「相对路径」解析一次符号链接目标。
 *
 * wimlib 的 link() 回退把别名做成符号链接时，目标就是「第一个别名的路径」。当 App 传的是
 * **绝对** staging 目录时目标也是绝对路径（内核直接能解析）；一旦传的是相对目录（例如
 * `dst_fb`），目标就变成相对 **调用方工作目录** 的路径 —— 这种链接从链接自己所在目录解析
 * 是断的，会被当成断链跳过，于是 DriverStore 里的 .inf/.sys 就缺了。
 * 这里依次试「cwd + 目标」「根目录的父目录 + 目标」「根目录 + 目标」，命中就返回 0。
 */
static int resolve_link_via_root(const char *linkpath, char *out, size_t outsz)
{
	char target[4096];
	char cwd[4096];
	char dir[4096];
	ssize_t n;
	size_t i;
	struct stat st;

	n = readlink(linkpath, target, sizeof(target) - 1);
	if (n <= 0)
		return -1;
	target[n] = 0;
	if (target[0] == '/') /* 绝对目标：stat(linkpath) 已经试过了 */
		return -1;

	if (getcwd(cwd, sizeof(cwd)) &&
	    snprintf(out, outsz, "%s/%s", cwd, target) < (int)outsz &&
	    stat(out, &st) == 0 && S_ISREG(st.st_mode))
		return 0;

	if (g_root && g_root[0]) {
		snprintf(dir, sizeof(dir), "%s", g_root);
		i = strlen(dir);
		while (i > 1 && dir[i - 1] != '/')
			i--;
		if (i >= 1)
			dir[i] = 0;
		if (snprintf(out, outsz, "%s/%s", dir, target) < (int)outsz &&
		    stat(out, &st) == 0 && S_ISREG(st.st_mode))
			return 0;
		if (snprintf(out, outsz, "%s/%s", g_root, target) < (int)outsz &&
		    stat(out, &st) == 0 && S_ISREG(st.st_mode))
			return 0;
	}
	return -1;
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
		/* desktop.ini 的「隐藏+系统」属性由 walk() 记下来、整棵树写完统一补 */
		files_written++;
		bytes_written += (long long)off;
		if (!quiet && (files_written % 500) == 0)
			fprintf(stderr, "PROGRESS files=%lld bytes=%lld\n", files_written, bytes_written);
	}

	ntfs_inode_close(ni);
	return bad ? -1 : 0;
}

static int walk(ntfs_inode *dir_ni, const char *src, const char *guest)
{
	DIR *d = opendir(src);
	int has_desktop_ini = 0;

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
		char child_guest[2048];

		if (snprintf(path, sizeof(path), "%s/%s", src, e->d_name) >= (int)sizeof(path)) {
			fprintf(stderr, "ERR path-too-long %s/%s\n", src, e->d_name);
			failures++;
			continue;
		}
		if (*guest)
			snprintf(child_guest, sizeof(child_guest), "%s/%s", guest, e->d_name);
		else
			snprintf(child_guest, sizeof(child_guest), "%s", e->d_name);

		struct stat st;
		if (lstat(path, &st) != 0)
			continue;

		/*
		 * 符号链接：Windows 镜像里有两类 ——
		 *  ① 指向**普通文件**的：wimlib 在 link() 被拒时用符号链接顶替的硬链接别名
		 *     （典型是 DriverStore\FileRepository\*），**必须展开成真实文件** ——
		 *     否则 Windows 里那些 .inf/.sys 就是空的（所有设备都装不上驱动）。
		 *  ② 指向**目录**的 junction（如 "Documents and Settings"）：跳过，
		 *     Windows 里那只是兼容用的外壳。
		 */
		if (S_ISLNK(st.st_mode)) {
			struct stat tst;
			char alt[4096];
			const char *real = NULL;

			if (stat(path, &tst) == 0 && S_ISREG(tst.st_mode))
				real = path;
			else if (resolve_link_via_root(path, alt, sizeof(alt)) == 0)
				real = alt;
			if (real) {
				if (name_is(e->d_name, "desktop.ini"))
					has_desktop_ini = 1,
					mb_attr_add(child_guest, MB_ATTR_HIDDEN | MB_ATTR_SYSTEM);
				write_one_file(dir_ni, e->d_name, real);
				links_expanded++;
			} else {
				links_skipped++;
			}
		} else if (S_ISDIR(st.st_mode)) {
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
			walk(sub, path, child_guest);
			set_times(sub, path);
			ntfs_inode_close(sub);
		} else if (S_ISREG(st.st_mode)) {
			/* 记下来，等整棵树写完再统一补 DOS 属性（见 mb_attr_apply 的说明） */
			if (name_is(e->d_name, "desktop.ini"))
				has_desktop_ini = 1, mb_attr_add(child_guest, MB_ATTR_HIDDEN | MB_ATTR_SYSTEM);
			write_one_file(dir_ni, e->d_name, path);
		}
		/* 符号链接/设备节点：Windows 镜像里基本用不到，直接跳过 */
	}
	closedir(d);
	/* Explorer 只有在该目录带「系统」属性时才把 desktop.ini 当视图配置解析 */
	if (has_desktop_ini)
		mb_attr_add(guest, MB_ATTR_SYSTEM);
	return 0;
}

/* ------------------------------------------------------------------ 单文件读写（BCD 用） */

/* 解析 guest 路径（`/`、`\` 都接受；大小写不敏感由兜底尝试覆盖）。 */
static ntfs_inode *resolve_path(const char *path)
{
	char norm[1100];
	size_t i;
	ntfs_inode *ni;

	for (i = 0; path[i] && i < sizeof(norm) - 2; i++)
		norm[i] = (path[i] == '\\') ? '/' : path[i];
	norm[i] = 0;
	if (norm[0] != '/') {
		memmove(norm + 1, norm, i + 1);
		norm[0] = '/';
	}
	DBG("  resolve: %s\n", norm);
	ni = ntfs_pathname_to_inode(vol, NULL, norm);
	if (ni)
		return ni;
	/* 兜底：整体转小写再试一次（ntfs_pathname_to_inode 大小写敏感） */
	for (i = 0; norm[i]; i++) {
		if (norm[i] >= 'A' && norm[i] <= 'Z')
			norm[i] = (char)(norm[i] + 32);
		else if (norm[i] >= 'a' && norm[i] <= 'z')
			norm[i] = (char)(norm[i] - 32);
	}
	DBG("  resolve(retry): %s\n", norm);
	ni = ntfs_pathname_to_inode(vol, NULL, norm);
	if (!ni)
		errno = ENOENT;
	return ni;
}

/*
 * 打印被读文件数据的物理位置（卷内偏移），供调用方就地改写：
 *   MAP <file-offset> <disk-offset> <len>
 * 常驻数据（小文件，存在 MFT 记录里）映射到该记录内部的字节位置。
 */
static s64 mft_record_disk_offset(u64 mft_no)
{
	s64 cs = (s64)vol->cluster_size;
	s64 byte = (s64)mft_no * (s64)vol->mft_record_size;
	ntfs_attr *mna;
	LCN lcn;
	s64 res;

	if (!vol->mft_ni || cs <= 0)
		return -1;
	mna = ntfs_attr_open(vol->mft_ni, AT_DATA, AT_UNNAMED, 0);
	if (!mna)
		return -1;
	if (ntfs_attr_map_whole_runlist(mna) != 0) {
		ntfs_attr_close(mna);
		return -1;
	}
	lcn = ntfs_attr_vcn_to_lcn(mna, (VCN)(byte / cs));
	if (lcn < 0) {
		ntfs_attr_close(mna);
		return -1;
	}
	res = (s64)lcn * cs + (byte % cs);
	ntfs_attr_close(mna);
	return res;
}

static void print_map(ntfs_attr *na, const char *guest)
{
	s64 cs = (s64)vol->cluster_size;
	s64 total = na->data_size;
	int i;

	if (!NAttrNonResident(na)) {
		/* 常驻：数据在 MFT 记录内部。手工遍历属性找 $DATA 的 value_offset。 */
		u8 *m = (u8 *)na->ni->mrec;
		u16 ao;
		int found = 0;
		s64 disk = mft_record_disk_offset(na->ni->mft_no);
		if (!m || disk < 0) {
			printf("MAP-RESIDENT-NO-OFFSET %s\n", guest);
			return;
		}
		memcpy(&ao, m + 0x14, 2);
		while ((u32)(ao + 24) <= vol->mft_record_size) {
			u32 type, alen;
			u8 nonres, namelen;
			memcpy(&type, m + ao, 4);
			memcpy(&alen, m + ao + 4, 4);
			nonres = m[ao + 8];
			namelen = m[ao + 9];
			if (type == 0xffffffff || alen < 24)
				break;
			if (type == 0x80 && !nonres && namelen == 0) {
				u32 vlen;
				u16 voff;
				memcpy(&vlen, m + ao + 0x10, 4);
				memcpy(&voff, m + ao + 0x14, 2);
				/* voff 是相对**属性记录**的偏移，所以要加上 ao 才是记录内偏移 */
				printf("MAP 0 %lld %lld\n", (long long)(disk + ao + voff),
				       (long long)vlen);
				DBG("  resident $DATA: rec=%lld attr=+0x%x value=+0x%x -> %lld\n",
				    (long long)disk, (unsigned)ao, (unsigned)voff,
				    (long long)(disk + ao + voff));
				found = 1;
				break;
			}
			ao = (u16)(ao + alen);
		}
		if (!found)
			printf("MAP-RESIDENT-NOTFOUND %s\n", guest);
		return;
	}

	if (!na->rl) {
		printf("MAP-NO-RUNLIST %s\n", guest);
		return;
	}
	for (i = 0; i < 500000 && na->rl[i].lcn != LCN_ENOENT; i++) {
		s64 fo = (s64)na->rl[i].vcn * cs;
		s64 flen = (s64)na->rl[i].length * cs;
		if (na->rl[i].lcn < 0)
			continue; /* 空洞：读出来是 0，不需要回写 */
		if (fo >= total)
			break;
		if (fo + flen > total)
			flen = total - fo;
		if (flen <= 0)
			continue;
		printf("MAP %lld %lld %lld\n", (long long)fo,
		       (long long)na->rl[i].lcn * cs, (long long)flen);
	}
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
	(void)ntfs_attr_map_whole_runlist(na);
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
	print_map(na, guest);
	ntfs_attr_close(na);
	ntfs_inode_close(ni);
	fprintf(stderr, "DUMP %s -> %s (%lld bytes)\n", guest, host, (long long)off);
	return rc;
}

/* 按 MFT 号读文件数据（名字查找在个别卷上不可靠时的兜底）。 */
static int mode_dump_inode(u64 mft_no, const char *host)
{
	ntfs_inode *ni = ntfs_inode_open(vol, (MFT_REF)mft_no);
	ntfs_attr *na;
	FILE *out;
	char *buf;
	s64 size, off = 0;
	int rc = 0;

	if (!ni) {
		fprintf(stderr, "ERR inode %llu: %s\n",
			(unsigned long long)mft_no, strerror(errno));
		return 5;
	}
	na = ntfs_attr_open(ni, AT_DATA, AT_UNNAMED, 0);
	if (!na) {
		fprintf(stderr, "ERR no-data inode %llu\n", (unsigned long long)mft_no);
		ntfs_inode_close(ni);
		return 5;
	}
	(void)ntfs_attr_map_whole_runlist(na);
	out = fopen(host, "wb");
	if (!out) {
		fprintf(stderr, "ERR create %s: %s\n", host, strerror(errno));
		ntfs_attr_close(na);
		ntfs_inode_close(ni);
		return 5;
	}
	buf = malloc(BUF_SIZE);
	size = na->data_size;
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
	print_map(na, host);
	ntfs_attr_close(na);
	ntfs_inode_close(ni);
	fprintf(stderr, "DUMP inode %llu -> %s (%lld bytes)\n",
		(unsigned long long)mft_no, host, (long long)off);
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

/* ------------------------------------------------------------------ main */

int main(int argc, char **argv)
{
	const char *image = NULL;
	const char *source = NULL;
	const char *mode = NULL;
	const char *arg_a = NULL;
	const char *arg_b = NULL;
	const char *regions = NULL;
	int i;
	int rc = 0;

	for (i = 1; i < argc; i++) {
		if (!strcmp(argv[i], "--quiet")) {
			quiet = 1;
		} else if (!strcmp(argv[i], "--regions") && i + 1 < argc) {
			regions = argv[++i];
		} else if (!strcmp(argv[i], "--dump") && i + 2 < argc) {
			mode = "dump";
			arg_a = argv[++i];
			arg_b = argv[++i];
		} else if (!strcmp(argv[i], "--put") && i + 2 < argc) {
			mode = "put";
			arg_a = argv[++i];
			arg_b = argv[++i];
		} else if (!strcmp(argv[i], "--attrs") && i + 1 < argc) {
			mode = "attrs";
			arg_a = argv[++i];
		} else if (!strcmp(argv[i], "--ls") && i + 1 < argc) {
			mode = "ls";
			arg_a = argv[++i];
		} else if (!strcmp(argv[i], "--dump-inode") && i + 2 < argc) {
			mode = "dumpinode";
			arg_a = argv[++i];
			arg_b = argv[++i];
		} else if (!image) {
			image = argv[i];
		} else if (!source) {
			source = argv[i];
		}
	}

	if (!image || (!source && !mode)) {
		fprintf(stderr,
			"usage: %s <ntfs-image> <source-dir> [--quiet]\n"
			"       %s <ntfs-image> [--regions <file>] --dump <guest-path> <host-file>\n"
			"       %s <ntfs-image> [--regions <file>] --put <host-file> <guest-path>\n"
			"       %s <ntfs-image> --attrs <guest-path>\n",
			argv[0], argv[0], argv[0], argv[0]);
		return 2;
	}

	if (regions)
		load_regions(regions);

	dev_fd = open(image, O_RDWR);
	if (dev_fd < 0) {
		fprintf(stderr, "ERR open %s: %s\n", image, strerror(errno));
		return 3;
	}

	vol = mount_volume(image);
	if (!vol) {
		rc = 3;
		goto done;
	}

	if (mode) {
		if (!strcmp(mode, "dump"))
			rc = mode_dump(arg_a, arg_b);
		else if (!strcmp(mode, "attrs"))
			rc = mode_attrs(arg_a);
		else if (!strcmp(mode, "ls"))
			rc = mode_ls(arg_a);
		else if (!strcmp(mode, "dumpinode"))
			rc = mode_dump_inode(strtoull(arg_a, NULL, 0), arg_b);
		else
			rc = mode_put(arg_a, arg_b);
		ntfs_umount(vol, FALSE);
		goto done;
	}

	ntfs_inode *root = ntfs_pathname_to_inode(vol, NULL, "/");
	if (!root) {
		fprintf(stderr, "ERR root-inode\n");
		ntfs_umount(vol, FALSE);
		rc = 4;
		goto done;
	}

	g_root = source;	/* wimlib 的相对链接目标要按这个根目录解析（见 resolve_link_via_root） */
	walk(root, source, "");
	/* 整棵树写完了：先解析出需要补属性的记录的物理位置（卸载后再真正落盘，见下） */
	mb_attr_resolve();
	ntfs_inode_close(root);

	/* force=FALSE：把元数据刷盘并清掉 dirty 标志，避免 Windows 首次挂载就 chkdsk */
	if (ntfs_umount(vol, FALSE) != 0) {
		fprintf(stderr, "ERR umount\n");
		failures++;
	}

	/* 卸载完成后再改磁盘上的 MFT 记录（此时库不会再覆盖我们的写入） */
	mb_attr_write_raw();

	fprintf(stderr, "DONE files=%lld bytes=%lld failures=%d links_expanded=%lld links_skipped=%lld\n",
		files_written, bytes_written, failures, links_expanded, links_skipped);
	rc = failures ? 1 : 0;

done:
	/* 区域模式：把还没拿到的区域报告给调用方，让它补齐后重跑 */
	if (regions_on && need_n > 0) {
		fprintf(stderr, "MISSING %d region(s), %.2f MB not in window\n",
			need_n, need_total / 1048576.0);
		for (i = 0; i < need_n; i++)
			printf("NEED %lld %lld\n", (long long)need[i].off, (long long)need[i].len);
		rc = 10;
	}
	if (dev_fd >= 0)
		close(dev_fd);
	return rc;
}

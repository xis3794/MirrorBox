#!/usr/bin/env python3
"""给 wimlib 打补丁：`link()` 失败时退化成「复制一份」。

为什么要这个补丁
----------------
`wimlib-imagex apply` 释放 Windows 镜像时，winsxs 里的同一份数据会以多个名字出现
（硬链接），典型一对是：

    Windows\\Boot\\PCAT\\bootmgr
    Windows\\winsxs\\x86_microsoft-windows-b..re-bootmanager-pcat_...\\bootmgr

在 Android 上，某些设备/文件系统**即使在自己的应用数据目录里也会拒绝 `link()`**
（实测华为机型：`Can't create hard link ...: Permission denied`，wimlib 退出码 35），
于是整个释放流程直接中止 —— 表现就是「换了一份 WIM 就释放失败」。

而硬链接对我们的流程**不是必需的**：暂存目录随后是逐文件写进 NTFS 分区的
（`mirrorbox-ntfs`），并不依赖 inode 共享。所以补丁的做法是：

  * `link()` 失败 → 直接**复制文件内容**到目标路径，并打印真实 errno（便于日后定位）；
  * 只有当复制也失败时，才照原样报错退出（保持原来的错误语义）。

用法：
    python3 native/scripts/patch-wimlib-link-fallback.py <wimlib 源码目录>
"""
import pathlib
import sys

MARKER = "mirrorbox_copy_file"

HELPER = '''/* mirrorbox: fallback used when link() is refused by the kernel/filesystem.
 * Copies the contents of @src to @dst.  Returns 0 on success. */
static int
mirrorbox_copy_file(const char *src, const char *dst)
{
	char buf[65536];
	int in = -1;
	int out = -1;
	ssize_t n;
	int rc = -1;

	(void)unlink(dst);
	in = open(src, O_RDONLY);
	if (in < 0)
		goto out;
	out = open(dst, O_WRONLY | O_CREAT | O_TRUNC, 0644);
	if (out < 0)
		goto out;
	while ((n = read(in, buf, sizeof(buf))) > 0) {
		ssize_t off = 0;

		while (off < n) {
			ssize_t w = write(out, buf + off, (size_t)(n - off));

			if (w <= 0)
				goto out;
			off += w;
		}
	}
	if (n < 0)
		goto out;
	rc = 0;
out:
	if (in >= 0)
		close(in);
	if (out >= 0)
		close(out);
	if (rc != 0)
		(void)unlink(dst);
	return rc;
}

'''

# 精确锚点（wimlib 1.14.4 src/unix_apply.c）
ANCHOR_COMMENT = '''/* Extract all needed aliases of the @inode, where one alias, corresponding to
 * @first_dentry, has already been extracted to @first_path.  */
static int
unix_create_hardlinks('''

ANCHOR_FAIL = '''\t\tif (link(first_path, newpath)) {
\t\t\tif (errno == EEXIST && !unlink(newpath))
\t\t\t\tgoto retry_link;
\t\t\tERROR_WITH_ERRNO("Can't create hard link "
\t\t\t\t\t "\\"%s\\" => \\"%s\\"", newpath, first_path);
\t\t\treturn WIMLIB_ERR_LINK;
\t\t}
'''

REPLACEMENT_FAIL = '''\t\tif (link(first_path, newpath)) {
\t\t\tint mirrorbox_link_errno = errno;

\t\t\tif (mirrorbox_link_errno == EEXIST && !unlink(newpath))
\t\t\t\tgoto retry_link;
\t\t\t/* mirrorbox: 某些设备直接拒绝 link()（实测华为：EACCES）。
\t\t\t * 硬链接对「写进 NTFS」的流程不是必需的，复制内容即可。 */
\t\t\tif (mirrorbox_copy_file(first_path, newpath) == 0) {
\t\t\t\tWARNING("Can't create hard link \\"%s\\" => \\"%s\\" (%s); copied instead",
\t\t\t\t\tnewpath, first_path, strerror(mirrorbox_link_errno));
\t\t\t\tunix_reuse_pathbuf(ctx);
\t\t\t\tcontinue;
\t\t\t}
\t\t\terrno = mirrorbox_link_errno;
\t\t\tERROR_WITH_ERRNO("Can't create hard link "
\t\t\t\t\t "\\"%s\\" => \\"%s\\"", newpath, first_path);
\t\t\treturn WIMLIB_ERR_LINK;
\t\t}
'''


def main() -> int:
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    src = pathlib.Path(sys.argv[1]) / "src" / "unix_apply.c"
    if not src.exists():
        raise SystemExit(f"找不到 {src}")

    text = src.read_text()
    if MARKER in text:
        print("wimlib link-fallback 补丁已在（跳过）")
        return 0
    if ANCHOR_COMMENT not in text:
        raise SystemExit("wimlib 源码结构与预期不符（找不到 unix_create_hardlinks），补丁中止")
    if ANCHOR_FAIL not in text:
        raise SystemExit("wimlib 源码结构与预期不符（找不到 link() 失败处理），补丁中止")

    text = text.replace(ANCHOR_COMMENT, HELPER + ANCHOR_COMMENT, 1)
    text = text.replace(ANCHOR_FAIL, REPLACEMENT_FAIL, 1)
    src.write_text(text)
    print("已打补丁 src/unix_apply.c：link() 失败时复制文件内容（并把真实 errno 打出来）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
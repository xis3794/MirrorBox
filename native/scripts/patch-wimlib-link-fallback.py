#!/usr/bin/env python3
"""给 wimlib 打补丁：`link()` 被拒时，退化成「数据写完后复制一份」。

为什么要这个补丁
----------------
`wimlib-imagex apply` 释放 Windows 镜像时，winsxs 里同一份数据会以多个名字出现（硬链接），
典型一对是：

    Windows\\Boot\\PCAT\\bootmgr
    Windows\\winsxs\\x86_microsoft-windows-b..re-bootmanager-pcat_...\\bootmgr

在 Android 上，某些设备/文件系统**即使在自己应用的数据目录里也会拒绝 `link()`**
（实测华为机型：`Can't create hard link ...: Permission denied`，wimlib 退出码 35），
整个释放流程就此中止 —— 现象就是「换了一份 WIM 就释放失败」。

而硬链接对我们的流程**不是必需的**：暂存目录随后是逐文件写进 NTFS 分区的
（`mirrorbox-ntfs`），不依赖 inode 共享。所以补丁的做法是：

  * `link()` 被拒 → 把「源/目标/errno」记进一个待办队列，**继续往下走**；
  * 该文件的数据**写完**后（`unix_end_extract_blob`，此时所有别名共享的 inode 已有完整数据）
    再逐条**复制内容**，并打印真实 errno；
  * 只有复制也失败时才继续报警（不改变原来的错误语义）。

注意时机：wimlib 是在**建好空文件之后、写数据之前**调用 `unix_create_hardlinks()` 的
（`unix_begin_extract_blob_instance()` 里 `open(O_CREAT)` 之后立刻调用），所以**不能在那里
立刻复制** —— 那只会复制到空文件。必须延迟到 blob 结束。

用法：
    python3 native/scripts/patch-wimlib-link-fallback.py <wimlib 源码目录>
"""
import pathlib
import sys

MARKER = "mirrorbox_pending_copy"

# ---------------------------------------------------------------- 顺序：先声明类型，再放进 ctx
STRUCT_BEFORE_CTX = "struct unix_apply_ctx {"

TYPE_DECL = '''/* mirrorbox: 一个被拒绝的硬链接别名，等数据写完后用「复制」补上。 */
struct mirrorbox_pending_copy {
	struct mirrorbox_pending_copy *next;
	char *src;
	char *dst;
	int err;
};

'''

CTX_FIELDS_ANCHOR = '''\t/* Number of special files we couldn't create due to EPERM  */
\tunsigned long num_special_files_ignored;
};
'''

CTX_FIELDS = '''\t/* Number of special files we couldn't create due to EPERM  */
\tunsigned long num_special_files_ignored;
\t/* mirrorbox: 等数据写完后要复制补齐的硬链接别名 */
\tstruct mirrorbox_pending_copy *mirrorbox_pending;
\t/* mirrorbox: 当前这次别名创建是否可以立刻复制（例如空文件） */
\tbool mirrorbox_immediate;
};
'''

FUNCS_ANCHOR = '''/* Extract all needed aliases of the @inode, where one alias, corresponding to
 * @first_dentry, has already been extracted to @first_path.  */
static int
unix_create_hardlinks('''

FUNCS = '''/* mirrorbox: 把 @src 的内容复制到 @dst（link() 的替代）。成功返回 0。 */
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

/* mirrorbox: 记下一条「稍后复制」的别名 */
static void
mirrorbox_pending_add(struct unix_apply_ctx *ctx, const char *src, const char *dst, int err)
{
	struct mirrorbox_pending_copy *e = malloc(sizeof(*e));

	if (!e)
		return;
	e->src = strdup(src);
	e->dst = strdup(dst);
	e->err = err;
	if (!e->src || !e->dst) {
		free(e->src);
		free(e->dst);
		free(e);
		return;
	}
	e->next = ctx->mirrorbox_pending;
	ctx->mirrorbox_pending = e;
}

/* mirrorbox: 数据已经写完 —— 把所有待办别名复制出来 */
static void
mirrorbox_flush_pending(struct unix_apply_ctx *ctx)
{
	struct mirrorbox_pending_copy *e = ctx->mirrorbox_pending;

	ctx->mirrorbox_pending = NULL;
	while (e) {
		struct mirrorbox_pending_copy *next = e->next;

		if (mirrorbox_copy_file(e->src, e->dst) == 0) {
			WARNING("Can't create hard link \\"%s\\" => \\"%s\\" (%s); copied instead",
				e->dst, e->src, strerror(e->err));
		} else {
			WARNING("mirrorbox: failed to copy \\"%s\\" => \\"%s\\"",
				e->src, e->dst);
		}
		free(e->src);
		free(e->dst);
		free(e);
		e = next;
	}
}

''' + FUNCS_ANCHOR

FAIL_ANCHOR = '''\t\tif (link(first_path, newpath)) {
\t\t\tif (errno == EEXIST && !unlink(newpath))
\t\t\t\tgoto retry_link;
\t\t\tERROR_WITH_ERRNO("Can't create hard link "
\t\t\t\t\t "\\"%s\\" => \\"%s\\"", newpath, first_path);
\t\t\treturn WIMLIB_ERR_LINK;
\t\t}
'''

FAIL_REPLACEMENT = '''\t\tif (link(first_path, newpath)) {
\t\t\tint mirrorbox_err = errno;

\t\t\tif (mirrorbox_err == EEXIST && !unlink(newpath))
\t\t\t\tgoto retry_link;
\t\t\t/* mirrorbox: 某些设备直接拒绝 link()（实测华为：EACCES）。
\t\t\t * 关键：这个函数是在「建好空文件、还没写数据」时调用的，所以默认
\t\t\t * 只能记进待办队列，等数据写完（unix_end_extract_blob）再复制；
\t\t\t * 空文件没有数据可等，由调用方把 mirrorbox_immediate 置位，立刻复制。 */
\t\t\tif (ctx->mirrorbox_immediate &&
\t\t\t    mirrorbox_copy_file(first_path, newpath) == 0) {
\t\t\t\tWARNING("Can't create hard link \\"%s\\" => \\"%s\\" (%s); copied instead",
\t\t\t\t\tnewpath, first_path, strerror(mirrorbox_err));
\t\t\t} else {
\t\t\t\tmirrorbox_pending_add(ctx, first_path, newpath, mirrorbox_err);
\t\t\t}
\t\t\tunix_reuse_pathbuf(ctx);
\t\t\tcontinue;
\t\t}
'''

EMPTY_CALL_ANCHOR = '''\tret = unix_create_hardlinks(inode, dentry, path, ctx);
'''
EMPTY_CALL_REPLACEMENT = '''\t/* mirrorbox: 空文件没有数据要等，可以立刻复制 */
\tctx->mirrorbox_immediate = true;
\tret = unix_create_hardlinks(inode, dentry, path, ctx);
\tctx->mirrorbox_immediate = false;
'''

FLUSH_ANCHOR = '''\tunix_cleanup_open_fds(ctx, j);
\treturn ret;
}
'''
FLUSH_REPLACEMENT = '''\tunix_cleanup_open_fds(ctx, j);
\t/* mirrorbox: 该 blob 的数据已经落盘，把待办别名复制出来 */
\tif (ret == 0)
\t\tmirrorbox_flush_pending(ctx);
\treturn ret;
}
'''


def apply_patch(src_dir: pathlib.Path) -> int:
    src = src_dir / "src" / "unix_apply.c"
    if not src.exists():
        raise SystemExit(f"找不到 {src}")
    text = src.read_text()
    if MARKER in text:
        print("wimlib link-fallback 补丁已在（跳过）")
        return 0

    def one(anchor: str, replacement: str, what: str) -> None:
        nonlocal text
        n = text.count(anchor)
        if n != 1:
            raise SystemExit(f"wimlib 源码结构与预期不符（{what} 出现 {n} 次），补丁中止")
        text = text.replace(anchor, replacement, 1)

    one(STRUCT_BEFORE_CTX, TYPE_DECL + STRUCT_BEFORE_CTX, "struct unix_apply_ctx 定义")
    one(CTX_FIELDS_ANCHOR, CTX_FIELDS, "unix_apply_ctx 字段")
    one(FUNCS_ANCHOR, FUNCS, "unix_create_hardlinks 注释")
    one(FAIL_ANCHOR, FAIL_REPLACEMENT, "link() 失败处理")
    one(EMPTY_CALL_ANCHOR, EMPTY_CALL_REPLACEMENT, "空文件的 unix_create_hardlinks 调用")
    one(FLUSH_ANCHOR, FLUSH_REPLACEMENT, "unix_end_extract_blob 收尾")

    src.write_text(text)
    print("已打补丁 src/unix_apply.c：link() 被拒 → 数据写完后复制该别名（并打印真实 errno）")
    return 0


def main() -> int:
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    return apply_patch(pathlib.Path(sys.argv[1]))


if __name__ == "__main__":
    sys.exit(main())
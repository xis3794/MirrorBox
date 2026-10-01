#!/usr/bin/env python3
"""给 wimlib 打补丁：`link()` 被拒时，改用**符号链接**（零额外空间）。

背景（真实踩坑，两轮）
----------------------
`wimlib-imagex apply` 释放 Windows 镜像时，同一份数据会以多个名字出现（硬链接），典型一对：

    Windows\\System32\\DriverStore\\FileRepository\\machine.inf_amd64_...\\machine.inf
    Windows\\winsxs\\amd64_machine.inf_...\\machine.inf

这台设备上 `link()` 直接返回 EACCES（wimlib 退出码 35），即使在自己的应用数据目录里也一样；
而 `symlink()` 是可用的（Windows 镜像里的 "Documents and Settings" 符号链接一直能建出来）。

**第一版补丁的教训**：当时改成"复制一份"。复制要额外占掉与 winsxs 等量的空间（几个 GB），
暂存盘空间不够时复制失败 —— 而那一版只把它记成 WARNING、没有算作失败，于是产出一个
"释放成功"但 `DriverStore` 里全是 **0 字节文件**的镜像：所有设备都装不上驱动
（`pnputil` 报 "INF 语法不正确"）。

现在的做法
----------
`link()` 被拒 → 建一个**符号链接**（≈0 字节，不占空间，也不需要等数据写完），
再由我们自己的 NTFS 写入器 `mirrorbox-ntfs` 把"指向普通文件的符号链接"展开成**普通文件**
（Windows 看到的就和正常安装一样）。指向目录的链接（junction）仍然跳过。

用法：
    python3 native/scripts/patch-wimlib-link-fallback.py <wimlib 源码目录>
"""
import pathlib
import sys

MARKER = "mirrorbox_link_via_symlink"

ANCHOR_FAIL = '''\t\tif (link(first_path, newpath)) {
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
\t\t\t/*
\t\t\t * mirrorbox_link_via_symlink: 某些设备直接拒绝 link()（实测华为：EACCES），
\t\t\t * 但允许 symlink()。硬链接对我们不是必需的：这里放一个符号链接（≈0 字节，
\t\t\t * 不需要等数据写完），随后 mirrorbox-ntfs 写 NTFS 时会把指向普通文件的
\t\t\t * 符号链接展开成普通文件 —— Windows 看到的和正常安装一致。
\t\t\t *
\t\t\t * 千万不要退化成"复制"：那会额外占掉与 winsxs 等量的空间，暂存盘不够时
\t\t\t * 复制失败，会产出 DriverStore 全是 0 字节文件的"假成功"镜像。
\t\t\t */
\t\t\tif (symlink(first_path, newpath) == 0) {
\t\t\t\tunix_reuse_pathbuf(ctx);
\t\t\t\tcontinue;
\t\t\t}
\t\t\terrno = mirrorbox_err;
\t\t\tERROR_WITH_ERRNO("Can't create hard link "
\t\t\t\t\t "\\"%s\\" => \\"%s\\"", newpath, first_path);
\t\t\treturn WIMLIB_ERR_LINK;
\t\t}
'''


def apply_patch(src_dir: pathlib.Path) -> int:
    src = src_dir / "src" / "unix_apply.c"
    if not src.exists():
        raise SystemExit(f"找不到 {src}")
    text = src.read_text()
    if MARKER in text:
        print("wimlib link→symlink 补丁已在（跳过）")
        return 0
    n = text.count(ANCHOR_FAIL)
    if n != 1:
        raise SystemExit(f"wimlib 源码结构与预期不符（link() 失败处理出现 {n} 次），补丁中止")
    text = text.replace(ANCHOR_FAIL, FAIL_REPLACEMENT, 1)
    src.write_text(text)
    print("已打补丁 src/unix_apply.c：link() 被拒时改用符号链接（零额外空间）")
    return 0


def main() -> int:
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    return apply_patch(pathlib.Path(sys.argv[1]))


if __name__ == "__main__":
    sys.exit(main())
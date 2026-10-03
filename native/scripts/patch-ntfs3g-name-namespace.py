#!/usr/bin/env python3
"""给 libntfs-3g 打补丁：新建文件/目录用 **Win32 命名空间**，而不是 POSIX。

背景（实测）
-----------
`mirrorbox-ntfs` 用 libntfs-3g 的 `ntfs_create()` 建文件，而它内部把 `$FILE_NAME`
的 `file_name_type` 写死成 `FILE_NAME_POSIX`（libntfs-3g/dir.c: `fn->file_name_type =
FILE_NAME_POSIX;`）。

Windows 自己建文件用的是 **Win32 命名空间**（实测 `C:\\Windows\\Prefetch\\*.pf`
全是 `ns=1`，而我们写进去的 `Windows\\System32\\DriverStore\\...` 全是 `ns=0`）。
POSIX 命名的条目在 Win32 子集里是"半可见"的：Explorer / cmd 有时能看到，但
**SetupAPI 读不了** —— 结果是这台 Win7 里每一个设备都装不上驱动：

    ! inf: Unable to load INF:
        'C:\\Windows\\System32\\DriverStore\\FileRepository\\disk.inf_amd64_...\\disk.inf'
        (e0000003)
    ! inf: Error 0xe0000003: The syntax of the INF is invalid.
    ! dvi: Error 0xe0000228: There are no compatible drivers for this device.

更坑的是：文件内容、`$DATA`、VDL、时间戳、`$STANDARD_INFORMATION` 都是好的
（和源 WIM 逐字节一致），所以从镜像里"读文件"永远看不出问题。

用法：
    python3 native/scripts/patch-ntfs3g-name-namespace.py <ntfs-3g 源码目录>
"""
import pathlib
import sys

MARKER = "mirrorbox_win32_file_name"

ANCHOR = "\tfn->file_name_type = FILE_NAME_POSIX;\n"

REPLACEMENT = (
    "\t/*\n"
    "\t * mirrorbox_win32_file_name: 用 Win32 命名空间建名字（Windows 自己就是这么做的）。\n"
    "\t * POSIX 命名的条目在 Win32 子集里是「半可见」的：Explorer/cmd 可能看得到，\n"
    "\t * 但 SetupAPI / 驱动安装读不了 —— 实测表现为 DriverStore 里的 INF 全部\n"
    "\t * 0xE0000003「INF 语法无效」，于是所有设备都「找不到驱动程序」。\n"
    "\t */\n"
    "\tfn->file_name_type = FILE_NAME_WIN32;\n"
)


def apply_patch(src_dir: pathlib.Path) -> int:
    src = src_dir / "libntfs-3g" / "dir.c"
    if not src.exists():
        raise SystemExit(f"找不到 {src}")
    text = src.read_text()
    if MARKER in text:
        print("libntfs-3g 的 Win32 命名空间补丁已在（跳过）")
        return 0
    n = text.count(ANCHOR)
    if n != 1:
        raise SystemExit(f"libntfs-3g 源码结构与预期不符（锚点出现 {n} 次），补丁中止")
    text = text.replace(ANCHOR, REPLACEMENT, 1)
    src.write_text(text)
    print("已打补丁 libntfs-3g/dir.c：新建名字改用 FILE_NAME_WIN32")
    return 0


def main() -> int:
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    return apply_patch(pathlib.Path(sys.argv[1]))


if __name__ == "__main__":
    sys.exit(main())
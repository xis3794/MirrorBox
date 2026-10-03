#!/usr/bin/env bash
#
# 回归守卫：mirrorbox-ntfs 写进 NTFS 的**名字必须是 Win32 命名空间**（ns=1）。
#
# 为什么必须有这条：
#   libntfs-3g 的 ntfs_create() 默认把 $FILE_NAME 的 file_name_type 写成 FILE_NAME_POSIX。
#   我们这样写出来的 Windows 卷，Windows 侧表现是：
#     ! inf: Unable to load INF: '...\DriverStore\FileRepository\disk.inf_amd64_...\disk.inf'
#     ! inf: Error 0xe0000003: The syntax of the INF is invalid.
#     ! dvi: Error 0xe0000228: There are no compatible drivers for this device.
#   于是**所有设备都「找不到驱动程序」**，而文件内容（与源 WIM 逐字节一致）、$DATA、
#   初始化长度、时间戳、属性全是好的 —— 从镜像侧读文件永远发现不了。
#   实测 Windows 自己创建的文件（C:\Windows\Prefetch\*.pf）全是 ns=1。
#
# 这个测试会和真实构建走同一条路：源码取 ntfs-3g + 打我们的命名空间补丁 + 编静态库 +
# 编译我们的工具，然后检查新建条目的 ns。
#
# 用法：native/scripts/test-ntfs3g-win32-names.sh [工作目录]
set -euo pipefail

WORK="${1:-/tmp/mb-win32names}"
HERE="$(cd "$(dirname "$0")" && pwd)"
NATIVE_DIR="$(cd "$HERE/.." && pwd)"
NTFS3G_VERSION="${NTFS3G_VERSION:-2022.10.3}"

log() { printf '\033[1;34m==\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mFAIL:\033[0m %s\n' "$*" >&2; exit 1; }

command -v gcc >/dev/null || die "缺 gcc"
command -v mkntfs >/dev/null || die "缺 mkntfs（apt install ntfs-3g）"
command -v python3 >/dev/null || die "缺 python3"

rm -rf "$WORK"; mkdir -p "$WORK"; cd "$WORK"

log "1) 取 ntfs-3g ${NTFS3G_VERSION} 源码并打命名空间补丁"
SRC=""
TARBALL_NAMES=("ntfs-3g_ntfsprogs-${NTFS3G_VERSION}")
GITHUB_TGZ="https://github.com/tuxera/ntfs-3g/releases/download/${NTFS3G_VERSION}/ntfs-3g_ntfsprogs-${NTFS3G_VERSION}.tgz"
for url in \
  "https://tuxera.com/opensource/ntfs-3g_ntfsprogs-${NTFS3G_VERSION}.tgz" \
  "$GITHUB_TGZ" \
  "https://ghproxy.net/${GITHUB_TGZ}"; do
  if curl -fsSL --max-time 300 -o n.tgz "$url" && gzip -t n.tgz 2>/dev/null; then
    tar xzf n.tgz
    SRC="$WORK/ntfs-3g_ntfsprogs-${NTFS3G_VERSION}"
    break
  fi
  echo "   镜像不可用：$url"
done

if [[ -z "$SRC" ]]; then
  # 兜底：GitHub 的源码包（没有生成好的 configure，得先跑 autogen.sh）
  echo "   回退到 GitHub 源码包（需要 autoconf / automake / libtool）"
  curl -fsSL --max-time 300 -o n2.tgz \
    "https://codeload.github.com/tuxera/ntfs-3g/tar.gz/refs/tags/${NTFS3G_VERSION}" \
    || die "下载 ntfs-3g 源码包失败"
  tar xzf n2.tgz
  SRC="$WORK/ntfs-3g-${NTFS3G_VERSION}"
  (cd "$SRC" && ./autogen.sh > autogen.log 2>&1) \
    || { tail -20 "$SRC/autogen.log"; die "autogen.sh 失败（缺 autoconf/automake/libtool？）"; }
fi
[[ -d "$SRC" ]] || die "解包后找不到源码目录"
python3 "$HERE/patch-ntfs3g-name-namespace.py" "$SRC"
grep -q mirrorbox_win32_file_name "$SRC/libntfs-3g/dir.c" || die "补丁没写进源码"

log "2) 编静态 libntfs-3g"
(cd "$SRC" && ./configure --disable-ntfs-3g --enable-ntfsprogs --disable-crypto \
  --disable-shared --enable-static > cfg.log 2>&1 && make -j"$(nproc)" > make.log 2>&1) \
  || { tail -30 "$SRC/make.log"; die "libntfs-3g 编译失败"; }
LIB="$SRC/libntfs-3g/.libs/libntfs-3g.a"
[[ -f "$LIB" ]] || die "没有编出 libntfs-3g.a"

log "3) 用这个库编译 mirrorbox-ntfs"
gcc -O2 -Wall -Wextra -Wno-unused-parameter \
  -I"$SRC/include" -I"$SRC/libntfs-3g" \
  -o mb-ntfs "$NATIVE_DIR/src-mirrorbox/ntfs-apply.c" "$LIB"

log "4) 造卷 + 写入一棵带深路径的目录树"
truncate -s 64M t.ntfs
mkntfs -F -Q -L WIN32NS t.ntfs > /dev/null 2>&1
DEEP="Windows/System32/DriverStore/FileRepository/disk.inf_amd64_neutral_10ce25bbc5a9cc43"
mkdir -p "tree/$DEEP"
printf 'hello' > "tree/$DEEP/disk.inf"
printf 'x' > tree/README.txt
./mb-ntfs t.ntfs tree > apply.log 2>&1 || { tail -5 apply.log; die "写入 NTFS 失败"; }
tail -1 apply.log

log "5) 新建条目必须是 Win32 命名空间（ns=1）"
./mb-ntfs t.ntfs --ls / | grep -E '^LS ' | tee root.ls
grep -q '^LS README.txt size=1 ns=1 ' root.ls || die "根下的新建文件不是 Win32 命名空间（是 POSIX 名！）"
grep -q '^LS Windows/ size=-1 ns=1 ' root.ls || die "新建目录不是 Win32 命名空间（是 POSIX 名！）"

./mb-ntfs t.ntfs --ls "$DEEP" | grep -E '^LS ' | tee deep.ls
grep -q '^LS disk.inf size=5 ns=1 ' deep.ls || die "深路径下的文件不是 Win32 命名空间"

# "./" 和 "../" 是 readdir 合成出来的，本来就是 ns=0，不算数。
if grep -E '^LS ' root.ls deep.ls | grep -v -E '^LS \.\.?/ ' | grep -q ' ns=0 '; then
  echo "--- 仍然出现 ns=0 的条目 ---"
  grep -E '^LS ' root.ls deep.ls | grep -v -E '^LS \.\.?/ ' | grep ' ns=0 ' || true
  die "仍然写出了 POSIX 命名的条目（Windows 侧会「文件在但读不到」）"
fi

log "全部通过 ✓（新建名字全是 Win32 命名空间，和 Windows 一致）"
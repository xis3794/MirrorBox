#!/usr/bin/env bash
#
# 验证 wimlib 的「link() 失败就退化成复制」补丁。
#
# 背景：Windows 镜像里 winsxs 的同一份数据以多个名字出现（硬链接），某些设备直接拒绝
# link()（实测华为 EACCES），wimlib 会以退出码 35 中止整个释放流程。补丁见
# native/scripts/patch-wimlib-link-fallback.py。
#
# 测试办法：在主机上编一份打过补丁的 wimlib，然后用 LD_PRELOAD 把 link() 一律变成
# EACCES，检查 (1) 释放仍然成功 (2) 目标文件内容是完整的副本 (3) 日志里有回退提示。
#
# 用法：native/scripts/test-wimlib-link-fallback.sh [工作目录]
set -euo pipefail

WORK="${1:-/tmp/mb-wimlib-fallback}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WIMLIB_VERSION="${WIMLIB_VERSION:-1.14.4}"

log() { printf '\033[1;34m==\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mFAIL:\033[0m %s\n' "$*" >&2; exit 1; }

command -v gcc >/dev/null || die "缺 gcc"
command -v python3 >/dev/null || die "缺 python3"

rm -rf "$WORK"
mkdir -p "$WORK"
cd "$WORK"

log "1) 取 wimlib ${WIMLIB_VERSION} 并打补丁"
curl -sSL --max-time 300 -o wimlib.tar.gz "https://wimlib.net/downloads/wimlib-${WIMLIB_VERSION}.tar.gz"
tar xzf wimlib.tar.gz
mv "wimlib-${WIMLIB_VERSION}" src
python3 "$HERE/patch-wimlib-link-fallback.py" src
grep -q mirrorbox_copy_file src/src/unix_apply.c || die "补丁没写进源码"

log "2) 编译主机版 wimlib"
(cd src && ./configure --without-ntfs-3g --without-fuse > cfg.log 2>&1 \
  && make -j"$(nproc)" > make.log 2>&1) || { tail -30 src/make.log; die "编译失败"; }
WIM="$WORK/src/wimlib-imagex"
[[ -x "$WIM" ]] || die "没有编出 wimlib-imagex"

log "3) 造一棵含硬链接的目录树，捕获成 WIM"
mkdir -p tree/dir_a tree/dir_b
printf 'MIRRORBOX-HARDLINK-CONTENT\n' > tree/dir_a/payload.bin
ln tree/dir_a/payload.bin tree/dir_b/payload.bin
[[ "$(stat -c %i tree/dir_a/payload.bin)" == "$(stat -c %i tree/dir_b/payload.bin)" ]] \
  || die "测试树里没有真的硬链接"
"$WIM" capture tree out.wim --compress=none > cap.log 2>&1 || { tail -20 cap.log; die "capture 失败"; }

log "4) 正常释放（link() 可用）：必须是真硬链接，证明测试有意义"
"$WIM" apply out.wim 1 dst_ok > app_ok.log 2>&1 || { tail -20 app_ok.log; die "正常释放失败"; }
[[ "$(stat -c %i dst_ok/dir_a/payload.bin)" == "$(stat -c %i dst_ok/dir_b/payload.bin)" ]] \
  || die "正常路径下没走硬链接，本测试无法证明回退有效"

log "5) 用 LD_PRELOAD 让 link() 恒返回 EACCES，验证回退（符号链接）"
cat > nohardlink.c <<'EOF'
#define _GNU_SOURCE
#include <errno.h>
#include <unistd.h>

int link(const char *oldpath, const char *newpath)
{
	(void)oldpath;
	(void)newpath;
	errno = EACCES;
	return -1;
}
EOF
gcc -shared -fPIC -o libnohardlink.so nohardlink.c
LD_PRELOAD="$WORK/libnohardlink.so" "$WIM" apply out.wim 1 dst_fb > app_fb.log 2>&1 \
  || {
    grep -q 'Can.t create hard link' app_fb.log || true
    tail -20 app_fb.log
    die "link() 被拒时释放失败了"
  }
[[ -L dst_fb/dir_b/payload.bin ]] || {
  tail -20 app_fb.log
  die "回退后不是符号链接（是不是又退化成复制了？复制会吃掉几个 GB）"
}
link_size=$(stat -c %s dst_fb/dir_b/payload.bin)
log "   别名是符号链接 ✓（只占 ${link_size} 字节；复制的话会占 $(stat -c %s tree/dir_a/payload.bin) 字节）"

log "6) 符号链接指向的内容必须与源一致"
cmp tree/dir_a/payload.bin dst_fb/dir_b/payload.bin || die "符号链接解析后的内容不一致"
cmp tree/dir_a/payload.bin dst_fb/dir_a/payload.bin || die "第一个别名的内容也不对"

log "全部通过 ✓"

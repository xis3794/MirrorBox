#!/usr/bin/env bash
#
# 验证 mirrorbox-ntfs 的「按需区域 (--regions / NEED / MAP)」协议。
#
# 背景：Android 上要读一个几十 GB 的 NTFS 分区里的 \Boot\BCD，不可能把整个分区提取出来
#（Win7 装完用掉几 GB，临时空间不够），也不能只提取"开头一段"——mkntfs 会把 $MFTMirr/$LogFile
# 放在卷中部、$AttrDef/$Bitmap/$Secure/$UpCase 放在 1/8 处。所以改成"工具按需索取"：
#   工具只信任 --regions 列出的范围，越界读取记为 NEED 并以退出码 10 结束；
#   调用方（App）把这些区域从 qcow2 里补进稀疏窗口文件后重跑，直到成功。
# 本脚本在主机上完整模拟 App 的行为，并校验：
#   1) 收敛所需的区域总量很小（不是把整个卷拉下来）
#   2) 读出来的 \Boot\BCD 字节与源文件一致
#   3) MAP 给出的物理位置正确（就地改写的基础）
#   4) 按 MAP 就地改写后，完整镜像里读到的内容确实变了
#
# 用法：native/scripts/test-ntfs-window.sh [工作目录]
set -euo pipefail

WORK="${1:-/tmp/mb-ntfs-window}"
SRC_DIR="$(cd "$(dirname "$0")/../src-mirrorbox" && pwd)"
TOOL="${WORK}/mb-ntfs"
IMG="${WORK}/full.img"
WIN="${WORK}/window.img"
REG="${WORK}/regions.txt"
OUT="${WORK}/bcd-dump.bin"

IMG_SIZE="${MB_TEST_IMG_SIZE:-2G}"
MAX_ROUNDS="${MB_TEST_MAX_ROUNDS:-40}"
MAX_WINDOW_BYTES="${MB_TEST_MAX_WINDOW_BYTES:-16777216}" # 16 MiB 上限：超过说明协议退化了

log() { printf '\033[1;34m==\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mFAIL:\033[0m %s\n' "$*" >&2; exit 1; }

command -v gcc >/dev/null || die "gcc 缺失"
command -v mkntfs >/dev/null || die "mkntfs 缺失（apt install ntfs-3g）"
[[ -f "${SRC_DIR}/ntfs-apply.c" ]] || die "找不到 ${SRC_DIR}/ntfs-apply.c"

rm -rf "${WORK}"
mkdir -p "${WORK}/src/Boot" "${WORK}/src/Windows/System32"
cd "${WORK}"

log "1) 编译 mirrorbox-ntfs（链接 libntfs-3g）"
gcc -O2 -Wall -Wextra -Wno-unused-parameter -o "${TOOL}" "${SRC_DIR}/ntfs-apply.c" -lntfs-3g

log "2) 造一个 ${IMG_SIZE} 的 NTFS 卷并写入目录树"
truncate -s "${IMG_SIZE}" "${IMG}"
mkntfs -F -Q -L MIRRORBOX "${IMG}" > mkntfs.log 2>&1 || { tail -5 mkntfs.log; die "mkntfs 失败"; }

# 伪 BCD：256 KiB、足够大所以是"非常驻"（真 BCD 也是），里面放一个 UTF-16LE 设备项
python3 - "${WORK}/src/Boot/BCD" <<'PY'
import struct, sys
b = bytearray(262144)
s = '\\Device\\HarddiskVolume1'.encode('utf-16-le')
b[0x120:0x120 + len(s)] = s
struct.pack_into('<Q', b, 0x1000, 1048576)     # 伪设备项：分区偏移
struct.pack_into('<I', b, 0x1008, 0xDEADBEEF)  # 伪设备项：磁盘签名
open(sys.argv[1], 'wb').write(b)
PY
head -c 2000000 /dev/urandom > src/Windows/System32/kernel32.dll
head -c 1500000 /dev/urandom > src/Windows/System32/ntdll.dll
head -c  300000 /dev/urandom > src/Windows/System32/ntoskrnl.exe
# 一个很小的"常驻"文件：用来覆盖 MAP 的常驻分支（数据直接存在 MFT 记录里）
printf 'MIRRORBOX-RESIDENT-DATA' > src/Windows/small.txt
"${TOOL}" "${IMG}" src > apply.log 2>&1 || { tail -5 apply.log; die "目录树写入失败"; }
log "   $(tail -1 apply.log)"

log "3) 稀疏窗口 + 按需补齐循环"
truncate -s "$(stat -c %s "${IMG}")" "${WIN}"
: > "${REG}"
size="$(stat -c %s "${IMG}")"
materialized=0
rounds=0

for ((round = 1; round <= MAX_ROUNDS; round++)); do
  rounds="${round}"
  set +e
  out="$("${TOOL}" "${WIN}" --regions "${REG}" --dump 'Boot/BCD' "${OUT}" 2>&1)"
  rc=$?
  set -e
  case "${rc}" in
    0) log "   第 ${round} 轮：成功"; break ;;
    10) ;;
    *) echo "${out}" | tail -20; die "工具异常退出 rc=${rc}" ;;
  esac
  needs="$(echo "${out}" | awk '/^NEED / {print $2, $3}')"
  [[ -n "${needs}" ]] || { echo "${out}" | tail -20; die "没有 NEED 却也不成功（协议卡死）"; }
  while read -r off len; do
    [[ -n "${off:-}" ]] || continue
    end=$((off + len))
    if (( end > size )); then len=$((size - off)); fi
    (( len > 0 )) || continue
    dd if="${IMG}" of="${WIN}" bs=4096 skip=$((off / 4096)) seek=$((off / 4096)) \
       count=$((len / 4096)) conv=notrunc status=none
    echo "${off} ${len}" >> "${REG}"
    materialized=$((materialized + len))
  done <<< "${needs}"
  log "   第 ${round} 轮：补齐 $(awk -v b="${materialized}" 'BEGIN{printf "%.2f", b/1048576}') MiB / $(wc -l < "${REG}") 个区域"
done

if (( rounds >= MAX_ROUNDS )); then die "超过 ${MAX_ROUNDS} 轮仍未收敛"; fi
if (( materialized > MAX_WINDOW_BYTES )); then
  die "按需区域超过上限（${materialized} > ${MAX_WINDOW_BYTES} 字节）"
fi

log "4) 校验读出的 BCD 内容"
cmp -s src/Boot/BCD "${OUT}" || die "BCD 内容不一致"
log "   BCD 字节一致 ✓（补齐 $(awk -v b="${materialized}" 'BEGIN{printf "%.2f", b/1048576}') MiB，窗口实占 $(du -h "${WIN}" | cut -f1)）"

log "5) 校验 MAP（物理位置）+ 就地改写"
"${TOOL}" "${WIN}" --regions "${REG}" --dump 'Boot/BCD' "${OUT}" 2>/dev/null | grep '^MAP ' > map.txt || true
[[ -s map.txt ]] || die "工具没有输出 MAP"
cat map.txt
python3 - "${WORK}" <<'PY'
import sys
work = sys.argv[1]
maps = []
for line in open(work + '/map.txt'):
    p = line.split()
    if len(p) == 4 and p[0] == 'MAP':
        maps.append((int(p[1]), int(p[2]), int(p[3])))
assert maps, 'MAP 为空'
src = open(work + '/src/Boot/BCD', 'rb').read()
img = open(work + '/full.img', 'rb')
for fo, doff, ln in maps:
    img.seek(doff)
    if img.read(ln) != src[fo:fo + ln]:
        raise SystemExit('MAP 位置不正确: file_off=%d disk_off=%d len=%d' % (fo, doff, ln))
print('MAP 校验通过：%d 段' % len(maps))
# 模拟 App 的就地改写：把 \Device\HarddiskVolume1 改成 Volume2
needle = '\\Device\\HarddiskVolume'.encode('utf-16-le')
i = src.find(needle)
assert i >= 0, '找不到设备项'
target = [d for fo, d, ln in maps if fo <= i < fo + ln]
assert target, '设备项不在任何 MAP 段里'
with open(work + '/full.img', 'r+b') as f:
    f.seek(target[0] + (i - fo))
    f.write(b'2')
print('已就地改写 disk_off=%d' % (target[0] + (i - fo)))
PY

log "6) 用完整镜像确认就地改写生效"
if ! "${TOOL}" "${IMG}" --dump 'Boot/BCD' after.bcd > dump_after.log 2>&1; then
  tail -20 dump_after.log
  die "从完整镜像读取 Boot/BCD 失败（就地改写之后）"
fi
tail -2 dump_after.log
ls -l after.bcd
python3 - "${WORK}" <<'PY'
import sys
work = sys.argv[1]
b = open(work + '/after.bcd', 'rb').read()
n = '\\Device\\HarddiskVolume'.encode('utf-16-le')
i = b.find(n)
assert i >= 0, '完整镜像里找不到设备项（dump 出 %d 字节）' % len(b)
text = b[i:i + len(n) + 2].decode('utf-16-le')
print('  完整镜像里现在是:', repr(text))
if not text.endswith('2'):
    raise SystemExit('就地改写没有生效')
PY

log "7) 常驻文件（数据存在 MFT 记录里）的 MAP 也要正确"
"${TOOL}" "${IMG}" --dump 'Windows/small.txt' small.bin 2>/dev/null | grep '^MAP ' > small.map || true
[[ -s small.map ]] || die "常驻文件没有输出 MAP"
cat small.map
cmp -s src/Windows/small.txt small.bin || die "常驻文件 dump 内容不一致"
python3 - "${WORK}" <<'PY'
import sys
work = sys.argv[1]
p = open(work + '/small.map').read().split()
fo, disk, ln = int(p[1]), int(p[2]), int(p[3])
src = open(work + '/src/Windows/small.txt', 'rb').read()
with open(work + '/full.img', 'rb') as f:
    f.seek(disk)
    got = f.read(ln)
assert got == src[fo:fo + ln], '常驻 MAP 位置不正确: disk=%d len=%d' % (disk, ln)
print('常驻 MAP 校验通过：disk=%d len=%d' % (disk, ln))
PY

log "全部通过 ✓（${rounds} 轮，补齐 $(awk -v b="${materialized}" 'BEGIN{printf "%.2f", b/1048576}') MiB）"

#!/usr/bin/env bash
#
# 用「按需区域」协议从 qcow2 里的某个分区取出一个客户机文件（不需要提取整个分区）。
# 这套流程就是 App 里 BcdFix.readBcd 做的事，主机上复现一遍用来诊断用户镜像。
#
# 用法：
#   tools/mb-ntfs-extract.sh <qcow2> <分区起始字节> <分区字节数> <客户机路径> <输出文件> [mirrorbox-ntfs 可执行文件]
#
# 例：
#   tools/mb-ntfs-extract.sh disk.qcow2 1048576 17178820608 'Boot/BCD' /tmp/BCD.bin
set -uo pipefail

IMG="$1"; PART_START="$2"; PART_SIZE="$3"; GUEST="$4"; OUT="$5"
TOOL="${6:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${TMPDIR:-/tmp}/mb-extract-$$"
mkdir -p "$WORK"
WIN="$WORK/window.raw"
REG="$WORK/regions.txt"
MAX_ROUNDS="${MB_MAX_ROUNDS:-200}"

if [[ -z "$TOOL" ]]; then
  for cand in "$HERE/../native/build/mirrorbox-ntfs" "$(command -v mirrorbox-ntfs || true)" /tmp/ntw2/mb-ntfs; do
    [[ -x "$cand" ]] && TOOL="$cand" && break
  done
fi
[[ -x "${TOOL:-}" ]] || { echo "找不到 mirrorbox-ntfs 可执行文件（可传第 6 个参数）" >&2; exit 2; }

# 窗口文件必须"看起来"就是整个分区（NTFS 才知道卷多大），数据只写需要的区域
truncate -s "$PART_SIZE" "$WIN"
: > "$REG"
total=0

# 工具参数：默认 --dump；用 MB_TOOL_ARGS 可以换成 --ls 之类的诊断模式
if [[ -n "${MB_TOOL_ARGS:-}" ]]; then
  read -r -a TOOL_ARGS <<< "${MB_TOOL_ARGS}"
else
  TOOL_ARGS=( --dump "$GUEST" "$OUT" )
fi

for ((round = 1; round <= MAX_ROUNDS; round++)); do
  out="$("$TOOL" "$WIN" --regions "$REG" "${TOOL_ARGS[@]}" 2>&1)"
  rc=$?
  if [[ $rc -eq 0 ]]; then
    if [[ -n "${MB_TOOL_ARGS:-}" ]]; then
      echo "$out" | grep -E '^(LS|LSCOUNT|ATTRS|STAT|DATA|ATTRTYPE|ATTRCOUNT)' || echo "$out" | tail -3
    fi
    echo "第 $round 轮完成（补齐 $(awk -v b=$total 'BEGIN{printf "%.2f", b/1048576}') MiB）" >&2
    break
  fi
  needs="$(echo "$out" | awk '/^NEED /{print $2" "$3}')"
  [[ -n "$needs" ]] || { echo "$out" | tail -5 >&2; echo "工具没有给出新的区域请求" >&2; exit 3; }
  spec=""
  while read -r off len; do
    [[ -n "${off:-}" ]] || continue
    end=$((off + len))
    (( end > PART_SIZE )) && len=$((PART_SIZE - off))
    (( len > 0 )) || continue
    spec+="${PART_START}+${off}:${len},"
    echo "$off $len" >> "$REG"
    total=$((total + len))
  done <<< "$needs"
  python3 "$HERE/qcow2-read.py" "$IMG" --windows "${spec%,}" --out "$WIN" > /dev/null || exit 4
done

echo "== $GUEST：$(stat -c %s "$OUT" 2>/dev/null || echo 0) 字节，补齐 $(awk -v b=$total 'BEGIN{printf "%.2f", b/1048576}') MiB ==" >&2
rm -rf "$WORK"
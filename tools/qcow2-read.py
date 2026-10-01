#!/usr/bin/env python3
"""qcow2 区间读取 / 诊断（零依赖，逐簇随机读，不会把镜像全读进内存）。

用途：
    # 看 MBR 分区表 + 磁盘签名（判断第一遍开机后签名有没有被改）
    python3 tools/qcow2-read.py disk.qcow2 --mbr

    # 按虚拟偏移十六进制查看
    python3 tools/qcow2-read.py disk.qcow2 --hex 0 512

    # 把若干"虚拟偏移区间"抽进一个稀疏文件（模拟 App 的按需窗口）
    python3 tools/qcow2-read.py disk.qcow2 --windows 0:65536,1342799872:131072 --out win.raw

    # 整段导出（可能很大，谨慎）
    python3 tools/qcow2-read.py disk.qcow2 --extract 1048576:1048576 --out head.bin

qcow2 映射：header → L1 表 → L2 表 → 簇。L2 表项低 9 位是标志（bit0=zero/bit1=compressed/
bit63=copied），因此簇偏移要 `& ~0x1FF`。压缩簇用 zlib 解（低 9 位里的 bit62/位数会被读取器忽略）。
"""
import argparse
import struct
import sys
import zlib

ZERO_FLAG = 1 << 0
COPIED_FLAG = 1 << 63
COMPRESSED_FLAG = 1 << 62
# 簇偏移在 bit9..55（bit0 是零簇、bit1 是压缩、bit62/63 是 copied/压缩标志）
OFFSET_MASK = 0x00FFFFFFFFFFFE00


class Qcow2:
    def __init__(self, path):
        self.f = open(path, "rb")
        hdr = self.f.read(112)
        if hdr[:4] != b"QFI\xfb":
            raise SystemExit("不是 qcow2（缺少 QFI\\xfb 魔数）")
        self.version = struct.unpack_from(">I", hdr, 4)[0]
        self.cluster_bits = struct.unpack_from(">I", hdr, 20)[0]
        self.vsize = struct.unpack_from(">Q", hdr, 24)[0]
        self.l1_size = struct.unpack_from(">I", hdr, 36)[0]
        self.l1_off = struct.unpack_from(">Q", hdr, 40)[0]
        self.cluster = 1 << self.cluster_bits
        self.l2_bits = self.cluster_bits - 3
        self.l2_mask = (1 << self.l2_bits) - 1
        self.copies = 0          # 实际读到的字段数（诊断用）
        self.mapped_clusters = 0
        # 压缩簇解压失败计数（静默补 0 会让上层误判"内容为空"）
        self.zfail = 0
        self.zfail_detail = []
        # l1 表里每一项覆盖 (1<<l2_bits) 个簇
        self.l1_bytes = self.l1_size * 8
        self.l2_count_per_l1 = 1 << self.l2_bits

    def _be64(self, off):
        self.f.seek(off)
        return struct.unpack(">Q", self.f.read(8))[0]

    def _read(self, off, n):
        self.f.seek(off)
        return self.f.read(n)

    def _cluster_offset(self, virtual_off):
        """虚拟偏移 → 宿主文件偏移；未分配返回 None。"""
        idx = virtual_off >> self.cluster_bits
        in_cl = virtual_off & (self.cluster - 1)
        l1i = idx >> self.l2_bits
        l2i = idx & self.l2_mask
        if l1i >= self.l1_size:
            return None
        e1 = self._be64(self.l1_off + 8 * l1i)
        if e1 == 0:
            return None
        l2_off = e1 & OFFSET_MASK
        e2 = self._be64(l2_off + 8 * l2i)
        if e2 == 0:
            return None
        if e2 & ZERO_FLAG:
            return None
        if e2 & COMPRESSED_FLAG:
            # 压缩簇描述符：x = 62 - (cluster_bits - 8)
            #   bit 0..x-1 : 宿主偏移（通常不对齐）
            #   bit x..61  : 压缩数据还要额外的多少个 512 字节扇区
            #   bit 62     : compressed，bit 63 : copied
            x = 62 - (self.cluster_bits - 8)
            off = e2 & ((1 << x) - 1)
            extra = (e2 >> x) & ((1 << (62 - x)) - 1)
            return ("compressed", off, extra)
        coff = e2 & OFFSET_MASK
        return ("data", coff + in_cl)

    def read(self, off, n):
        out = bytearray()
        while n > 0:
            in_cl = off & (self.cluster - 1)
            take = min(self.cluster - in_cl, n)
            m = self._cluster_offset(off)
            if m is None:
                out += b"\x00" * take
            elif m[0] == "data":
                self.f.seek(m[1])
                buf = self.f.read(take)
                out += buf + b"\x00" * (take - len(buf))
            else:
                # 压缩簇：deflate **没有 zlib 头**（见 qcow2 规范），必须 raw inflate
                _, coff, extra = m
                clen = (extra + 1) * 512
                self.f.seek(coff)
                raw = self.f.read(clen)
                dec = b""
                try:
                    d = zlib.decompressobj(-15)
                    dec = d.decompress(raw, self.cluster)
                    if len(dec) < self.cluster:
                        dec += d.flush()
                except zlib.error as exc:
                    # 不能静默补 0：否则 NTFS 索引块会被读成空洞，导致"文件不存在"的假象
                    self.zfail += 1
                    self.zfail_detail.append(
                        (virtual_off, coff, clen, str(exc)))
                    dec = b""
                if len(dec) < self.cluster:
                    dec += b"\x00" * (self.cluster - len(dec))
                out += dec[in_cl:in_cl + take]
            off += take
            n -= take
        return bytes(out)


def parse_windows(spec):
    """返回 [(源偏移, 目标偏移, 长度)]。

    "off:len"        → 源=目标=off（整盘窗口）
    "base+rel:len"   → 源=base+rel，目标=rel（分区窗口：文件里只有分区内的偏移）
    """
    out = []
    for part in spec.split(","):
        part = part.strip()
        if not part:
            continue
        off_s, _, ln_s = part.partition(":")
        off_s = off_s.strip()
        ln = int(ln_s, 0)
        if "+" in off_s:
            base, rel = off_s.split("+", 1)
            out.append((int(base, 0) + int(rel, 0), int(rel, 0), ln))
        else:
            off = int(off_s, 0)
            out.append((off, off, ln))
    return out


def print_mbr(img):
    mbr = img.read(0, 512)
    if mbr[510:512] != b"\x55\xaa":
        print("!! 没有 0x55AA 引导签名（可能是 GPT 或未分区）")
    sig = struct.unpack_from("<I", mbr, 440)[0]
    print(f"磁盘签名 = 0x{sig:08X}（MBR bytes 440..443）")
    print("分区表：")
    for i in range(4):
        e = 0x1BE + 16 * i
        boot, ptype = mbr[e], mbr[e + 4]
        lba, cnt = struct.unpack_from("<II", mbr, e + 8)
        if ptype == 0 and cnt == 0:
            continue
        print(
            f"  [{i + 1}] 类型=0x{ptype:02X} active={boot == 0x80} "
            f"起始LBA={lba}（{lba * 512} 字节）扇区数={cnt} 大小={cnt * 512 / 1048576:.1f} MiB"
        )
    print("")
    print("引导代码前 16 字节:", " ".join(f"{b:02x}" for b in mbr[:16]))
    if mbr[0x1BE + 4] == 0xEE:
        print("!! 检测到 GPT 保护分区表（EFI PART 签名在 LBA1）")


def main():
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("path")
    ap.add_argument("--mbr", action="store_true")
    ap.add_argument("--hex", nargs=2, type=lambda s: int(s, 0))
    ap.add_argument("--windows")
    ap.add_argument("--extract", nargs="?", const="")
    ap.add_argument("--out")
    args = ap.parse_args()

    img = Qcow2(args.path)
    print(
        f"# {args.path}: qcow2 v{img.version} cluster={img.cluster} "
        f"virtual={img.vsize} ({img.vsize / 1073741824:.2f} GiB)",
        file=sys.stderr,
    )

    if args.mbr:
        print_mbr(img)
        return

    if args.hex:
        off, ln = args.hex
        data = img.read(off, ln)
        for i in range(0, len(data), 16):
            chunk = data[i:i + 16]
            print(
                f"{off + i:08x}  " + " ".join(f"{b:02x}" for b in chunk).ljust(47) + " " +
                "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
            )
        return

    if args.windows:
        if not args.out:
            raise SystemExit("--windows 需要 --out")
        total = 0
        with open(args.out, "r+b" if _exists(args.out) else "wb") as fh:
            for src, dst, ln in parse_windows(args.windows):
                data = img.read(src, ln)
                fh.seek(dst)
                if any(data):
                    fh.write(data)
                total += ln
        if img.zfail:
            print(f"WARNING: {img.zfail} 个压缩簇解压失败（这些位置被当成 0，"
                  f"上层可能误判内容为空）", file=sys.stderr)
            for v, coff, clen, err in img.zfail_detail[:5]:
                print(f"  虚拟偏移 {v} 宿主 {coff} 长度 {clen}: {err}", file=sys.stderr)
        print(f"写入 {args.out}：{total} 字节（{total / 1048576:.2f} MiB，全 0 段留空洞）")
        return

    if args.extract is not None:
        spec = args.extract or ""
        off, _, ln = spec.partition(":")
        off = int(off, 0)
        ln = int(ln, 0) if ln else img.vsize - off
        if not args.out:
            raise SystemExit("--extract 需要 --out")
        remaining, pos = ln, off
        with open(args.out, "wb") as fh:
            while remaining > 0:
                take = min(8 << 20, remaining)
                fh.write(img.read(pos, take))
                pos += take
                remaining -= take
        print(f"导出 {ln} 字节 → {args.out}")
        return

    print_mbr(img)


def _exists(p):
    import os
    return os.path.exists(p)


if __name__ == "__main__":
    main()
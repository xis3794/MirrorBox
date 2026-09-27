#!/usr/bin/env python3
"""BCD（Windows 启动配置数据）解析器 —— 把 hive 与 BCD 元素翻译成可读文本。

BCD 文件本身是一个注册表 hive（`regf`）：
    Objects\\{GUID}\\Elements\\<8 位十六进制元素类型>  →  值 `Element` = REG_BINARY（元素数据）
其中 device 元素（11000001 / 12000002 / 22000002 / 21000001）保存"系统分区在哪块盘的哪个偏移"，
就是 bootmgr 报 0xC000000E（引导选择失败，因为需要的设备不可访问）时对不上本磁盘的东西。

用法：
    python3 tools/bcd-inspect.py <BCD 文件> --elements   # 只解元素（推荐）
    python3 tools/bcd-inspect.py <BCD 文件> --tree       # 整棵键树
    python3 tools/bcd-inspect.py <BCD 文件> --scan       # 原始扫描（字符串 / 常量）
"""
import re
import struct
import sys

ELEMENT_NAMES = {
    0x11000001: "device",
    0x12000001: "application",
    0x12000002: "device",
    0x21000001: "device(旧型)",
    0x22000002: "osdevice",
    0x23000003: "default",
    0x24000001: "displayorder",
    0x24000002: "bootsequence",
    0x25000004: "timeout",
    0x12000004: "description",
    0x12000005: "locale",
    0x14000006: "inherit",
    0x12000030: "path",
}
HEX8 = re.compile(r"^[0-9a-f]{8}$")


class Hive:
    def __init__(self, data: bytes):
        self.d = data
        if data[:4] != b"regf":
            raise SystemExit("不是注册表 hive（缺少 regf 魔数）")
        self.root = self.u32(0x24)
        self.bins = self.u32(0x28)
        self.hbin = 0x1000
        self.visited = set()

    def u16(self, o):
        return struct.unpack_from("<H", self.d, o)[0]

    def u32(self, o):
        return struct.unpack_from("<I", self.d, o)[0]

    def cell(self, rel):
        """cell 相对偏移 → 数据起点（跳过 4 字节 size）。"""
        return self.hbin + rel + 4

    def name_at(self, o, n, ascii_name):
        raw = self.d[o:o + n]
        return raw.decode("latin-1") if ascii_name else raw.decode("utf-16-le", "replace")

    def values(self, rel):
        """key cell 的所有值 → [(name, type, data), ...]"""
        o = self.cell(rel)
        nval = self.u32(o + 0x24)
        vlist = self.u32(o + 0x28)
        out = []
        if not nval or vlist in (0, 0xFFFFFFFF):
            return out
        base = self.cell(vlist)
        for i in range(nval):
            v = self.u32(base + 4 * i)
            vo = self.cell(v)
            nlen = self.u16(vo + 2)          # vk: sig(2) + name_len(2) + data_size(4) …
            dsize = self.u32(vo + 4)
            doff = self.u32(vo + 8)
            dtype = self.u32(vo + 12)
            flags = self.u16(vo + 16)
            inline = bool(dsize & 0x80000000)
            size = dsize & 0x7FFFFFFF
            vname = self.name_at(vo + 20, nlen, bool(flags & 1))
            if inline:
                raw = struct.pack("<I", doff)[:max(0, min(4, size))]
            else:
                raw = self.d[self.cell(doff):self.cell(doff) + size]
            out.append((vname, dtype, raw))
        return out

    def subkeys(self, rel):
        o = self.cell(rel)
        n = self.u32(o + 0x14)
        slist = self.u32(o + 0x1C)
        return self._sk_list(slist, n) if n and slist not in (0, 0xFFFFFFFF) else []

    def _sk_list(self, rel, expect):
        if rel in (0, 0xFFFFFFFF):
            return []
        o = self.cell(rel)
        sig = self.d[o:o + 2]
        n = self.u16(o + 2)
        if sig in (b"lf", b"lh"):
            return [self.u32(o + 4 + i * 8) for i in range(n)]
        if sig == b"li":
            return [self.u32(o + 4 + i * 4) for i in range(n)]
        if sig == b"ri":
            out = []
            for i in range(n):
                out.extend(self._sk_list(self.u32(o + 4 + i * 4), 0))
            return out
        return []

    def key(self, rel):
        o = self.cell(rel)
        flags = self.u16(o + 2)
        nlen = self.u16(o + 0x48)
        return self.name_at(o + 0x4C, nlen, bool(flags & 0x20))


def hexdump(b, indent="    "):
    lines = []
    for i in range(0, len(b), 16):
        chunk = b[i:i + 16]
        lines.append(f"{indent}+0x{i:04x}  " + " ".join(f"{x:02x}" for x in chunk).ljust(47) + " " +
                     "".join(chr(x) if 32 <= x < 127 else "." for x in chunk))
    return "\n".join(lines) if lines else f"{indent}(空)"


def decode_element(raw: bytes, label: str, is_device: bool):
    print(f"  {label}: len={len(raw)}")
    print(hexdump(raw))
    if is_device and len(raw) >= 0x3C:
        off = struct.unpack_from("<Q", raw, 0x20)[0]
        flag = struct.unpack_from("<I", raw, 0x34)[0]
        sig = struct.unpack_from("<I", raw, 0x38)[0]
        print(f"    → 分区偏移={off}（LBA {off // 512}）flag={flag} 磁盘签名=0x{sig:08X}")
    for i in range(0, max(0, len(raw) - 7)):
        v = struct.unpack_from("<Q", raw, i)[0]
        if v and v % 512 == 0 and 512 <= v <= (1 << 42):
            sig = struct.unpack_from("<I", raw, i + 8)[0] if i + 12 <= len(raw) else 0
            print(f"    +0x{i:02x}: u64={v}（LBA {v // 512}, {v // 1024} KiB）后 u32=0x{sig:08X}")
    txt = raw.decode("utf-16-le", "ignore").replace("\x00", "").strip()
    if txt and txt.isprintable():
        print(f"    utf16: {txt!r}")


def walk(h: Hive, rel, depth, path, want):
    if rel in h.visited:
        return
    h.visited.add(rel)
    name = h.key(rel)
    here = f"{path}\\{name}" if name else path
    is_type_key = bool(HEX8.match(name))
    etype = int(name, 16) if is_type_key else None
    label = ELEMENT_NAMES.get(etype, "") if etype is not None else ""

    if want == "elements":
        if is_type_key:
            print(f"\n=== {here}  ({label or '未知元素'}) ===")
            for vname, dtype, raw in h.values(rel):
                decode_element(raw, f"[{name}] 值 {vname} type={dtype}", "device" in label)
        else:
            for vname, dtype, raw in h.values(rel):
                if vname in ("Description", "Application", "Path"):
                    txt = raw.decode("utf-16-le", "ignore").replace("\x00", "").strip()
                    if txt.isprintable() and txt:
                        print(f"  [{here}] {vname} = {txt!r}")
    else:
        print(f"{'  ' * depth}[{here}]")
        for vname, dtype, raw in h.values(rel):
            print(f"{'  ' * depth}  {vname} type={dtype} len={len(raw)}")
            if len(raw) <= 96:
                print(hexdump(raw, "  " * depth + "    "))

    for s in h.subkeys(rel):
        walk(h, s, depth + 1, here, want)


def scan_mode(path):
    d = open(path, "rb").read()
    print("== UTF-16LE 字符串（>=6 字符）==")
    i = 0
    while i + 12 < len(d):
        s = []
        j = i
        while j + 1 < len(d) and 32 <= d[j] < 127 and d[j + 1] == 0:
            s.append(chr(d[j]))
            j += 2
        if len(s) >= 6:
            print(f"  0x{i:06x}  {''.join(s)}")
            i = j
        else:
            i += 1


def device_mode(path):
    """结构扫描：找出所有 BCD device 元素，打印它期望的（分区偏移, 磁盘签名）。

    经验布局（0x11000001 / 0x12000002 / 0x22000002 / 0x21000001 元素数据）：
        +0x10 u32 = 6        （设备类）
        +0x18 u32 = 0x48     （数据长度 72）
        +0x20 u64 = 分区字节偏移
        +0x34 u32 = 1        （分区号/标志）
        +0x38 u32 = 磁盘签名
    """
    d = open(path, "rb").read()
    hits = []
    for p in range(0, len(d) - 0x40):
        if d[p:p + 16] != b"\x00" * 16:          # 结构前面 16 字节是 0
            continue
        if struct.unpack_from("<I", d, p + 0x10)[0] != 6:   # 设备类（6 = 分区）
            continue
        if struct.unpack_from("<I", d, p + 0x18)[0] != 0x48:  # 结构长度 72
            continue
        off = struct.unpack_from("<Q", d, p + 0x20)[0]
        if off == 0 or off % 512 or off > (1 << 42):
            continue
        flag = struct.unpack_from("<I", d, p + 0x34)[0]
        if flag > 4:
            continue
        sig = struct.unpack_from("<I", d, p + 0x38)[0]
        if sig == 0 or sig == 0xFFFFFFFF:
            continue
        if struct.unpack_from("<I", d, p + 0x3C)[0] != 0:
            continue
        hits.append((p, off, sig, flag))

    print(f"== 候选 device 元素：{len(hits)} 个 ==")
    sigs, offs = set(), set()
    for p, off, sig, flag in hits:
        print(f"  @0x{p:06x} 分区偏移={off}（LBA {off // 512}）签名=0x{sig:08X} flag={flag}")
        sigs.add(sig)
        offs.add(off)
    print(f"== 期望签名集合: {[hex(s) for s in sigs]}；期望偏移集合: {sorted(offs)} ==")


def find_device_structs(d):
    hits = []
    for p in range(0, len(d) - 0x40):
        if d[p:p + 16] != b"\x00" * 16:
            continue
        if struct.unpack_from("<I", d, p + 0x10)[0] != 6:
            continue
        if struct.unpack_from("<I", d, p + 0x18)[0] != 0x48:
            continue
        off = struct.unpack_from("<Q", d, p + 0x20)[0]
        if off == 0 or off % 512 or off > (1 << 42):
            continue
        flag = struct.unpack_from("<I", d, p + 0x34)[0]
        if flag > 4:
            continue
        sig = struct.unpack_from("<I", d, p + 0x38)[0]
        if sig == 0 or sig == 0xFFFFFFFF:
            continue
        if struct.unpack_from("<I", d, p + 0x3C)[0] != 0:
            continue
        hits.append((p, off, sig, flag))
    return hits


def patch_mode(path, new_off, new_sig, out_path):
    d = bytearray(open(path, "rb").read())
    hits = find_device_structs(d)
    print(f"== 找到 {len(hits)} 处 device 结构，改为 offset={new_off}（LBA {new_off // 512}）sig=0x{new_sig:08X} ==")
    for p, off, sig, flag in hits:
        struct.pack_into("<Q", d, p + 0x20, new_off)
        struct.pack_into("<I", d, p + 0x38, new_sig)
        print(f"  @0x{p:06x}: ({off}, 0x{sig:08X}) → ({new_off}, 0x{new_sig:08X})")
    open(out_path, "wb").write(d)
    again = find_device_structs(d)
    print("== 改后复查: " + ", ".join(f"offset={o} sig=0x{s:08X}" for _, o, s, _ in again) + " ==")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        raise SystemExit(__doc__)
    path = args[0]
    if "--scan" in sys.argv:
        scan_mode(path)
        return
    if "--device" in sys.argv:
        device_mode(path)
        return
    if "--patch" in sys.argv:
        # --patch <新偏移> <新签名hex> <输出文件>
        vals = sys.argv[sys.argv.index("--patch") + 1:]
        patch_mode(path, int(vals[0]), int(vals[1], 16), vals[2])
        return
    h = Hive(open(path, "rb").read())
    want = "elements" if "--elements" in sys.argv else "tree"
    print(f"== {path}: hbins {h.bins} 字节，root cell 0x{h.root:x}，模式 {want} ==")
    walk(h, h.root, 0, "", want)
    print(f"\n== 访问了 {len(h.visited)} 个键 ==")


if __name__ == "__main__":
    main()
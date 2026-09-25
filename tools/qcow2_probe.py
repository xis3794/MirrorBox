#!/usr/bin/env python3
"""qcow2 structural probe.

A tiny, dependency-free forensic helper used while developing the Kotlin engine: it dumps the
header, the L1/L2 entries (in hex, together with the flag bits) and the first refcounts of an
image, so that the exact on-disk encoding produced by a given `qemu-img` can be compared with what
MirrorBox writes.

Usage:
    python3 tools/qcow2_probe.py image.qcow2 [--l2-count N] [--refcounts N]
"""

import argparse
import struct


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("path")
    ap.add_argument("--l2-count", type=int, default=8)
    ap.add_argument("--refcounts", type=int, default=8)
    args = ap.parse_args()

    with open(args.path, "rb") as fh:
        data = fh.read()

    magic, version = struct.unpack_from(">II", data, 0)
    cluster_bits, = struct.unpack_from(">I", data, 20)
    size, = struct.unpack_from(">Q", data, 24)
    l1_size, l1_off = struct.unpack_from(">IQ", data, 36)
    rct_off, rct_clusters, nbsnap, snap_off = struct.unpack_from(">QIIQ", data, 48)
    incompat = struct.unpack_from(">Q", data, 72)[0] if version >= 3 else 0
    refcount_order = struct.unpack_from(">I", data, 96)[0] if version >= 3 else 4

    print(f"magic          = {magic:#x}")
    print(f"version        = {version}")
    print(f"cluster_bits   = {cluster_bits} (cluster = {1 << cluster_bits} bytes)")
    print(f"virtual_size   = {size}")
    print(f"l1_size/offset = {l1_size} / {l1_off:#x}")
    print(f"refcount table = {rct_off:#x} ({rct_clusters} clusters), order={refcount_order}")
    print(f"snapshots      = {nbsnap} @ {snap_off:#x}")
    print(f"incompat       = {incompat:#018x}")

    offset_mask = 0x00FFFFFFFFFFFE00
    print("\nL1 / L2 entries:")
    for i in range(l1_size):
        entry, = struct.unpack_from(">Q", data, l1_off + i * 8)
        if entry == 0:
            continue
        l2_off = entry & offset_mask
        print(f"  L1[{i}] = {entry:#018x}  copied_bit0={entry & 1} off={l2_off:#x}")
        for j in range(args.l2_count):
            value, = struct.unpack_from(">Q", data, l2_off + j * 8)
            if value == 0:
                continue
            if value & (1 << 62):
                kind = "compressed"
            elif value == 1:
                kind = "zero cluster"
            else:
                kind = (f"standard off={value & offset_mask:#x} "
                        f"bit0={value & 1} bit1={(value >> 1) & 1} bit62={(value >> 62) & 1} "
                        f"bit63={(value >> 63) & 1}")
            print(f"    L2[{j}] = {value:#018x}  {kind}")

    refcount_size = 1 << (refcount_order - 3)
    entries_per_block = (1 << cluster_bits) * 8 // (1 << refcount_order)
    print(f"\nrefcount entries: {refcount_size} byte(s), {entries_per_block} per block")
    for block_index in range(min(rct_clusters * ((1 << cluster_bits) // 8), 4)):
        block_off, = struct.unpack_from(">Q", data, rct_off + block_index * 8)
        if block_off == 0:
            continue
        print(f"  refcount_block[{block_index}] @ {block_off:#x}")
        values = []
        for c in range(args.refcounts):
            if refcount_size == 2:
                rc, = struct.unpack_from(">H", data, block_off + c * 2)
            else:
                rc, = struct.unpack_from(">I", data, block_off + c * 4)
            values.append(f"c{c}={rc}")
        print("    " + "  ".join(values))


if __name__ == "__main__":
    main()

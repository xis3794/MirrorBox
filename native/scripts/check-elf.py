#!/usr/bin/env python3
"""Verifies that the ELF files we package can actually be mapped by the Android linker.

Why this exists
---------------
A patchelf rewrite went wrong once and produced libraries that still "looked" fine to every
packaging check while being unusable on device:

    WARNING: linker: ".../libzstd.so" unused DT entry: unknown (type0x5858585858585858 ...)
    CANNOT LINK EXECUTABLE ".../libqemu-img.so": empty/missing DT_HASH/DT_GNU_HASH in
        ".../libzstd.so" (new hash type from the future?)

patchelf 0.14 appended a new loadable segment whose p_vaddr and p_offset were *not* congruent
modulo p_align (it used a 64 KB alignment for a 16 KB aligned library). ELF requires

    p_vaddr % p_align == p_offset % p_align

and bionic derives its mapping from page_start(p_vaddr) / page_start(p_offset). With the two out
of step it maps the wrong file range, so the dynamic section it parses is filler bytes (patchelf
writes 0x58, 'X') instead of DT_* entries.

Usage: check-elf.py FILE [FILE ...]     (exit status 0 = all good)
"""

import struct
import sys

ELF_MAGIC = b"\x7fELF"


def check(path):
    """Returns a list of human readable problems (empty when the file is fine)."""
    problems = []
    try:
        with open(path, "rb") as handle:
            data = handle.read()
    except OSError as exc:
        return ["cannot read: %s" % exc]

    if data[:4] != ELF_MAGIC:
        return ["not an ELF file"]
    if data[4] != 2:
        return ["expected a 64-bit ELF"]
    if data[5] != 1:
        return ["expected a little endian ELF"]

    e_phoff, = struct.unpack_from("<Q", data, 0x20)
    e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x36)

    loads = 0
    dynamic = None
    for index in range(e_phnum):
        offset = e_phoff + index * e_phentsize
        if offset + e_phentsize > len(data):
            problems.append("program header table is truncated")
            break
        p_type, = struct.unpack_from("<I", data, offset)
        p_offset, p_vaddr = struct.unpack_from("<QQ", data, offset + 8)
        p_filesz, p_memsz, p_align = struct.unpack_from("<QQQ", data, offset + 32)

        if p_type == 1:  # PT_LOAD
            loads += 1
            if p_filesz == 0 and p_memsz == 0:
                problems.append("LOAD #%d is empty (p_filesz = p_memsz = 0)" % index)
            if p_align > 1 and (p_vaddr - p_offset) % p_align != 0:
                problems.append(
                    "LOAD #%d is not congruent: p_vaddr=0x%x p_offset=0x%x p_align=0x%x"
                    % (index, p_vaddr, p_offset, p_align)
                )
        elif p_type == 2:  # PT_DYNAMIC
            dynamic = (p_offset, p_filesz)

    if loads == 0:
        problems.append("no PT_LOAD segment")

    if dynamic is None:
        problems.append("no PT_DYNAMIC segment")
        return problems

    # The dynamic array must be readable and properly terminated, otherwise the linker walks off
    # the end of it.
    dyn_offset, dyn_size = dynamic
    if dyn_size == 0 or dyn_size % 16 != 0:
        problems.append("PT_DYNAMIC size 0x%x is not a multiple of 16" % dyn_size)
    terminated = False
    needed = []
    strtab = None
    strsz = None
    for i in range(dyn_size // 16):
        start = dyn_offset + i * 16
        if start + 16 > len(data):
            problems.append("PT_DYNAMIC extends past end of file")
            break
        tag, value = struct.unpack_from("<qQ", data, start)
        if tag == 0:
            terminated = True
            break
        if tag == 1:
            needed.append(value)
        elif tag == 5:
            strtab = value
        elif tag == 10:
            strsz = value
    if not terminated:
        problems.append("PT_DYNAMIC has no DT_NULL terminator")
    if needed and (strtab is None or strsz is None):
        problems.append("DT_NEEDED present but DT_STRTAB/DT_STRSZ missing")

    return problems


def main(argv):
    args = [a for a in argv[1:] if a != "-q"]
    quiet = len(args) != len(argv[1:])
    if not args:
        print(__doc__.rstrip())
        return 2
    failed = 0
    checked = 0
    for path in args:
        checked += 1
        problems = check(path)
        name = path.rsplit("/", 1)[-1]
        if problems:
            failed += 1
            for problem in problems:
                print("  %s: %s" % (name, problem))
        elif not quiet:
            print("  %s: ok" % name)
    if quiet and not failed:
        print("  %d ELF files are loadable" % checked)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

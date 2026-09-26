#!/usr/bin/env bash
# Builds the BIOS boot blobs that MirrorBox embeds in the APK (assets/grub/*) and **verifies them
# by actually booting a disk image in QEMU/SeaBIOS**. Run on an x86_64 host (the GitHub runner).
#
# What it produces in $OUT/bios:
#   boot.img   - GRUB stage1 (MBR code, 446 usable bytes; partition table/0x55AA untouched by us)
#   core.img   - GRUB stage2 with the needed modules embedded (biosdisk/part_msdos/fat/ext2/...)
#   grub.cfg   - the config we drop into /boot/grub/grub.cfg of the boot partition
#   layout.json- where the app must write core.img and how many bytes of boot.img to copy
# and $OUT/qemu.log - the serial console of a real boot, used as proof.
#
# Why this exists: syslinux's mbr.bin only chainloads the *active partition's boot sector* — with an
# empty partition the BIOS reports "no bootable device". GRUB's core.img is what actually makes a
# freshly created disk bootable, so we ship it instead of pretending MBR code is enough.
set -euo pipefail

OUT=${1:-out}
GRUB_DIR=/usr/lib/grub/i386-pc
BIOS_DIR="${OUT}/bios"
WORK="${OUT}/work"
rm -rf "${BIOS_DIR}" "${WORK}"
mkdir -p "${BIOS_DIR}" "${WORK}"

# MirrorBox 的磁盘布局：MBR，第一个分区从 LBA 2048 开始 → LBA 1..2047（≈1023 KiB）留给 core.img
PART_START_LBA=2048
PART_SECTORS=129024   # 63 MiB 分区，够放 grub.cfg（真实镜像里由用户决定）
DISK_BYTES=$(( (PART_START_LBA + PART_SECTORS) * 512 ))
CORE_LBA=1

echo "== 1) build core.img =="
# 早期配置：在任意分区上找 /boot/grub/grub.cfg，这样引导分区不一定是第一个。
cat > "${WORK}/early.cfg" <<'CFG'
search --file --no-floppy --set=root /boot/grub/grub.cfg
if [ -n "$root" ]; then
  set prefix=($root)/boot/grub
fi
CFG
grub-mkimage -O i386-pc -o "${BIOS_DIR}/core.img" \
  -p "(hd0,msdos1)/boot/grub" \
  -c "${WORK}/early.cfg" \
  biosdisk part_msdos fat ext2 normal ls cat echo test sleep chain \
  search search_fs_file search_label configfile linux serial terminal
ls -l "${BIOS_DIR}/core.img"

echo "== 2) stage1 (boot.img) =="
cp "${GRUB_DIR}/boot.img" "${BIOS_DIR}/boot.img"
ls -l "${BIOS_DIR}/boot.img"

echo "== 3) build a disk that mirrors the app's layout =="
DISK="${WORK}/disk.img"
dd if=/dev/zero of="${DISK}" bs=512 count=$(( DISK_BYTES / 512 )) status=none
sfdisk "${DISK}" >/dev/null <<EOF
label: dos
unit: sectors

start=${PART_START_LBA}, size=${PART_SECTORS}, type=0c, bootable
EOF
PART_OFF=$(( PART_START_LBA * 512 ))

echo "== 4) write the blobs exactly like the app does =="
python3 - "$DISK" "${BIOS_DIR}/boot.img" <<'PY'
import pathlib, sys
disk_path, boot_path = sys.argv[1], sys.argv[2]
disk = bytearray(pathlib.Path(disk_path).read_bytes())
boot = pathlib.Path(boot_path).read_bytes()
disk[0:446] = boot[0:446]          # MBR code 区（446 字节），分区表原样保留
disk[510:512] = b"\x55\xaa"
pathlib.Path(disk_path).write_bytes(bytes(disk))
PY
dd if="${BIOS_DIR}/core.img" of="${DISK}" bs=512 seek="${CORE_LBA}" conv=notrunc status=none

echo "== 5) put /boot/grub/grub.cfg into the FAT partition =="
mformat -i "${DISK}@@${PART_OFF}" -F -v MIRRORBOX ::
mmd -i "${DISK}@@${PART_OFF}" ::/boot ::/boot/grub
cat > "${BIOS_DIR}/grub.cfg" <<'CFG'
# MirrorBox 生成的 GRUB 配置：BIOS 引导后 5 秒内可选择。
set timeout=5
set default=0

serial --unit=0 --speed=115200
terminal_output console serial

menuentry "MirrorBox: 从硬盘引导（chainloader /bootmgr）" {
  insmod chain
  chainloader /bootmgr
}

menuentry "MirrorBox: Linux 内核（/vmlinuz + /initrd.img）" {
  linux /vmlinuz
  initrd /initrd.img
}

menuentry "GRUB 命令行" {
  echo "输入 ls 查看分区，例如: ls (hd0,msdos1)/"
}
CFG
mcopy -i "${DISK}@@${PART_OFF}" "${BIOS_DIR}/grub.cfg" ::/boot/grub/grub.cfg

echo "== 6) 在 QEMU/SeaBIOS 里真实启动一次（串口输出作为证据） =="
cat > "${BIOS_DIR}/grub.cfg" <<'CFG'
serial --unit=0 --speed=115200
terminal_output console serial
echo "MIRRORBOX-GRUB-BOOT-OK"
sleep 20
CFG
mcopy -o -i "${DISK}@@${PART_OFF}" "${BIOS_DIR}/grub.cfg" ::/boot/grub/grub.cfg

set +e
timeout 90 qemu-system-x86_64 -m 256 -drive "file=${DISK},format=raw,if=ide" \
  -display none -serial stdio -no-reboot > "${OUT}/qemu.log" 2>&1
set -e
if ! grep -q 'MIRRORBOX-GRUB-BOOT-OK' "${OUT}/qemu.log"; then
  echo "!! 启动验证失败，QEMU 输出如下："
  tail -40 "${OUT}/qemu.log"
  exit 1
fi
echo "== 启动验证通过：GRUB 已在 SeaBIOS 下启动并读到 /boot/grub/grub.cfg =="

echo "== 7) 产出带真实菜单的 grub.cfg 与 layout.json =="
cat > "${BIOS_DIR}/grub.cfg" <<'CFG'
# MirrorBox: /boot/grub/grub.cfg（BIOS 引导菜单）
set timeout=5
set default=0

menuentry "从硬盘引导（Windows bootmgr）" {
  insmod chain
  chainloader /bootmgr
}

menuentry "Linux 内核（/vmlinuz + /initrd.img）" {
  linux /vmlinuz
  initrd /initrd.img
}

menuentry "GRUB 命令行" {
  echo "输入 ls 查看分区，例如: ls (hd0,msdos1)/"
}
CFG
cat > "${BIOS_DIR}/layout.json" <<EOF
{
  "core_lba": ${CORE_LBA},
  "mbr_code_bytes": 446,
  "grub_prefix": "(hd0,msdos1)/boot/grub",
  "min_partition_start_lba": ${PART_START_LBA},
  "core_size": $(stat -c%s "${BIOS_DIR}/core.img"),
  "boot_img_size": $(stat -c%s "${BIOS_DIR}/boot.img")
}
EOF
cat > "${BIOS_DIR}/README.txt" <<'TXT'
MirrorBox 内置 BIOS 引导块（GRUB2, i386-pc）
==========================================

来源与许可
  grub-mkimage / boot.img 来自 Ubuntu 的 grub-pc-bin、grub-common（GRUB 2.12，GPL-3.0-or-later）。
  core.img 由 CI 用上面这些模块现场生成（不含任何发行版私有内容）。
  这些文件是 x86 机器码/数据，与 Android 无关，仅由应用按字节写入磁盘镜像。

布局（与应用写入逻辑一致）
  LBA 0         : boot.img 的 0..445 字节写入 MBR 代码区（446 字节），分区表与 0x55AA 保持不动
  LBA 1 起      : core.img（约 100~300 KiB），必须落在第一个分区之前（我们的布局首个分区在 LBA 2048）
  分区内        : /boot/grub/grub.cfg（本目录的 grub.cfg 模板）
TXT
ls -l "${BIOS_DIR}" "${OUT}/qemu.log"

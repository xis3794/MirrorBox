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

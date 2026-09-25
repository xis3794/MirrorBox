# 镜像匣 MirrorBox

> **不启动虚拟机**，直接在手机上预览、编辑、生成 qcow2 磁盘镜像。
> qcow2 ↔ vmdk / vhdx / vdi / raw 互转，ISO 制作与提取，全部离线、无需 root。

[![CI](https://github.com/xis3794/MirrorBox/actions/workflows/ci.yml/badge.svg)](https://github.com/xis3794/MirrorBox/actions/workflows/ci.yml)
[![Native Toolchain](https://github.com/xis3794/MirrorBox/actions/workflows/native.yml/badge.svg)](https://github.com/xis3794/MirrorBox/actions/workflows/native.yml)
[![Release](https://github.com/xis3794/MirrorBox/actions/workflows/release.yml/badge.svg)](https://github.com/xis3794/MirrorBox/actions/workflows/release.yml)

---

## 这是什么

镜像匣是一台**口袋里的镜像工作站**：它把 QEMU 的 `qemu-img`、`e2fsprogs`、`mtools`、`ntfsprogs`、
`xorriso` 全部交叉编译进 APK，再用一套**自研的纯 Kotlin qcow2 引擎**补齐“没有 qemu-img 也能干活”的能力。

不需要 root，不需要 Termux，不需要联网，也不需要启动任何虚拟机。

### 核心能力

| 能力 | 说明 |
|---|---|
| **预览** | qcow2 结构可视化：簇分配热图、L1/L2 表、快照时间线、分区图、十六进制视图（全部由内置引擎直接解析） |
| **编辑（双轨）** | ① **直写**：引擎按簇写时复制，直接改扇区/回写修改，快且省空间；② **安全模式**：提取分区 → e2fsprogs / mtools / ntfsprogs 编辑 → 只回写变化的簇 → `qemu-img check` 校验 |
| **生成** | 空白 qcow2（簇大小 / 预分配可选）、带分区与文件系统的磁盘（ext4 / FAT32 / NTFS，MBR 或 GPT） |
| **转换** | qcow2 ↔ vmdk（monolithicSparse / streamOptimized / 2GB 分卷）、vhdx、vdi、raw、qcow、vpc、qed；支持 `-c` 压缩瘦身 |
| **ISO 工作室** | 从文件夹制作 ISO（Joliet / RockRidge / El Torito BIOS / EFI 引导）、提取、编辑 |
| **其他** | 一致性检查与修复、快照创建/回滚/删除、调整容量、导出 raw、任务中心（实时进度 + 日志） |

### 设计要点

- **零 VM**：所有操作都是文件级/扇区级运算，不启动 QEMU 系统模拟，功耗与内存占用极低。
- **零网络**：APK 不申请 `INTERNET` 权限，工具链全部内置，数据不出设备。
- **双引擎互证**：内置引擎写出的每个镜像都会被 `qemu-img check` 独立校验；CI 中还有
  “qemu-img 生成的镜像让引擎读” 与 “引擎生成的镜像让 qemu-img 校验” 的双向对拍。
- **UI**：Jetpack Compose + Material 3，液态玻璃组件、悬浮胶囊底栏、深浅色与性能档位。

## 安装

1. 打开 [Releases](https://github.com/xis3794/MirrorBox/releases)
2. 下载 `MirrorBox-*-arm64-v8a.apk`（模拟器选 `x86_64`，或使用 `universal`）
3. 安装后首次进入「设置 → 工具自检」确认原生工具链就绪

> 应用未上架任何商店：可选的「所有文件访问」权限用于**原位**操作 `/sdcard` 上的大镜像，
> 不授权也能用（走 SAF 导入导出）。

## 从源码构建

```bash
git clone https://github.com/xis3794/MirrorBox.git
cd MirrorBox

# 1) 本地构建 APK（不含原生工具链，界面与内置引擎完整可用）
./gradlew :app:assembleDebug

# 2) 构建完整版（含 qemu-img 等工具）需要 NDK，见 native/README.md
export ANDROID_NDK_ROOT=/path/to/android-ndk
bash native/scripts/build-all.sh      # 产物注入 app/src/main/jniLibs/<abi>/
./gradlew :app:assembleRelease
```

引擎单测与 qemu-img 对拍：

```bash
./gradlew :qcow2:test
./gradlew :qcow2:qcow2Cli --args="info my.qcow2"
```

## 仓库结构

```
MirrorBox/
├─ app/                    Android 应用（Compose UI、任务中心、工具执行层）
├─ qcow2/                  纯 Kotlin qcow2 引擎（可 JVM 单测，含 CLI 自测工具）
├─ native/                 原生工具链构建脚本与版本清单
├─ .github/workflows/      CI / 原生构建 / 发布三条流水线
├─ keys/                   固定签名密钥（保证覆盖安装）
└─ docs/                   构建与架构说明
```

详见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 与 [native/README.md](native/README.md)。

## 与 Limbo 的关系

镜像匣生成/转换出来的 qcow2 可以直接丢给 [Limbo](https://github.com/xis3794/limbo) 之类的
QEMU 前端启动；两者的 Android/bionic 构建经验（NDK 配置、glib 移植、`patchelf` 符号与 SONAME
修正、华为 linker 兼容）是互相复用的。

## 许可证

GPL-3.0。内置的 QEMU、xorriso、e2fsprogs、mtools、ntfs-3g 等均为各自上游许可证，
构建脚本与补丁全部公开在本仓库，可复现完整产物。

---

## English (short)

MirrorBox is an offline Android disk-image toolbox: inspect, edit and create qcow2 images
**without booting a VM**, convert between qcow2 / vmdk / vhdx / vdi / raw, and author ISOs.
It ships a cross-compiled native toolchain (qemu-img, e2fsprogs, mtools, ntfsprogs, xorriso)
plus a self-written pure-Kotlin qcow2 engine with copy-on-write safe writes.
No root, no network permission, no Termux.

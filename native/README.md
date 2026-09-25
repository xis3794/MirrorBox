# 原生工具链（native/）

镜像匣把整套镜像处理工具**离线打包进 APK**。所有二进制都在 GitHub Actions 上用 Android NDK
交叉编译，然后以 `jniLibs/lib*.so` 的形式随 APK 分发，运行时从 `nativeLibraryDir` 直接执行
（现代 targetSdk 下应用数据目录不可执行，`nativeLibraryDir` 是唯一标准可执行位置）。

## 组成

| 工具 | 来源 | 用途 |
|---|---|---|
| `libqemu-img.so` | QEMU（`--enable-tools --disable-system --disable-user`） | 创建 / 转换 / 检查 / 信息 / 映射 / 快照 / 调整大小 / dd |
| `libqemu-io.so` | 同上 | 扇区级读写与调试 |
| `libmke2fs.so` `libe2fsck.so` `libdebugfs.so` `libdumpe2fs.so` `libresize2fs.so` `libtune2fs.so` | e2fsprogs | ext2/3/4 创建、检查、浏览、写入 |
| `libmkfs-fat.so` `libfsck-fat.so` | dosfstools | FAT16/32 格式化与检查 |
| `libmcopy.so` `libmdir.so` `libmdel.so` `libmmd.so` `libmren.so` | mtools | FAT 镜像内文件读写（支持 `image@@offset` 分区偏移） |
| `libmkntfs.so` `libntfsls.so` `libntfscat.so` `libntfscp.so` `libntfsfix.so` `libntfsinfo.so` | ntfsprogs（ntfs-3g 发行版） | NTFS 创建、浏览、读写文件 |
| `libxorriso.so` | GNU xorriso | ISO 制作 / 提取 / 编辑（El Torito BIOS/EFI） |
| `libglib-2.0.so` `libgobject-2.0.so` `libgio-2.0.so` `libgmodule-2.0.so` `libz.so` `libzstd.so` | 依赖 | qemu-img 运行期依赖 |

## 关键约束（踩坑记录）

1. **文件名必须形如 `lib*.so`，不能带版本后缀**：Android 打包与解压只认 `lib*.so`。
   因此 `normalize_so()` 会把 `libfoo.so.1` 重写 SONAME 为 `libfoo.so`，并用
   `patchelf --replace-needed` 修正所有依赖方的 `DT_NEEDED`。
2. **RPATH 必须是 `$ORIGIN`**：工具从 `nativeLibraryDir` 执行，同一目录即是依赖目录。
3. **华为设备的 linker**：历史上遇到过 glib 里 glibc 风格版本化符号
   （`_rwlock_*`）导致 `dlopen` 失败，脚本统一执行 `patchelf --replace-symbol`
   把它们改名为 bionic 的标准名。
4. **bionic 缺失函数**：QEMU 用到了 `malloc_trim` 等 glibc 专属接口，构建前用脚本打补丁
   （`build-qemu-img.sh` 内的 Python 片段），与 Limbo 的 QEMU 11 移植思路一致。
5. **簇/页对齐**：链接参数包含 `-Wl,-z,max-page-size=16384`，兼容 16K 页设备。

## 本地/CI 构建

```bash
export ANDROID_NDK_ROOT=/path/to/android-ndk
export ABI=arm64-v8a                # arm64-v8a | armeabi-v7a | x86_64

# 全量构建（依赖 + qemu-img + 文件系统工具 + xorriso）
bash native/scripts/build-all.sh

# 只重建其中一部分，便于排错
TARGETS=qemu   bash native/scripts/build-all.sh
TARGETS=deps   bash native/scripts/build-all.sh
TARGETS=fstools bash native/scripts/build-all.sh
TARGETS=xorriso bash native/scripts/build-all.sh
```

产物：

- `native/out/<abi>/tools/lib*.so` —— 打包内容
- `native/out/mirrorbox-tools-<abi>.tar.gz`（+ `.sha256`）—— Release 附件
- `app/src/main/jniLibs/<abi>/` —— APK 使用的暂存目录（已在 .gitignore 中）

## 上游版本

版本集中在 [`versions.env`](versions.env)，同时被 CI 缓存键引用。升级 QEMU 时优先保证
`qemu-img` 能构建，工具子集的依赖面远小于 system 模拟器；若新版受阻，可回退到
`QEMU_FALLBACK_VERSION`。

## 验证

`build-all.sh` 结束前会运行 `verify-package.sh`：

- 每个文件都必须是 ELF 且命名为 `lib*.so`
- 不允许残留 `lib*.so.*`
- 打印文件数量与总体积

APK 侧另有「设置 → 工具自检」页面，逐个执行 `--version` 并在真机上确认可用性。
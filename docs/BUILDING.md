# 构建指南

## 1. 只构建应用（最快，界面 + 内置引擎）

要求：JDK 17、Android SDK（platform 35、build-tools 35.0.0）。

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

这个 APK 不含 qemu-img 等原生工具，但以下功能仍然完整可用：

- qcow2 结构预览（簇热图 / L1-L2 / 快照 / 分区 / Hex）
- 用内置引擎创建镜像、扇区级直写、差分回写
- 任务中心与工具自检（会提示哪些工具缺失）

## 2. 构建完整版（含原生工具链）

需要 Android NDK（CI 使用 r26d）。见 [native/README.md](../native/README.md)。

```bash
export ANDROID_NDK_ROOT=/path/to/android-ndk
export ABI=arm64-v8a

bash native/scripts/build-all.sh          # 依赖 + qemu-img + 文件系统工具 + xorriso
./gradlew :app:assembleRelease            # 工具已在 jniLibs 中，会自动打进 APK
```

只要重建某一部分（CI 与本地排错都常用）：

```bash
TARGETS=deps    bash native/scripts/build-all.sh
TARGETS=qemu    bash native/scripts/build-all.sh
TARGETS=fstools bash native/scripts/build-all.sh
TARGETS=xorriso bash native/scripts/build-all.sh
```

## 3. 引擎自测与对拍

```bash
./gradlew :qcow2:test

# 读取 qemu-img 生成的镜像
qemu-img create -f qcow2 /tmp/ref.qcow2 64M
./gradlew :qcow2:qcow2Cli --args="info /tmp/ref.qcow2"
./gradlew :qcow2:qcow2Cli --args="verify /tmp/ref.qcow2"

# 让 qemu-img 校验引擎写出的镜像
./gradlew :qcow2:qcow2Cli --args="create /tmp/engine.qcow2 33554432"
./gradlew :qcow2:qcow2Cli --args="write /tmp/engine.qcow2 12345 200000"
qemu-img check /tmp/engine.qcow2
qemu-img convert -f qcow2 -O raw /tmp/engine.qcow2 /tmp/engine.raw
```

## 4. 发布

```bash
git tag v0.1.0 && git push origin v0.1.0
```

`release.yml` 会自动：编译 arm64-v8a 与 x86_64 原生工具 → 注入 jniLibs → 签名打包
（arm64 / x86_64 / universal 三个 APK）→ 创建 GitHub Release，附上 APK、工具链 tar.gz
与 SHA256。

## 5. 在无 Android SDK 的 ARM64 Linux（如 Operit 内置 Ubuntu）中构建

仓库自带的 `setup_android_env.sh` 会准备 JDK、SDK、Gradle，并替换为 ARM64 版 `aapt2`：

```bash
bash setup_android_env.sh
./gradlew :app:assembleDebug
```

## 常见问题

**Q：Debug 包里工具自检全是「缺失」？**
A：正常。原生工具由 `native.yml` 产出并注入 `app/src/main/jniLibs/<abi>/`（该目录不入库）。
本地想验证请先跑 `native/scripts/build-all.sh`。

**Q：Release 签名从哪里来？**
A：仓库内的 `keys/`；CI 也可用 `KEYSTORE_BASE64` 与 `KEYSTORE_PROPERTIES` 两个 Secret 覆盖。

**Q：为什么不直接内置 Termux / proot？**
A：镜像匣的定位是“不启动虚拟机、不启动用户态 Linux 发行版”的轻量工具，
所有能力都通过内置的独立可执行文件完成，功耗与体积都更友好。
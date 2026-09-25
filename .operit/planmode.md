# 镜像匣（MirrorBox）· 实施计划（定稿·精简版）

> Android 端「不启动虚拟机」的磁盘镜像工具箱：预览 / 编辑 / 生成 qcow2；qcow2 ↔ vmdk（vmlk）及 vhdx/vdi/raw/qcow 互转；ISO 制作 / 提取 / 编辑；Compose + Material3 + 液态玻璃 + 悬浮底栏；GitHub 公开仓库 `xis3794/MirrorBox`，Actions 编译原生二进制与签名 APK。

## 0. 已确认决策
- 仓库：公开 `xis3794/MirrorBox`（Actions 免费不限时）
- 编辑：双轨（内置 qcow2 读写引擎 + 安全模式差分回写）
- 存储：沙盒工作区 + SAF 导入导出 + 可选「所有文件访问」开关
- Token：仅建库/推送使用，不落盘不提交；完成后建议吊销

## 1. 验收标准（v0.1.0）
- CI 三线全绿：`ci.yml`（引擎单测+Debug APK）、`native.yml`（工具链）、`release.yml`（tag 签名出包）
- Release 资产：arm64-v8a 签名 APK（主）、可选 universal APK、各 ABI 工具包 tar.gz、SHA256SUMS
- 华为真机全链路：工具自检 → 创建 → 转换 → 预览 → 编辑（双轨） → ISO → 校验
- 写操作后自动 `qemu-img check`；文档齐全（README 中英 / BUILDING / ARCHITECTURE / CHANGELOG / GPL-3.0）

## 2. 架构与关键决策
- Gradle 模块：`:app`（UI+服务）＋ `:qcow2`（纯 Kotlin 引擎，JVM 可单测，UI 无关）
- 原生工具以 jniLibs 的 `lib*.so` 打包，从 `nativeLibraryDir` 直接执行（targetSdk 35 下应用数据目录不可 exec 的标准解法）；`extractNativeLibs=true`、RPATH=`$ORIGIN`、统一注入 HOME/TMPDIR/LD_LIBRARY_PATH
- 双通道互验：所有产物过 `qemu-img check` / `compare`；与 Limbo 衔接（生成的 qcow2 可直接给 Limbo 用，构建技巧沿用 Limbo 已验证方案）

## 3. 原生工具链（CI 构建，随 APK 分发）
- **qemu-img**（QEMU 11.1.0 tools 子集，备选 8.0.5）：创建 / 转换 / 信息 / 检查 / 映射 / 快照 / 调整大小 / dd / amend
- **e2fsprogs**（mke2fs / e2fsck / debugfs / dumpe2fs / resize2fs / tune2fs；`mke2fs -d` 可灌文件夹）
- **dosfstools**（mkfs.fat / fsck.fat）、**mtools**（mdir/mcopy/mmd/mdel，支持 `-i image@@offset` 分区偏移）
- **ntfsprogs**（mkntfs / ntfsls / ntfscat / ntfscp / ntfsfix，--disable-fuse）
- **xorriso**（ISO 制作/编辑/提取：Joliet、RockRidge、El Torito、EFI、isohybrid）
- 依赖链：glib / pcre2 / libffi / zlib / zstd；NDK r23b（Limbo 同款），沿用其 bionic/华为 linker 补丁（`_rwlock_*` 符号重命名、`.dynstr` 布局修正、patchelf）
- `native/scripts/` 脚本化（versions.env + 每工具脚本 + 打包 strip/tar.gz/sha256）；ABI：arm64-v8a（主）、armeabi-v7a、x86_64（可选）

## 4. qcow2 引擎（核心自研）
- 读：v2/v3、L1/L2（extended L2 探测）、refcount、内部快照、压缩簇（zlib 起步，zstd 二期）
- 写：簇分配 + L2/refcount 维护（共享簇复制写）+ 零簇优化 + 写后自检；写前可选快照/备份
- 对外：结构数据（热图/L1L2/快照时间线）、随机读写（hex/扇区编辑）、差分回写后端
- 测试：JVM 单测 + CI 与 qemu-img 对拍（apt qemu-utils 生成 fixtures，check/compare 断言）

## 5. 功能设计
- 预览：概览（header+info JSON）、簇分配热图、L1/L2 浏览、快照时间线、分区图（MBR/GPT）、客户机文件浏览（debugfs ls / mdir / ntfsls / xorriso -ls）、十六进制视图
- 编辑双轨：直写（hex、文件级差异回写，备份可选）＋ 安全模式（`qemu-img dd` 提取分区 → debugfs/mtools/ntfscp 编辑 → 差分回写 → check）；分区编辑器；快照（-c/-d/-a）；resize/amend/压缩瘦身
- 生成：空白 qcow2（簇大小/预分配/压缩）；带分区+文件系统的磁盘（ext4/FAT32/NTFS）；从文件夹生成 ISO
- 转换：qcow2 ↔ vmdk（monolithicSparse / streamOptimized / twoGbMaxExtentSparse）、vhdx、vdi、raw、qcow、vpc 等；进度解析、空间预检
- ISO 工作室：制作/提取/编辑、BIOS/EFI 引导、混合 MBR
- 任务中心（前台服务、实时日志、取消）、高级终端（加分项）

## 6. UI/UX
- 自研玻璃组件库（Haze 模糊，Android 12+ 原生 RenderEffect，低版本渐变+噪点降级；性能档开关）
- `FloatingGlassNavBar`：胶囊悬浮底栏 4 项（首页/镜像库/任务/设置）+ 中央创建按钮（新建 qcow2 / 转换 / ISO）；指示器弹簧动画、触觉反馈
- 页面：首页、镜像库、创建向导、转换台、预览检查器（7 子页签）、ISO 工作室、任务中心、设置；动效与品牌图标；深/浅色；首启引导

## 7. 存储与 I/O
- 沙盒工作区 + 外部专属目录（真实路径）；SAF 导入导出桥；可选「所有文件访问」原位操作 /sdcard 大文件
- 全流式 IO、磁盘空间预检、同文件操作互斥、操作历史可回滚

## 8. GitHub 与 CI/CD
- 建库（REST）+ git 推送 main；包名 `io.github.xis3794.mirrorbox`、应用名「镜像匣」、rootProject 改名；固定签名 `keys/mirrorbox.jks` 提交（保证覆盖安装）
- 三流水线：`ci.yml`（push/PR：单测+lint+Debug）、`native.yml`（矩阵构建工具链+缓存+sha256）、`release.yml`（tag：注入 jniLibs→assembleRelease 签名→Release 含 APK/工具包/SHA256/changelog）
- 版本：tag 驱动 versionName/versionCode

## 9. 里程碑
- **M0** 建库+骨架改名+`ci.yml` 跑绿（S）
- **M1** 工具链 CI：qemu-img 优先→真机 /data/local/tmp 实测→补齐其它工具→应用 ToolRunner+自检页（XL，最关键）
- **M2** 引擎只读 + 预览 UI（概念/热图/L1L2/快照/分区）（L）
- **M3** 核心操作+任务中心+镜像库+SAF；真机验收 vmdk↔qcow2 全链路（L）
- **M4** 编辑双轨+分区/FS 向导+客户机文件浏览器；写后 check 断言（XL）
- **M5** ISO 工作室（M）
- **M6** 打磨+v0.1.0 发布：玻璃全量、动效、i18n、文档、截图、≥2GB 压力测试（M）

## 10. 风险 / 测试 / 合规 / 交付
- 风险与对策：QEMU 交叉编译（Limbo 补丁复用+版本回退）；华为 linker（M1 优先验证）；exec 限制（nativeLibraryDir）；直写正确性（备份+check+对拍）；Token（用后吊销）；GPL（公开脚本补丁+NOTICE）
- 测试：JVM 单测、CI 对拍、真机清单、写后完整性断言
- 合规：GPL-3.0；应用默认零网络权限、全离线
- 交付：仓库（源码+3 workflow+native 脚本+引擎+文档）、APK（arm64 主+可选 universal）、工具包、截图
- 后续（v0.2+）：zstd 簇、backing 链、exFAT、文件浏览增强；明确不做：VM 启动、qcow2 加密新建

---
自 M0 起执行；M1 完成即产出第一个可安装、可跑 qcow2 转换的 APK。

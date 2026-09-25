# 架构说明

## 分层

```
Compose UI (Material 3 + 液态玻璃组件)
   │  StateFlow / 协程
   ▼
nav.Navigator ── 极简类型化导航（无第三方依赖）
   │
   ▼
core.*  ── 设备侧基础设施
   ├─ AppPaths      工作区目录（应用沙盒 / 外部专属目录 / 全文件访问模式）
   ├─ Prefs         轻量设置存储
   ├─ NativeTools   原生工具发现（nativeLibraryDir → 外部目录 → 沙盒 tools/）
   ├─ ToolRunner    ProcessBuilder 执行 + 行级输出泵（\n 与 \r 都切分，实时进度）
   ├─ TaskManager   任务状态机、日志落盘、进度解析、取消
   └─ OperationService 前台服务，长任务不被系统回收
   │
   ▼
ops.*  ── 业务操作
   ├─ ImageOps   qemu-img 封装：create / convert / check / resize / snapshot / amend / compress / 导出 raw
   ├─ IsoOps     xorriso 封装：mkisofs 制作、提取、编辑
   └─ EditOps    安全模式流水线 + 分区表 + 文件系统工具 + 十六进制编辑
   │
   ▼
qcow2/  ── 纯 Kotlin 引擎（无 Android 依赖，可 JVM 单测）
   ├─ Qcow2Image   解析 / 读 / 写（CoW）/ 快照 / 统计 / 簇状态图
   ├─ Qcow2Format  常量与模型
   ├─ disk/PartitionTable  MBR/GPT 解析与生成
   └─ tools/Qcow2Cli      CI 对拍用命令行入口
```

## 为什么要有自研引擎

`qemu-img` 能做的事情很多，但有三类场景它帮不上忙：

1. **结构可视化**：把 L1/L2、refcount、簇分配状态画成热图，需要直接解析元数据；
2. **无工具可用时**：Debug 构建、未注入 jniLibs、或用户禁用原生工具时，预览与直写仍然工作；
3. **扇区级编辑**：按虚拟地址读写、只回写变化的簇（差分回写），比整盘转换快几个数量级。

## 写入的正确性策略

引擎的写入规则只有一条核心不变式：

> **refcount == 1 表示该簇被当前活动镜像独占，可以原地修改；refcount > 1 表示共享（例如被内部快照引用），必须先复制再修改。**

这样快照安全性与写入性能同时成立，不需要额外的引用计数簿记。配套措施：

- 每次涉及元数据的写操作后，`TaskManager` 会自动排队一次 `qemu-img check`；
- CI 里做双向对拍（qemu-img ↔ 引擎），任何不一致都会让流水线失败；
- 安全模式（提取 → 工具编辑 → 差分回写）作为“最保险”的另一条路径，与会话内的直写互为备份。

## 工具执行模型

- 二进制以 `jniLibs/lib*.so` 分发 → 安装时被解压到 `nativeLibraryDir`；
- `targetSdk >= 29` 时应用数据目录不可执行，`nativeLibraryDir` 是唯一标准可执行位置；
- 环境变量固定为：`HOME`=沙盒、`TMPDIR`=cache、`LD_LIBRARY_PATH`=`nativeLibraryDir`，RPATH 为 `$ORIGIN`；
- 输出泵按 `\n`/`\r` 双分隔切行，`qemu-img -p` 的进度刷屏能被实时转成进度条；
- 任务日志写入 `files/mirrorbox/logs/task-<id>.log`，可在任务中心查看或复制。

## 存储模型

| 模式 | 路径 | 说明 |
|---|---|---|
| 沙盒工作区 | `files/mirrorbox/{images,iso,work}` | 默认，工具可直接读写 |
| 外部专属目录 | `Android/data/io.github.xis3794.mirrorbox/files/MirrorBox` | 用户可见、无需权限、真实路径 |
| SAF 导入导出 | 系统文件选择器 | 合规方式搬运大文件 |
| 全文件访问（可选） | `/sdcard/...` | 原位操作数 GB 镜像，避免复制 |

## CI 流水线

| 工作流 | 触发 | 内容 |
|---|---|---|
| `ci.yml` | push / PR | 引擎单测 + 与 qemu-img 双向对拍 + Debug APK |
| `native.yml` | 手动 / 被 release 调用 | NDK 交叉编译全套工具，产出 tar.gz 与 jniLibs |
| `release.yml` | tag `v*` | 原生工具 → 注入 jniLibs → 签名 APK → 发布 Release |

## 签名

`keys/mirrorbox.jks` 与 `keys/keystore.properties` 已提交到仓库（个人项目选择，
保证每次构建签名一致，用户可覆盖安装）。若你 fork 后发布，请替换为自己的密钥，
或在 CI 中改用 `KEYSTORE_BASE64` / `KEYSTORE_PROPERTIES` 两个 Secret 覆盖。
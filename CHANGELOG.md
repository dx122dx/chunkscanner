# 更新日志 / Changelog

本项目的所有重要变更都会记录在此文件中。

All notable changes to this project will be documented in this file.

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [1.1.0] - 2026-08-05

首个提供**对外公共 API** 的版本。自 1.0.0 以来累计 83 次提交，涉及 82 个源文件、约 +11400 / -3200 行变更。

### 新增 / Added

#### 公共 API（对外契约）

- **新增 `com.billy65536.chunkscanner.api` 包**，作为第三方模组接入 Chunk Scanner 的唯一稳定契约。同一主版本内保持向后兼容。
  - `ChunkScannerApi` —— 总入口门面，提供 `API_VERSION`（能力探测）、`id(path)`、`unknownId()`、`isReady()`
  - `DatabaseApi` —— 数据库目录、查询、加载、导出包与 GUI 打开
  - `NavigationApi` —— 导航实例创建、入队控制与到达条件注册
  - `RegistryApi` —— 分析器、数据库视图、存储引擎三注册器统一入口
- 详见 [`docs/API.md`](docs/API.md)。

#### 配置系统

- **配置体系全面改造为 Cloth Config AutoConfig**，支持嵌套结构与自动持久化。
- 新增 `/cs config get|set|reset` 命令，通过反射访问任意配置路径，带路径与值的自动补全。
- 新增 `ConfigReflectionAccessor` 反射访问核心，供命令与外部调试工具使用。
- 新增**服务端 opt-in 配置锁定机制**（`ConfigurationLocker`）：支持锁定配置项、强制指定值、授权解锁。锁定项在命令、Cloth GUI、配置文件三条通道下均不可绕过。

#### 导航与寻路

- **新增 Baritone 导航集成**：`/cs nav` 系列命令支持自动寻路至扫描结果坐标。
- 新增 Baritone 风险警告配置项与路径点回退机制（Baritone 不可用时降级为创建路径点）。
- **导航支持多实例**：`ChunkScannerNavigation.create(name)` 可创建独立导航实例，拥有各自的队列、模式、回调与路径点分组，与玩家的全局 `/cs nav` 队列互不干扰。
- 新增 `NavigationConditionRegistry` 到达条件注册表，内置 `chunkscanner:player_near`，支持第三方模组注册自定义到达判定。
- 新增 `NavigationTickDispatcher` 托管独立导航实例的 tick 驱动。

#### 数据库与导出

- **BinaryChunkDb 升级至 VERSION 4**：新增 CRC32 完整性校验与 TaskConfig 独立段（任务配置随数据库持久化，重启扫描时自动恢复）。
- 新增**数据库 ZIP 包导出**：导出内含 `metadata.json`（含 `databaseType`/`mainFile` 字段），导入方可通过 `FactoryRegistry` 创建实例，无需硬编码具体实现类。
- 数据库页面导出按钮拆分为「导出 TSV」与「导出数据库 ZIP」两项。
- 新增 CLI 导出命令。
- 新增子数据库机制，增强数据独立存储，避免触发主数据库重写。

#### QShop 分析器

- 新增**聊天增强数据提取系统**（`ChatItemExtractor` + `QShopChatListener`）：从聊天消息组件树提取商品注册名、附魔、NBT 哈希，回填至数据库。
- 新增增强匹配模式：`MANUAL`（配合 `/cs enhance commit` 手动提交）、`SemiAutomatic`、隔离模式。
- 新增 QShop 增强通知与**高亮边框渲染**（红/黄/绿三色表示交易信息时效性），高亮范围与渐变时长可配置。
- 新增 `ItemTranslator`：进入世界时构建译名 → 注册名映射表，用于恢复商品注册名。
- QShop 筛选器文本字段新增四种匹配模式：包含（默认）、排除、全字匹配、正则表达式。
- 新增「更新时间」列、Detail 列物品图标渲染与 JEI 风格完整 tooltip。
- 新增「空间不足」商店状态识别与配置驱动的正则解析。

#### GUI

- 新增任务配置编辑模式：在任务主页点击任务行可打开配置编辑页，支持运行时更新并持久化。
- 新增表格自动列宽适应。
- 路径点命名支持 `{key}` 占位符替换（如 `{Item}({Price})`）。
- 新增 `ErrorDisplayLayout` 结构化异常展示布局。
- 新增单元格级 tooltip 支持。

#### 命令

- 新增 `/csc` 别名（等价于 `/cs components`）。
- 命令参数支持双引号包裹。

#### 构建与测试

- 引入 JUnit Jupiter 5.10.2 + Mockito 5.10.0 + fabric-loader-junit 测试体系。
- 新增 GitHub Actions 构建与发布工作流。

### 变更 / Changed

- **cloth-config 由可选依赖变为必需依赖**（配置模型由 AutoConfig 驱动）。
- **内部组件标识符（analyzerId / viewId / factoryId）由 `String` 统一改为 `Identifier`**，命名空间 `chunkscanner`。旧版数据库文件的裸字符串 id 会自动兼容解析。
- 命令注册与执行逻辑集中迁移至 `ChunkScannerCommands`。
- 命令树结构重组。
- 数据库文件扩展名由 `.bin4` 改为 `.bin`。
- QShop 价格改为整数存储，消除浮点精度导致的筛选与排序偏差。
- 代码结构分层解耦：明确核心层（`chunkscanner.*`）与组件层（`chunkscanner.components.*`）的依赖方向，核心层不再反向依赖组件层。
- `DbViewProvider` 与 `BinaryChunkDb` 解耦，视图不再绑定具体存储实现。
- 单元格内容统一为 `CellContent` 密封接口；GUI 文本由 `String` 改为 `Text` 以保留样式。
- 接口命名统一为 `I` 前缀（`IChunkDb`、`IChunkAnalyzer`、`IDbViewProvider` 等）。
- QShop 二进制格式扩展至 56 字节（基础 48 + 增强 8）。
- 增强匹配采用即时密封处理模式替代批量排队。

### 修复 / Fixed

- 修复玩家可通过 Cloth Config GUI 绕过服务端配置锁定的问题（`validatePostLoad()` 钩子重放锁定值）。
- 修复 `/cs nav go` 无效的 Baritone 反射调用缺陷。
- 修复 QShop 增强数据在区块重访时被清除的问题。
- 修复 `buildEnhancedValue` 字节偏移错位导致增强数据完全失效的问题。
- 修复 QShop 扫描无结果的问题（价格解析对货币符号后缀的兼容）。
- 修复 QShop 聊天消息捕获的三层缺陷，新增 `SystemChatMixin` 拦截 Fabric API 无法覆盖的系统消息通道。
- 修复潜影盒商品的有效数量与单价显示。
- 修复连续点击 / 容器点击的商店错配问题。
- 修复浮点精度、缓存与常量不统一导致的价格筛选偏差。
- 修复数据库内页物品图标 tooltip 移开后不消失的问题。
- 修复 BMCLAPI 镜像路径与版本 JSON 下载失败。

### 废弃 / Deprecated

以下方法已标记 `@Deprecated`，本版本仍保留，**计划在 1.2.0 移除**：

- `ChunkScannerMod.startNavigation()` / `clearNavigation()` / `enqueueNavigation()` / `getNavQueue()` —— 请改用 `NavigationApi`
- `ChunkScanner` 中的 3 处兼容方法
- `ChunkScannerNavigation` 中的 1 处兼容方法

### 已知限制 / Known Limitations

- **服务端 opt-in 配置锁定为实验性功能**：锁定与强制值的执行链路已完整可用，但**服务端授权信号的网络接收层尚未实现**。当前 `ConfigurationLocker.setAuthorized()` / `setLocked()` 只能由外部模组或调试工具主动调用，服务端无法自动下发策略。网络协议将在后续版本设计。
- **Baritone 路径执行是全局唯一资源**：多个导航实例同时启动会互相抢占寻路目标。调用方需自行保证同一时刻仅有一个导航实例处于活动状态。
- Xaero 联动基于反射实现，基于 Xaero's Minimap 24.7.1 验证。其它版本可能因 API 变动而降级或失效。

---

## [1.0.0] - 2026-07-10

首个正式发布版本。

- 客户端渐进式区块扫描器，异步扫描已加载区块。
- 可扩展分析器体系，内置 Sign（告示牌）与 QShop（商店）两个分析器。
- 多任务并行扫描与自适应速率控制。
- 双轴 GUI 数据库浏览器，支持特化视图。
- 上下文感知的数据存储。
- Xaero 路径点集成。
- Cloth Config 配置界面支持。

[1.1.0]: https://github.com/dx122dx/chunkscanner/releases/tag/1.1.0
[1.0.0]: https://github.com/dx122dx/chunkscanner/releases/tag/1.0.0

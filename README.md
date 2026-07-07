# Chunk Scanner

**客户端渐进式区块扫描器** — 在 Minecraft 中异步扫描已加载区块，配合可扩展的分析器自动发现和记录感兴趣的内容。

[![License: GNU AGPL v3](https://img.shields.io/badge/License-GNU%20APGL%20v3-green.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-blue.svg)](https://minecraft.net/)
[![Fabric Loader](https://img.shields.io/badge/Fabric%20Loader-%E2%89%A50.14.24-orange.svg)](https://fabricmc.net/)

---

## 目录

- [简介](#简介)
- [特性](#特性)
- [安装](#安装)
- [快速开始](#快速开始)
- [命令参考](#命令参考)
- [内置分析器](#内置分析器)
- [数据库浏览器](#数据库浏览器)
- [GUI 界面](#gui-界面)
- [配置系统](#配置系统)
- [Xaero 路径点集成](#xaero-路径点集成)
- [构建](#构建)
- [项目结构](#项目结构)
- [许可证](#许可证)

---

## 简介

Chunk Scanner 是一个纯客户端的 Fabric 模组，能够在后台异步扫描玩家视野内的已加载区块。与传统的区块扫描器不同，它**不会强制加载新区块**，因此对服务器几乎无影响，也不会造成客户端卡顿。

核心设计理念是可扩展性：通过注册不同的**分析器 (Analyzer)**，可以对同一批区块进行多种类型的扫描，数据独立存储，互不干扰。内置了**告示牌扫描器**和 **QShop 商店扫描器**两个实用分析器。

所有扫描结果以自定义紧凑二进制格式持久化存储，并可通过内置的双轴滚动 GUI 数据库浏览器进行查看、筛选、排序和导出。

---

## 特性

- **异步渐进式扫描** — 在后台线程中处理区块，主线程无卡顿
- **自适应速率控制** — 根据每 tick 实际耗时动态调整扫描速度，平衡性能与速度
- **多任务并行** — 支持同时运行多个独立的扫描任务，互不干扰
- **可扩展分析器架构** — 通过 `ChunkAnalyzer` 接口轻松注册自定义分析器
- **紧凑二进制数据库** — 自定义 `.dat` 格式，字符串池压缩，原子写入
- **上下文感知存储** — 自动区分单机世界和多人服务器，数据按存档隔离
- **双轴滚动 GUI** — 数据库浏览器支持垂直 + 水平滚动，适配宽表数据
- **特化视图** — 不同分析器类型提供专属的数据解析视图（告示牌、QShop 交易面板）
- **QShop 筛选排序** — 支持按买卖模式、价格范围、数量范围筛选，多字段排序
- **Xaero 路径点集成** — 在数据库浏览器中点击坐标可直接创建 Xaero 路径点
- **Cloth Config + ModMenu** — 可选依赖，提供图形化配置界面
- **完整国际化** — 支持简体中文和英文

---

## 安装

### 前置要求

| 组件 | 版本 |
|------|------|
| Minecraft | 1.20.1 |
| Fabric Loader | ≥ 0.14.24 |
| Fabric API | 任意版本 |
| Java | ≥ 17 |

### 可选依赖

| 模组 | 用途 |
|------|------|
| [Cloth Config](https://modrinth.com/mod/cloth-config) | 图形化配置界面 |
| [Mod Menu](https://modrinth.com/mod/modmenu) | 模组菜单入口 |
| [Xaero's Minimap](https://modrinth.com/mod/xaeros-minimap) / [World Map](https://modrinth.com/mod/xaeros-world-map) | 路径点集成 |

### 安装步骤

1. 确保已安装 Fabric Loader 和 Fabric API
2. 下载 `chunkscanner-<version>.jar`
3. 放入 `.minecraft/mods/` 目录
4. 启动游戏，输入 `/cs help` 验证安装

---

## 快速开始

### 1. 扫描告示牌

```
/cs task begin sign
```

这会在后台自动扫描视野中的所有告示牌，结果保存到数据库。

### 2. 扫描 QShop 商店

```
/cs task begin qshop
```

扫描箱子/木桶上 QShop 格式的商店告示牌。

### 3. 查看扫描状态

```
/cs task status
```

### 4. 浏览扫描结果

```
/cs db gui
```

在 GUI 中选择数据库文件，选择对应视图查看详细数据。

### 5. 停止扫描

```
/cs task stop <id>
```

---

## 命令参考

所有命令支持 `/chunkscanner` 和 `/cs` 两个别名。大部分参数支持 Tab 自动补全。

### 任务管理

| 命令 | 说明 |
|------|------|
| `/cs task begin <分析器> [id] [配置...]` | 启动扫描任务 |
| `/cs task stop <id>` | 停止指定任务 |
| `/cs task pause <id>` | 暂停任务 |
| `/cs task resume <id>` | 恢复暂停的任务 |
| `/cs task stopall` | 停止全部任务 |
| `/cs task status` | 显示所有任务状态 |
| `/cs task list` | 列出所有可用分析器 |
| `/cs task gui` | 打开任务管理界面 |
| `/cs task reload [restart]` | 热重载配置 |
| `/cs help` | 显示帮助 |

### 数据库管理

| 命令 | 说明 |
|------|------|
| `/cs db gui` | 打开数据库浏览器 |
| `/cs db open [id]` | 直接打开指定数据库 |
| `/cs db delete <id>` | 删除数据库文件 |
| `/cs db reboot <id>` | 从已有数据库恢复扫描 |
| `/cs db list` | 列出所有数据库文件 |

### 启动参数

`/cs task begin` 可以附带任务级配置，格式为 `key=value`：

```
/cs task begin qshop myScan revisit=30 tasks=8 radius=2.0 wpName=商店 wpInit=¥ wpGroup=shops
```

支持的参数：

| 参数 | 类型 | 说明 | 默认值 |
|------|------|------|--------|
| `revisit` | int | 最小重访间隔（秒） | 60 |
| `tasks` | int | 每 tick 最大处理区块数 | 16 |
| `initTasks` | int | 初始每 tick 处理区块数 | 2 |
| `targetNs` | int | 目标每 tick 耗时（纳秒） | 5,000,000 |
| `flush` | int | 刷盘间隔（tick） | 100 |
| `threads` | int | 工作线程数 | 2 |
| `radius` | float | 扫描半径倍率 | 1.0 |
| `wpName` | string | Xaero 路径点名称 | 选中的坐标点 |
| `wpInit` | string | 路径点缩写 | 目标 |
| `wpGroup` | string | 路径点分组 | chunkscanner |

---

## 内置分析器

### Sign Analyzer（告示牌扫描器）

- 扫描区块内所有告示牌方块实体（普通告示牌 + 悬挂式告示牌）
- 同时记录正面和背面文字内容
- 重新扫描时自动清理已移除的告示牌记录

### QShop Analyzer（商店扫描器）

- 识别贴在容器（箱子、木桶等）上的 QShop 格式商店告示牌
- 解析四行标准格式：所有者、买卖模式+数量、商品名称、单价
- 支持三种交易状态：**出售**、**收购**、**缺货**
- 支持系统商店（数量为"无限"）
- 跨区块容器检测，确保数据准确

---

## 数据库浏览器

通过 `/cs db gui` 打开数据库浏览器，包含以下功能：

### 文件列表页

- 浏览所有已保存的数据库文件
- 显示文件名、分析器类型、文件大小
- 支持**恢复扫描**（从已有数据继续扫描）
- 支持**删除**数据库文件
- **打开文件夹**按钮一键打开存储目录

### KV 视图页

三种视图类型，可根据分析器类型切换：

| 视图 | 适用分析器 | 说明 |
|------|-----------|------|
| **Raw** | 全部 | 以十六进制格式展示原始键值对 |
| **Sign** | sign | 解析告示牌文本，显示位置和正反面内容 |
| **QShop** | qshop | 结构化表格：位置、所有者、类型、数量、商品、价格 |

### QShop 筛选系统

- **模式筛选**：全部 / 出售 / 收购
- **排序**：无 / 价格升序 / 价格降序 / 数量升序 / 数量降序
- **文本过滤**：维度、所有者、商品名（支持子串匹配）
- **范围过滤**：价格范围、数量范围

### 导出

- 支持通过 `另存为` 按钮将数据导出为 TSV 格式文件

---

## GUI 界面

### 任务管理界面 (`/cs task gui`)

- 顶部操作栏：数据库按钮、扫描 ID 输入、分析器选择、创建/高级按钮
- 活跃任务列表：每行显示分析器名、扫描 ID、彩色状态条、统计数据
- 状态条颜色编码：
  - 白色 = 待扫描
  - 深绿 = 已扫描无发现
  - 亮绿 = 已扫描有发现
  - 深蓝 = 超重访无发现
  - 亮蓝 = 超重访有发现
  - 红色 = 错误
  - 黄色 = 发现 + 错误
- 悬停 tooltip 显示详细统计信息
- 每个任务行有独立的暂停/继续和停止按钮

### 任务配置界面

通过 `高级...` 按钮打开，提供可滚动的配置区域，包含 10 个参数。所有字段有灰色 placeholder 显示全局默认值，留空即使用默认值。

---

## 配置系统

### 配置文件位置

- Cloth Config（优先）：通过 Mod Menu → Chunk Scanner 打开图形化配置界面
- JSON 回退：`.minecraft/config/chunkscanner.json`

### 全局配置结构

```json
{
  "defaults": {
    "minRevisitIntervalSec": 60,
    "maxTasksPerTick": 16,
    "initialTasksPerTick": 2,
    "targetTickNs": 5000000,
    "flushIntervalTicks": 100,
    "workerThreads": 2,
    "scanRadiusMultiplier": 1.0
  },
  "waypoint": {
    "name": "选中的坐标点",
    "initials": "目标",
    "group": "chunkscanner"
  }
}
```

### 配置加载策略

1. 优先尝试 Cloth Config API 加载
2. 若 Cloth Config 未安装，回退到 JSON 文件
3. 每个扫描任务可独立覆盖全局默认值（任务级配置）
4. 支持 `/cs task reload` 热重载，`restart` 子命令可完全重启所有活跃会话

---

## Xaero 路径点集成

当安装了 Xaero's Minimap 或 Xaero's World Map 后，在数据库浏览器中点击位置列即可创建路径点。

- 通过 Java 反射检测 Xaero 的可用性和 API 版本
- 自动兼容多版本 Xaero API
- 支持自定义路径点名称、缩写和分组
- 若 Xaero 不可用，回退到在聊天框中显示坐标

---

## 数据存储

### 存储路径

```
.minecraft/
└── chunkscanner/
    ├── local/              # 单机世界数据
    │   └── <世界名>/
    │       └── <scanId>.dat
    └── server/             # 多人服务器数据
        └── <服务器地址>/
            └── <scanId>.dat
```

### 数据库格式

自定义紧凑二进制格式 (`.dat`)：

```
Header → String Pool → Chunk Meta → KV Records
```

- **Header**：魔数、版本、分析器名、scanId
- **String Pool**：字符串池，通过 intern 机制压缩重复字符串
- **Chunk Meta**：区块元数据（最后扫描时间、状态标记）
- **KV Records**：键值对数据（分析器自定义格式）

写入采用原子操作：先写 `.tmp` 文件，完成后再 `ATOMIC_MOVE` 到 `.dat`。

---

## 自适应速率控制

Chunk Scanner 内置了智能速率调节机制：

1. 每 tick 检查实际处理耗时 vs 目标耗时
2. **加速条件**：实际耗时 < 目标/2 且满载 → 每 tick 处理区块数 +1
3. **减速条件**：实际耗时 > 目标×2 → 每 tick 处理区块数 -1
4. 区块按 `lastScanTime` 排序的优先队列调度，优先处理最久未扫描的区块
5. 每个区块有最小重访间隔（默认 60 秒），避免重复扫描

这种机制确保了扫描器在后台静默运行，不会影响游戏帧率。

---

## 构建

### 环境要求

- JDK 17+
- Gradle（使用项目自带的 Gradle Wrapper）

### 构建步骤

```bash
# 克隆仓库
git clone https://github.com/billy65536/chunkscanner.git
cd chunkscanner

# 构建
./gradlew build

# 产物位于 build/libs/chunkscanner-<version>.jar
```

### 开发

```bash
# 生成 IDE 项目文件
./gradlew genSources

# 运行 Minecraft 客户端
./gradlew runClient
```

---

## 项目结构

```
src/main/java/com/billy65536/chunkscanner/
├── ChunkScannerMod.java              # 主入口：初始化、命令注册、回调
├── config/
│   ├── ChunkScannerConfig.java        # 全局配置数据模型
│   ├── ConfigLoader.java              # 配置加载/保存
│   └── TaskConfig.java                # 任务级配置
├── core/
│   ├── ChunkScanner.java              # 扫描引擎核心
│   ├── ChunkAnalyzer.java             # 分析器接口
│   ├── ChunkDb.java                   # 通用数据库接口
│   ├── ScanSession.java               # 扫描会话状态
│   ├── AnalyzeResult.java             # 分析结果
│   ├── DbViewProvider.java            # 数据库视图提供者接口 + 注册表
│   ├── LocatedPosition.java           # 世界位置记录
│   └── CoreUtil.java                  # 工具方法
├── components/
│   ├── analyzer/
│   │   ├── SignAnalyzer.java          # 告示牌分析器
│   │   └── QShopAnalyzer.java         # QShop 商店分析器
│   ├── db/
│   │   ├── BinaryChunkDb.java         # 二进制数据库实现
│   │   └── DbFileUtil.java            # 数据库文件工具
│   └── view_provider/
│       ├── SignDbViewProvider.java    # 告示牌特化视图
│       ├── QShopDbViewProvider.java   # QShop 特化视图
│       └── QShopFilterScreen.java     # QShop 筛选界面
├── screen/
│   ├── ChunkScannerScreen.java        # 任务管理 GUI
│   ├── DatabaseScreen.java            # 数据库浏览器 GUI
│   └── TaskConfigScreen.java          # 任务配置 GUI
├── gui/
│   ├── GuiUtil.java                   # GUI 通用工具
│   ├── KvPageRenderer.java            # 数据库页面渲染
│   ├── PlaceholderTextField.java      # Placeholder 输入框
│   ├── ScrollManager.java             # 滚动管理器
│   ├── ScrollableListPanel.java       # 可滚动列表面板
│   └── ScrollbarUtil.java             # 滚动条渲染
└── integration/
    ├── ClothConfigIntegration.java     # Cloth Config 集成
    ├── ModMenuIntegration.java         # ModMenu 集成
    └── XaeroWaypointHelper.java       # Xaero 路径点集成
```

---

## 许可证

本项目基于 [GNU AGPL v3 License](LICENSE) 开源。
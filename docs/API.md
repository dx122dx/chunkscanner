# Chunk Scanner 公共 API 文档（v1）

> 本文档面向**第三方模组开发者**。Chunk Scanner 自 **1.1.0** 起对外提供稳定的公共 API，
> 同主版本号内保证向后兼容；次版本号变更可能新增方法，修订号变更只修 bug。
>
> **能力探测**：通过 `ChunkScannerApi.API_VERSION` 检查当前 API 版本号（当前为 `1`）。
> `ChunkScannerApi.isReady()` 可用于确认 Chunk Scanner 客户端入口已加载（游戏外返回 false）。

---

## 1. 总览

公共 API 全部位于 `com.billy65536.chunkscanner.api` 包，由四个全静态门面类组成：

| 类 | 作用 |
|------|------|
| `ChunkScannerApi` | 总入口：`API_VERSION`、`id(path)`、`unknownId()`、`isReady()` |
| `DatabaseApi` | 数据库目录、列表、元数据、加载、ZIP 导出包、GUI 打开 |
| `NavigationApi` | 导航实例创建、入队/控制、到达条件注册 |
| `RegistryApi` | 分析器 / 数据库视图 / 数据库工厂 三注册器统一入口 |

**约定的稳定子集**：
- `ChunkScannerMod` 的 `startNavigation`/`clearNavigation`/`enqueueNavigation`/`getNavQueue`
  已被 `@Deprecated`，**新代码必须走 `NavigationApi`**。
- `core/`、`gui/`、`screen/`、`integration/`、`security/` 等内部包**不在公共契约内**，可随时重构。
  `security.server_optin.ConfigurationLocker`（服务端 opt-in 锁定）自 1.1.0 起为实验性能力，
  **不属于本契约**，升级或对接前请直接与作者确认。

---

## 2. Identifier 命名约定

> **强制规则**：Yarn 1.20.1 映射下，`Identifier.of(ns, path)` 两参静态方法行为异常
> （会产生 `minecraft:` 前缀）。Chunk Scanner 扩展点 id 一律用 `new Identifier(ns, path)`，
> 或通过 `ChunkScannerApi.id(path)` 辅助。

```java
// ✅ 推荐
Identifier analyzerId = ChunkScannerApi.id("my_analyzer");        // → "chunkscanner:my_analyzer"
Identifier custom = new Identifier("mymod", "thing");              // 第三方命名空间

// ❌ 错误用法（会产生意外前缀）
Identifier broken = Identifier.of("mymod", "thing");
```

`Identifier.path` 必须匹配 `[a-z0-9/._-]`，**大写字母、汉字非法**。
解析旧 DB / metadata 中的裸字符串必须先做冒号检测：

```java
Identifier safe = raw.indexOf(':') >= 0
        ? Identifier.tryParse(raw)            // 带冒号 → tryParse
        : ChunkScannerApi.id(raw);             // 无冒号 → 回到 chunkscanner 命名空间
```

遇到空串或非法字符，请使用 `ChunkScannerApi.unknownId()` 哨兵
（内部为 `undefined:undefined`）。**不要**用 `id("")` 产生的 `chunkscanner:`，
那会绕过所有"未知 analyzerId"判空守卫。

---

## 3. DatabaseApi

### 3.1 路径

```java
Path root      = DatabaseApi.dbRoot();     // .minecraft/chunkscanner
Path dbDir     = DatabaseApi.dbDir();      // 存档隔离后的当前 DB 目录（local / server）
Path exportDir = DatabaseApi.exportDir();  // 导出包默认目录
```

### 3.2 查询与元数据

```java
List<String> allIds = DatabaseApi.listScanIds();
List<DbMetadata> list = DatabaseApi.listDatabases();                  // 按当前 analyzer
List<DbMetadata> byAnalyzer = DatabaseApi.listDatabasesByAnalyzer(
        ChunkScannerApi.id("qshop"));

DbMetadata meta  = DatabaseApi.getMeta(scanId);
boolean exists   = DatabaseApi.exists(scanId);
String filePath  = DatabaseApi.resolveFilePath(scanId);               // 调试用绝对路径
```

### 3.3 加载与删除

```java
try (IChunkDb db = DatabaseApi.openDatabase(scanId)) {                // 完整加载
    byte[] v = db.get(key);
}

try (IChunkDb db = DatabaseApi.openDatabase(scanId, true)) {          // 元数据 only，延迟加载
    db.open();
}

DatabaseApi.deleteDatabase(scanId);                                   // 不可恢复
```

### 3.4 导出包（ZIP 跨模组共享）

```java
// 导出（两种入口）
Path zip  = DatabaseApi.exportZip(db);                                 // 默认到 export/ 目录
Path zip2 = DatabaseApi.exportZip(db, customPath);                     // 自定义输出
Path tsv  = DatabaseApi.exportTsv(db);
Path tsv2 = DatabaseApi.exportTsv(scanId, path);                       // 按 scanId

// 导入
DbPackage pkg = DatabaseApi.openPackage(zipPath);                      // 打开（不加载）
DbValidationResult v = DatabaseApi.validatePackage(pkg);               // 校验完整性
if (v.valid()) {
    DatabaseApi.loadPackage(pkg, targetDir);                          // 解压到目标目录
}
```

ZIP 内含 `metadata.json`，记录 `databaseType` / `mainFile` / `sha256` 等字段。
**导入方必须通过 `IChunkDb.FactoryRegistry.get(identifier)` 取得对应工厂创建实例**，
不要硬编码 `BinaryChunkDb`（可通过 `RegistryApi.defaultDbFactory()` 取得当前默认工厂）。

### 3.5 GUI 打开

`openGui*` 内部通过 `client.send` 切回主线程，**任意线程均可调用**，不会在游戏外 / 加载前崩溃：

```java
DatabaseApi.openGui();                // 文件列表页
DatabaseApi.openGui(scanId);          // 直接打开指定库
DatabaseApi.createGui(scanId);        // 仅构建 Screen 实例（返回 null 若失败）
```

---

## 4. NavigationApi

### 4.1 实例创建

```java
ChunkScannerNavigation global = NavigationApi.global();                  // 全局实例（/cs nav 操作）
ChunkScannerNavigation qab    = NavigationApi.createNavigation("qab");   // 独立实例
```

- **全局实例**由 Chunk Scanner 内部每 tick 自动驱动，**禁止外部自行 `manageTick`**
  （`NavigationTickDispatcher.register(global)` 会返回 false，防止到达判定被执行两次）。
- **独立实例**默认不被 tick，调用方按需用 `manageTick(nav)` / `unmanageTick(nav)` 托管。
- **Baritone 路径执行是全局唯一资源**：多实例同时 `start()` 会互相抢占。
  架构级约束要求**同一时刻仅一个实例处于活动**，调用方负责互斥。

### 4.2 入队与控制

```java
qab.enqueue(waypoint, condition);                       // 单条
qab.enqueue(List.of(wp1, wp2));                         // 批量
qab.start();                                            // 开始执行
qab.stop();                                             // 停止（保留队列）
qab.clear();                                            // 清空队列
List<NavigationEntry> snapshot = qab.list();
```

### 4.3 到达条件注册

```java
NavigationApi.registerCondition(
        new Identifier("mymod", "near_my_block"),
        (x, y, z, dimensionId) -> NavigationConditionFactory.create(...));
```

Chunk Scanner 内置 `chunkscanner:player_near` **不可注销**；未注册的 id 在执行时
仅记录警告并回退到内置条件（不会抛异常）。

### 4.4 回退路径点组名（Xaero）

实例字段（**勿退回 static**）：
- `global` → `chunkscanner_nav`
- 独立实例 `name="qab"` → `chunkscanner_nav_qab`

---

## 5. RegistryApi

```java
// 1. 分析器 / 数据库视图 / 数据库工厂三注册器统一入口
RegistryApi.analyzers();        // AnalyzerRegistry
RegistryApi.dbViewProviders();  // DbViewProviderRegistry
RegistryApi.dbFactories();      // IChunkDb.FactoryRegistry

// 2. 视图提供者查询
List<IDbViewProvider> views = RegistryApi.viewProvidersFor(analyzerId);
```

### 5.1 视图提供者契约（关键）

`IDbViewProvider.applicableAnalyzers()` 返回的集合：
- **非空** → 仅对列出的 analyzer 适用
- **空集** → 视为**通用（universal）**，对所有 analyzer 适用

```java
boolean universal = provider.applicableAnalyzers().isEmpty();
```

`viewProvidersFor(analyzerId)` 合并「指定 analyzer 的特化视图」与「所有 universal 视图」，
并返回**不可变列表**（修改会抛 `UnsupportedOperationException`）。

### 5.2 注册第三方扩展点

外部模组注册分析器请使用**自己的命名空间**（`new Identifier("mymod", "...")`），
避免与 Chunk Scanner 内置 id 冲突；ViewProvider 的 `applicableAnalyzers()` 应列出你的 analyzer id
（若想让视图对所有 analyzer 可见，留空集合即可）。

---

## 6. 版本兼容与升级建议

- Chunk Scanner 主版本号（`X.y.z` 中的 `X`）变更时，公共 API 可能破坏性调整。
  对接方应在 `onInitialize` 中检查 `ChunkScannerApi.API_VERSION`，不符合预期时降级或拒绝加载。
- `DatabaseApi` 的 `metadata.json` 是稳定契约（自 1.0.0 起未变更格式字段名）。
- Chunk Scanner 主版本 1（API_VERSION=1）保证向后兼容至下一个主版本。

---

## 7. 参考实现

`chunkscanner-dbg`（`/home/billy/dev/chunkscanner-dbg`）是一个完整的第三方对接示例，
可作为外部模组接入 Chunk Scanner 的参考实现。
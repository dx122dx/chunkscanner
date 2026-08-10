package com.billy65536.chunkscanner.config;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.infrastructure.util.reflect.FlatConfigs;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * 任务级配置：每个扫描任务可独立设置参数。
 * 所有字段为 null 时表示使用 ChunkScannerConfig 中的默认值。
 *
 * <p>字段上的 {@link FlatConfigs.Key} 声明「解析别名」与「展示键名」：解析期按别名（大小写不敏感）
 * 匹配命令行输入，展示期按 display 输出驼峰键（{@code initTasks=} / {@code wpName=} 等）。
 * 解析 / 复制 / 合并 / 展示 / 空值判定 / 键枚举均由 {@link FlatConfigs} 反射驱动，
 * 新增字段只需加一个带注解的字段即可全链路生效。</p>
 *
 * <p>异构业务映射（本配置 → 全局 ChunkScannerConfig）刻意保留手写
 * {@link #applyTo(ChunkScannerConfig)}，不进通用工具类。</p>
 */
public class TaskConfig {

    private static final Gson GSON = new GsonBuilder().create();

    /** 最小重访间隔（秒）。null = 使用默认值。 */
    @FlatConfigs.Key("revisit")
    public Integer minRevisitIntervalSec;

    /** 每 tick 最大 chunk 数。null = 使用默认值。 */
    @FlatConfigs.Key("tasks")
    public Integer maxTasksPerTick;

    /** 初始每 tick chunk 数。null = 使用默认值。 */
    @FlatConfigs.Key("initTasks")
    public Integer initialTasksPerTick;

    /** 目标 tick 耗时（纳秒）。null = 使用默认值。 */
    @FlatConfigs.Key("targetNs")
    public Long targetTickNs;

    /** 批量刷写间隔（tick）。null = 使用默认值。 */
    @FlatConfigs.Key("flush")
    public Integer flushIntervalTicks;

    /** 工作线程数。null = 使用默认值。 */
    @FlatConfigs.Key("threads")
    public Integer workerThreads;

    /** 扫描视距倍率。null = 使用默认值。 */
    @FlatConfigs.Key("radius")
    public Double scanRadiusMultiplier;

    /** 路径点名称。null = 使用默认值。 */
    @FlatConfigs.Key("wpName")
    public String waypointName;

    /** 路径点缩写。null = 使用默认值。 */
    @FlatConfigs.Key("wpInit")
    public String waypointInitials;

    /** 路径点所属组（WaypointSet 名称）。null = 使用默认值。 */
    @FlatConfigs.Key("wpGroup")
    public String waypointGroup;

    /**
     * 命令层补全用的「已识别键名」集合（小写，{@link #parse} 实际接受的写法）。
     * 由 {@link FlatConfigs#keysOf} 反射生成，与解析器物理同源，杜绝键名漂移。
     */
    public static final java.util.List<String> KNOWN_KEYS = FlatConfigs.keysOf(TaskConfig.class);

    /** 创建一个空配置（所有值使用默认值）。 */
    public TaskConfig() {}

    /**
     * 从 key=value 字符串解析配置。
     * 支持的键：revisit, tasks, initTasks, targetNs, flush, threads, radius,
     *           wpName, wpInit, wpGroup
     * 示例：revisit=60 tasks=16 radius=1.5 wpName=商店
     */
    public static TaskConfig parse(String configStr) {
        return FlatConfigs.createFrom(configStr, TaskConfig.class);
    }

    /** 检查所有字段是否都是 null。 */
    public boolean isAllNull() {
        return FlatConfigs.isAllNull(this);
    }

    /**
     * 将此任务配置合并到全局配置，返回最终生效的配置值。
     * 此任务配置中为 null 的字段使用 defaults 中的值。
     *
     * <p><b>异构业务映射，刻意不走通用工具类</b>：这是「扁平 TaskConfig → 嵌套
     * ChunkScannerConfig」的唯一映射点，语义稳定且规模小，手写 14 行 {@code if}
     * 比注解反射更直观、更易追溯。{@link FlatConfigs} 只服务同构操作。
     *
     * <p>注意：{@code defaults.copy()} 是深拷贝，写入 result 不会污染全局配置。
     */
    public ChunkScannerConfig applyTo(ChunkScannerConfig defaults) {
        ChunkScannerConfig result = defaults.copy();
        if (minRevisitIntervalSec != null) result.scanner.minRevisitIntervalSec = minRevisitIntervalSec;
        if (maxTasksPerTick != null) result.scanner.maxTasksPerTick = maxTasksPerTick;
        if (initialTasksPerTick != null) result.scanner.initialTasksPerTick = initialTasksPerTick;
        if (targetTickNs != null) result.scanner.targetTickNs = targetTickNs;
        if (flushIntervalTicks != null) result.scanner.flushIntervalTicks = flushIntervalTicks;
        if (workerThreads != null) result.scanner.workerThreads = workerThreads;
        if (scanRadiusMultiplier != null) result.scanner.scanRadiusMultiplier = scanRadiusMultiplier;
        if (waypointName != null) result.integration.xaero.name = waypointName;
        if (waypointInitials != null) result.integration.xaero.initials = waypointInitials;
        if (waypointGroup != null) result.integration.xaero.group = waypointGroup;
        return result;
    }

    /** 生成配置说明字符串（紧凑单行，用于聊天消息）。 */
    public String toDisplayString() {
        return FlatConfigs.toString(this);
    }

    /** 序列化为 JSON 字符串。 */
    public String toJson() {
        JsonObject obj = new JsonObject();
        if (minRevisitIntervalSec != null) obj.addProperty("minRevisitIntervalSec", minRevisitIntervalSec);
        if (maxTasksPerTick != null) obj.addProperty("maxTasksPerTick", maxTasksPerTick);
        if (initialTasksPerTick != null) obj.addProperty("initialTasksPerTick", initialTasksPerTick);
        if (targetTickNs != null) obj.addProperty("targetTickNs", targetTickNs);
        if (flushIntervalTicks != null) obj.addProperty("flushIntervalTicks", flushIntervalTicks);
        if (workerThreads != null) obj.addProperty("workerThreads", workerThreads);
        if (scanRadiusMultiplier != null) obj.addProperty("scanRadiusMultiplier", scanRadiusMultiplier);
        if (waypointName != null) obj.addProperty("waypointName", waypointName);
        if (waypointInitials != null) obj.addProperty("waypointInitials", waypointInitials);
        if (waypointGroup != null) obj.addProperty("waypointGroup", waypointGroup);
        return GSON.toJson(obj);
    }

    /** 从 JSON 字符串反序列化。空或 "null" 返回 null。 */
    public static TaskConfig fromJson(String json) {
        if (json == null || json.isEmpty() || "null".equals(json)) {
            return null;
        }
        try {
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            if (obj == null || obj.size() == 0) {
                return null;
            }
            TaskConfig cfg = new TaskConfig();
            if (obj.has("minRevisitIntervalSec") && !obj.get("minRevisitIntervalSec").isJsonNull())
                cfg.minRevisitIntervalSec = obj.get("minRevisitIntervalSec").getAsInt();
            if (obj.has("maxTasksPerTick") && !obj.get("maxTasksPerTick").isJsonNull())
                cfg.maxTasksPerTick = obj.get("maxTasksPerTick").getAsInt();
            if (obj.has("initialTasksPerTick") && !obj.get("initialTasksPerTick").isJsonNull())
                cfg.initialTasksPerTick = obj.get("initialTasksPerTick").getAsInt();
            if (obj.has("targetTickNs") && !obj.get("targetTickNs").isJsonNull())
                cfg.targetTickNs = obj.get("targetTickNs").getAsLong();
            if (obj.has("flushIntervalTicks") && !obj.get("flushIntervalTicks").isJsonNull())
                cfg.flushIntervalTicks = obj.get("flushIntervalTicks").getAsInt();
            if (obj.has("workerThreads") && !obj.get("workerThreads").isJsonNull())
                cfg.workerThreads = obj.get("workerThreads").getAsInt();
            if (obj.has("scanRadiusMultiplier") && !obj.get("scanRadiusMultiplier").isJsonNull())
                cfg.scanRadiusMultiplier = obj.get("scanRadiusMultiplier").getAsDouble();
            if (obj.has("waypointName") && !obj.get("waypointName").isJsonNull())
                cfg.waypointName = obj.get("waypointName").getAsString();
            if (obj.has("waypointInitials") && !obj.get("waypointInitials").isJsonNull())
                cfg.waypointInitials = obj.get("waypointInitials").getAsString();
            if (obj.has("waypointGroup") && !obj.get("waypointGroup").isJsonNull())
                cfg.waypointGroup = obj.get("waypointGroup").getAsString();
            return cfg.isAllNull() ? null : cfg;
        } catch (Exception e) {
            ChunkScannerMod.LOGGER.warn("Failed to parse TaskConfig from JSON: {}", json);
            return null;
        }
    }

    /** 创建当前配置的副本。 */
    public TaskConfig copy() {
        return FlatConfigs.copy(this);
    }

    /**
     * 将 {@code delta} 中的非 null 字段合并到本配置，返回新实例（本实例不变）。
     * 用于 {@code /cs task modify}：仅覆盖指定的字段，保留其他已设置的字段。
     */
    public TaskConfig merge(TaskConfig delta) {
        return FlatConfigs.merge(this, delta);
    }
}

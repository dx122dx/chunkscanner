package com.billy65536.chunkscanner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * 任务级配置：每个扫描任务可独立设置参数。
 * 所有字段为 null 时表示使用 ChunkScannerConfig 中的默认值。
 */
public class TaskConfig {

    private static final Gson GSON = new GsonBuilder().create();

    /** 最小重访间隔（秒）。null = 使用默认值。 */
    public Integer minRevisitIntervalSec;

    /** 每 tick 最大 chunk 数。null = 使用默认值。 */
    public Integer maxTasksPerTick;

    /** 初始每 tick chunk 数。null = 使用默认值。 */
    public Integer initialTasksPerTick;

    /** 目标 tick 耗时（纳秒）。null = 使用默认值。 */
    public Long targetTickNs;

    /** 批量刷写间隔（tick）。null = 使用默认值。 */
    public Integer flushIntervalTicks;

    /** 工作线程数。null = 使用默认值。 */
    public Integer workerThreads;

    /** 扫描视距倍率。null = 使用默认值。 */
    public Double scanRadiusMultiplier;

    /** 创建一个空配置（所有值使用默认值）。 */
    public TaskConfig() {}

    /**
     * 从 key=value 字符串解析配置。
     * 支持的键：revisit, tasks, initTasks, targetNs, flush, threads, radius
     * 示例：revisit=60 tasks=16 radius=1.5
     */
    public static TaskConfig parse(String configStr) {
        if (configStr == null || configStr.isBlank()) {
            return null;
        }

        TaskConfig config = new TaskConfig();
        String[] parts = configStr.trim().split("\\s+");
        for (String part : parts) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) continue;

            String key = kv[0].toLowerCase();
            String value = kv[1];

            try {
                switch (key) {
                    case "revisit" -> config.minRevisitIntervalSec = Integer.parseInt(value);
                    case "tasks" -> config.maxTasksPerTick = Integer.parseInt(value);
                    case "inittasks" -> config.initialTasksPerTick = Integer.parseInt(value);
                    case "targetns" -> config.targetTickNs = Long.parseLong(value);
                    case "flush" -> config.flushIntervalTicks = Integer.parseInt(value);
                    case "threads" -> config.workerThreads = Integer.parseInt(value);
                    case "radius" -> config.scanRadiusMultiplier = Double.parseDouble(value);
                    default -> ChunkScannerMod.LOGGER.warn("Unknown task config key: {}", key);
                }
            } catch (NumberFormatException e) {
                ChunkScannerMod.LOGGER.warn("Invalid value for {}: {}", key, value);
            }
        }

        // 如果所有字段都是 null，返回 null 表示无需配置
        if (config.isAllNull()) {
            return null;
        }
        return config;
    }

    /** 检查所有字段是否都是 null。 */
    public boolean isAllNull() {
        return minRevisitIntervalSec == null
                && maxTasksPerTick == null
                && initialTasksPerTick == null
                && targetTickNs == null
                && flushIntervalTicks == null
                && workerThreads == null
                && scanRadiusMultiplier == null;
    }

    /**
     * 将此任务配置合并到全局配置，返回最终生效的配置值。
     * 此任务配置中为 null 的字段使用 defaults 中的值。
     */
    public ChunkScannerConfig applyTo(ChunkScannerConfig defaults) {
        ChunkScannerConfig result = defaults.copy();
        if (minRevisitIntervalSec != null) result.minRevisitIntervalSec = minRevisitIntervalSec;
        if (maxTasksPerTick != null) result.maxTasksPerTick = maxTasksPerTick;
        if (initialTasksPerTick != null) result.initialTasksPerTick = initialTasksPerTick;
        if (targetTickNs != null) result.targetTickNs = targetTickNs;
        if (flushIntervalTicks != null) result.flushIntervalTicks = flushIntervalTicks;
        if (workerThreads != null) result.workerThreads = workerThreads;
        if (scanRadiusMultiplier != null) result.scanRadiusMultiplier = scanRadiusMultiplier;
        return result;
    }

    /** 生成配置说明字符串。 */
    public String toDisplayString() {
        StringBuilder sb = new StringBuilder();
        if (minRevisitIntervalSec != null) sb.append("revisit=").append(minRevisitIntervalSec).append(" ");
        if (maxTasksPerTick != null) sb.append("tasks=").append(maxTasksPerTick).append(" ");
        if (initialTasksPerTick != null) sb.append("initTasks=").append(initialTasksPerTick).append(" ");
        if (targetTickNs != null) sb.append("targetNs=").append(targetTickNs).append(" ");
        if (flushIntervalTicks != null) sb.append("flush=").append(flushIntervalTicks).append(" ");
        if (workerThreads != null) sb.append("threads=").append(workerThreads).append(" ");
        if (scanRadiusMultiplier != null) sb.append("radius=").append(scanRadiusMultiplier).append(" ");
        return sb.toString().trim();
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
            return cfg.isAllNull() ? null : cfg;
        } catch (Exception e) {
            ChunkScannerMod.LOGGER.warn("Failed to parse TaskConfig from JSON: {}", json);
            return null;
        }
    }

    /** 创建当前配置的副本。 */
    public TaskConfig copy() {
        TaskConfig cfg = new TaskConfig();
        cfg.minRevisitIntervalSec = this.minRevisitIntervalSec;
        cfg.maxTasksPerTick = this.maxTasksPerTick;
        cfg.initialTasksPerTick = this.initialTasksPerTick;
        cfg.targetTickNs = this.targetTickNs;
        cfg.flushIntervalTicks = this.flushIntervalTicks;
        cfg.workerThreads = this.workerThreads;
        cfg.scanRadiusMultiplier = this.scanRadiusMultiplier;
        return cfg;
    }
}

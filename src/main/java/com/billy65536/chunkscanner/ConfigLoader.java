package com.billy65536.chunkscanner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 配置加载器。
 *
 * 加载策略：
 * 1. Cloth Config 优先 — 若已安装，配置由 Cloth Config 管理，JSON 文件仅作为备份。
 * 2. JSON 文件后备 — 若 Cloth Config 未安装，直接从 chunkscanner.json 读写配置。
 *
 * 注意：detectClothConfig 始终返回 false（回退 JSON），因为 Cloth Config 通过
 * ClothConfigIntegration.createConfigScreen 的 setSavingRunnable 独立管理保存逻辑。
 */
public class ConfigLoader {

    private static final String CONFIG_FILENAME = "chunkscanner.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Cloth Config 是否可用（懒加载检测，仅检测一次）。 */
    private static boolean clothAvailable = false;
    private static boolean clothChecked = false;

    public static void load(ChunkScannerConfig config) {
        if (detectClothConfig(config)) return;
        loadFromJson(config);
    }

    public static void save(ChunkScannerConfig config) {
        // 如果 Cloth Config 管理配置，JSON 保存由 Cloth Config 的 setSavingRunnable 触发
        if (clothAvailable) return;
        saveToJson(config);
    }

    /** 检测 Cloth Config 是否可用，但始终回退到 JSON 加载。 */
    private static boolean detectClothConfig(ChunkScannerConfig config) {
        if (!clothChecked) {
            clothAvailable = FabricLoader.getInstance().isModLoaded("cloth-config");
            clothChecked = true;
            if (clothAvailable) {
                ChunkScannerMod.LOGGER.info("Cloth Config detected, configuration will be managed by Cloth Config.");
            }
        }
        return false; // 回退 JSON 加载，Cloth Config 集成另行通过 createConfigScreen 处理
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILENAME);
    }

    private static void loadFromJson(ChunkScannerConfig config) {
        Path path = configPath();
        if (!Files.exists(path)) {
            ChunkScannerMod.LOGGER.info("No config found, using defaults.");
            saveToJson(config);
            return;
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject json = JsonParser.parseString(content).getAsJsonObject();

            // 读取扫描默认值：优先从 "defaults" 节点，回退到根节点（旧版兼容）
            JsonObject defaults = json.has("defaults") ? json.getAsJsonObject("defaults") : json;
            if (defaults.has("minRevisitIntervalSec"))
                config.minRevisitIntervalSec = defaults.get("minRevisitIntervalSec").getAsInt();
            if (defaults.has("maxTasksPerTick"))
                config.maxTasksPerTick = defaults.get("maxTasksPerTick").getAsInt();
            if (defaults.has("initialTasksPerTick"))
                config.initialTasksPerTick = defaults.get("initialTasksPerTick").getAsInt();
            if (defaults.has("targetTickNs"))
                config.targetTickNs = defaults.get("targetTickNs").getAsLong();
            if (defaults.has("flushIntervalTicks"))
                config.flushIntervalTicks = defaults.get("flushIntervalTicks").getAsInt();
            if (defaults.has("workerThreads"))
                config.workerThreads = defaults.get("workerThreads").getAsInt();
            if (defaults.has("scanRadiusMultiplier"))
                config.scanRadiusMultiplier = defaults.get("scanRadiusMultiplier").getAsDouble();

            // 读取路径点默认值
            if (json.has("waypoint")) {
                JsonObject wp = json.getAsJsonObject("waypoint");
                if (wp.has("name")) config.waypointName = wp.get("name").getAsString();
                if (wp.has("initials")) config.waypointInitials = wp.get("initials").getAsString();
                if (wp.has("group")) config.waypointGroup = wp.get("group").getAsString();
            }

            ChunkScannerMod.LOGGER.info("Config loaded from: {}", path);
        } catch (IOException e) {
            ChunkScannerMod.LOGGER.error("Failed to load config: {}", e.getMessage());
        }
    }

    private static void saveToJson(ChunkScannerConfig config) {
        try {
            Path path = configPath();
            Files.createDirectories(path.getParent());
            JsonObject json = new JsonObject();

            // 扫描默认值
            JsonObject defaults = new JsonObject();
            defaults.addProperty("minRevisitIntervalSec", config.minRevisitIntervalSec);
            defaults.addProperty("maxTasksPerTick", config.maxTasksPerTick);
            defaults.addProperty("initialTasksPerTick", config.initialTasksPerTick);
            defaults.addProperty("targetTickNs", config.targetTickNs);
            defaults.addProperty("flushIntervalTicks", config.flushIntervalTicks);
            defaults.addProperty("workerThreads", config.workerThreads);
            defaults.addProperty("scanRadiusMultiplier", config.scanRadiusMultiplier);
            json.add("defaults", defaults);

            // 路径点默认值
            JsonObject waypoint = new JsonObject();
            waypoint.addProperty("name", config.waypointName);
            waypoint.addProperty("initials", config.waypointInitials);
            waypoint.addProperty("group", config.waypointGroup);
            json.add("waypoint", waypoint);

            Files.writeString(path, GSON.toJson(json), StandardCharsets.UTF_8);
            ChunkScannerMod.LOGGER.info("Config saved to: {}", path);
        } catch (IOException e) {
            ChunkScannerMod.LOGGER.error("Failed to save config: {}", e.getMessage());
        }
    }
}

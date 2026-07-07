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
            if (json.has("minRevisitIntervalSec"))
                config.minRevisitIntervalSec = json.get("minRevisitIntervalSec").getAsInt();
            if (json.has("maxTasksPerTick"))
                config.maxTasksPerTick = json.get("maxTasksPerTick").getAsInt();
            if (json.has("initialTasksPerTick"))
                config.initialTasksPerTick = json.get("initialTasksPerTick").getAsInt();
            if (json.has("targetTickNs"))
                config.targetTickNs = json.get("targetTickNs").getAsLong();
            if (json.has("flushIntervalTicks"))
                config.flushIntervalTicks = json.get("flushIntervalTicks").getAsInt();
            if (json.has("workerThreads"))
                config.workerThreads = json.get("workerThreads").getAsInt();
            if (json.has("scanRadiusMultiplier"))
                config.scanRadiusMultiplier = json.get("scanRadiusMultiplier").getAsDouble();
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
            json.addProperty("minRevisitIntervalSec", config.minRevisitIntervalSec);
            json.addProperty("maxTasksPerTick", config.maxTasksPerTick);
            json.addProperty("initialTasksPerTick", config.initialTasksPerTick);
            json.addProperty("targetTickNs", config.targetTickNs);
            json.addProperty("flushIntervalTicks", config.flushIntervalTicks);
            json.addProperty("workerThreads", config.workerThreads);
            json.addProperty("scanRadiusMultiplier", config.scanRadiusMultiplier);
            Files.writeString(path, GSON.toJson(json), StandardCharsets.UTF_8);
            ChunkScannerMod.LOGGER.info("Config saved to: {}", path);
        } catch (IOException e) {
            ChunkScannerMod.LOGGER.error("Failed to save config: {}", e.getMessage());
        }
    }
}

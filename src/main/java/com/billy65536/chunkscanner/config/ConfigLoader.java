package com.billy65536.chunkscanner.config;

import com.billy65536.chunkscanner.ChunkScannerMod;
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
        ensureClothDetected();
        // 始终从 JSON 加载基线配置（Cloth Config 存在时也会加载，确保默认值来自 JSON）
        loadFromJson(config);
    }

    public static void save(ChunkScannerConfig config) {
        // 始终写入 JSON，确保持久化（Cloth Config 也通过 setSavingRunnable 调用此方法）
        saveToJson(config);
    }

    /** 检测 Cloth Config 是否可用（用于 save() 决策）。JSON 加载始终执行。 */
    private static void ensureClothDetected() {
        if (!clothChecked) {
            clothAvailable = FabricLoader.getInstance().isModLoaded("cloth-config");
            clothChecked = true;
            if (clothAvailable) {
                ChunkScannerMod.LOGGER.info("Cloth Config detected, configuration will be managed by Cloth Config.");
            }
        }
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
            if (defaults.has("qshopSellBuyPattern"))
                config.qshopSellBuyPattern = defaults.get("qshopSellBuyPattern").getAsString();
            if (defaults.has("qshopInfinitePattern"))
                config.qshopInfinitePattern = defaults.get("qshopInfinitePattern").getAsString();
            if (defaults.has("qshopOutOfStockPattern"))
                config.qshopOutOfStockPattern = defaults.get("qshopOutOfStockPattern").getAsString();
            if (defaults.has("qshopOutOfSpacePattern"))
                config.qshopOutOfSpacePattern = defaults.get("qshopOutOfSpacePattern").getAsString();
            if (defaults.has("qshopPricePattern"))
                config.qshopPricePattern = defaults.get("qshopPricePattern").getAsString();
            if (defaults.has("qshopSellKeyword"))
                config.qshopSellKeyword = defaults.get("qshopSellKeyword").getAsString();
            if (defaults.has("qshopBuyKeyword"))
                config.qshopBuyKeyword = defaults.get("qshopBuyKeyword").getAsString();
            if (defaults.has("qshopHighlightEnabled"))
                config.qshopHighlightEnabled = defaults.get("qshopHighlightEnabled").getAsBoolean();
            if (defaults.has("qshopHighlightRadius"))
                config.qshopHighlightRadius = defaults.get("qshopHighlightRadius").getAsInt();
            if (defaults.has("qshopHighlightGradientMs"))
                config.qshopHighlightGradientMs = defaults.get("qshopHighlightGradientMs").getAsLong();
            if (defaults.has("qshopEnhanceMatchMode"))
                config.qshopEnhanceMatchMode = parseEnhanceMatchMode(defaults.get("qshopEnhanceMatchMode").getAsString());
            if (defaults.has("qshopManualEnhanceItemExpireMs"))
                config.qshopManualEnhanceItemExpireMs = defaults.get("qshopManualEnhanceItemExpireMs").getAsLong();
            if (defaults.has("qshopChatInterceptionMethod"))
                config.qshopChatInterceptionMethod = parseChatInterceptionMethod(defaults.get("qshopChatInterceptionMethod").getAsString());

            // 读取路径点默认值
            if (json.has("waypoint")) {
                JsonObject wp = json.getAsJsonObject("waypoint");
                if (wp.has("name")) config.waypointName = wp.get("name").getAsString();
                if (wp.has("initials")) config.waypointInitials = wp.get("initials").getAsString();
                if (wp.has("group")) config.waypointGroup = wp.get("group").getAsString();
            }

            // 读取导航默认值
            if (json.has("navigation")) {
                JsonObject nav = json.getAsJsonObject("navigation");
                if (nav.has("autoEnabled")) config.navAutoEnabled = nav.get("autoEnabled").getAsBoolean();
                if (nav.has("reachDist")) config.navReachDist = nav.get("reachDist").getAsDouble();
                if (nav.has("compositeLimit")) config.navCompositeLimit = nav.get("compositeLimit").getAsInt();
                if (nav.has("baritoneRiskWarning"))
                    config.baritoneRiskWarning = parseBaritoneRiskWarning(nav.get("baritoneRiskWarning").getAsString());
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
            defaults.addProperty("qshopSellBuyPattern", config.qshopSellBuyPattern);
            defaults.addProperty("qshopInfinitePattern", config.qshopInfinitePattern);
            defaults.addProperty("qshopOutOfStockPattern", config.qshopOutOfStockPattern);
            defaults.addProperty("qshopOutOfSpacePattern", config.qshopOutOfSpacePattern);
            defaults.addProperty("qshopPricePattern", config.qshopPricePattern);
            defaults.addProperty("qshopSellKeyword", config.qshopSellKeyword);
            defaults.addProperty("qshopBuyKeyword", config.qshopBuyKeyword);
            defaults.addProperty("qshopHighlightEnabled", config.qshopHighlightEnabled);
            defaults.addProperty("qshopHighlightRadius", config.qshopHighlightRadius);
            defaults.addProperty("qshopHighlightGradientMs", config.qshopHighlightGradientMs);
            defaults.addProperty("qshopEnhanceMatchMode", config.qshopEnhanceMatchMode.name());
            defaults.addProperty("qshopManualEnhanceItemExpireMs", config.qshopManualEnhanceItemExpireMs);
            defaults.addProperty("qshopChatInterceptionMethod", config.qshopChatInterceptionMethod.name());
            json.add("defaults", defaults);

            // 路径点默认值
            JsonObject waypoint = new JsonObject();
            waypoint.addProperty("name", config.waypointName);
            waypoint.addProperty("initials", config.waypointInitials);
            waypoint.addProperty("group", config.waypointGroup);
            json.add("waypoint", waypoint);

            // 导航默认值
            JsonObject navigation = new JsonObject();
            navigation.addProperty("autoEnabled", config.navAutoEnabled);
            navigation.addProperty("reachDist", config.navReachDist);
            navigation.addProperty("compositeLimit", config.navCompositeLimit);
            navigation.addProperty("baritoneRiskWarning", config.baritoneRiskWarning.name());
            json.add("navigation", navigation);

            Files.writeString(path, GSON.toJson(json), StandardCharsets.UTF_8);
            ChunkScannerMod.LOGGER.info("Config saved to: {}", path);
        } catch (IOException e) {
            ChunkScannerMod.LOGGER.error("Failed to save config: {}", e.getMessage());
        }
    }

    /** 安全解析增强匹配模式字符串，无法识别时回退默认值。兼容旧版枚举名。 */
    private static ChunkScannerConfig.EnhanceMatchMode parseEnhanceMatchMode(String s) {
        if (s == null) {
            ChunkScannerMod.LOGGER.warn("Enhance match mode is null, falling back to StrictAutomatic");
            return ChunkScannerConfig.EnhanceMatchMode.StrictAutomatic;
        }
        // 向后兼容：映射旧枚举名到新枚举名
        switch (s) {
            case "Strict": return ChunkScannerConfig.EnhanceMatchMode.StrictAutomatic;
            case "TimeOnly": return ChunkScannerConfig.EnhanceMatchMode.WeakAutomatic;
            case "Manual": return ChunkScannerConfig.EnhanceMatchMode.NonAutomatic;
        }
        try {
            return ChunkScannerConfig.EnhanceMatchMode.valueOf(s);
        } catch (IllegalArgumentException e) {
            ChunkScannerMod.LOGGER.warn("Unknown enhance match mode '{}', falling back to StrictAutomatic", s);
            return ChunkScannerConfig.EnhanceMatchMode.StrictAutomatic;
        }
    }

    /** 安全解析聊天拦截方式字符串，无法识别时回退默认值。 */
    private static ChunkScannerConfig.ChatInterceptionMethod parseChatInterceptionMethod(String s) {
        if (s == null) {
            ChunkScannerMod.LOGGER.warn("Chat interception method is null, falling back to BOTH");
            return ChunkScannerConfig.ChatInterceptionMethod.BOTH;
        }
        try {
            return ChunkScannerConfig.ChatInterceptionMethod.valueOf(s);
        } catch (IllegalArgumentException e) {
            ChunkScannerMod.LOGGER.warn("Unknown chat interception method '{}', falling back to BOTH", s);
            return ChunkScannerConfig.ChatInterceptionMethod.BOTH;
        }
    }

    /** 安全解析 Baritone 风险警告字符串，无法识别时回退默认值。 */
    private static ChunkScannerConfig.BaritoneRiskWarning parseBaritoneRiskWarning(String s) {
        if (s == null) {
            ChunkScannerMod.LOGGER.warn("Baritone risk warning is null, falling back to SHOWN");
            return ChunkScannerConfig.BaritoneRiskWarning.SHOWN;
        }
        try {
            return ChunkScannerConfig.BaritoneRiskWarning.valueOf(s);
        } catch (IllegalArgumentException e) {
            ChunkScannerMod.LOGGER.warn("Unknown baritone risk warning '{}', falling back to SHOWN", s);
            return ChunkScannerConfig.BaritoneRiskWarning.SHOWN;
        }
    }
}

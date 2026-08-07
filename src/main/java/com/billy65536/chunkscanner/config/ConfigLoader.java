package com.billy65536.chunkscanner.config;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.infrastructure.core.module.ModuleRegistry;
import com.billy65536.infrastructure.security.builtin.ConfigLocker;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 配置加载器：AutoConfig 的薄封装。
 *
 * <p>持久化完全由 AutoConfig 的 {@code GsonConfigSerializer} 接管，
 * 写入 {@code config/chunkscanner.json}，JSON 结构与 {@link ChunkScannerConfig}
 * 的对象层级一一对应，无需手工映射。
 *
 * <p>{@link #register()} 必须在任何 {@link #get()} 调用之前执行（即
 * {@code onInitializeClient} 的最开头）。
 */
public class ConfigLoader {

    private ConfigLoader() {}

    private static final String CONFIG_FILENAME = "chunkscanner.json";

    private static ConfigHolder<ChunkScannerConfig> holder;

    /**
     * 注册 AutoConfig。必须在客户端初始化最开头调用一次。
     *
     * <p>注册前会检测旧版（v1.0）配置格式，若命中则输出警告：旧格式的
     * {@code defaults}/{@code waypoint}/{@code navigation} 三节点结构无法被
     * AutoConfig 解析，配置将回退为默认值并被新格式覆盖。
     */
    public static void register() {
        if (holder != null) return;
        warnIfLegacyFormat();
        holder = AutoConfig.register(ChunkScannerConfig.class, GsonConfigSerializer::new);
        ChunkScannerMod.LOGGER.info("AutoConfig registered for ChunkScannerConfig.");
    }

    /** 返回 AutoConfig 持有的活动配置实例。 */
    public static ChunkScannerConfig get() {
        return holder().getConfig();
    }

    /** 配置子系统是否已注册（游戏外 / 早期初始化时可能为 false）。 */
    public static boolean isRegistered() {
        return holder != null;
    }

    /** 从磁盘重新加载配置。 */
    public static void load() {
        holder().load();
        // 重放服务器锁定强制值（防手动编辑磁盘文件绕过）
        var module = ModuleRegistry.get(ChunkScannerMod.MOD_ID);
        if (module != null) {
            ConfigLocker.applyAll(module.getConfigDescriptors());
        } else {
            ChunkScannerMod.LOGGER.warn(
                    "Module not registered yet, server config locks were NOT applied during config load.");
        }
        ChunkScannerMod.LOGGER.info("Config reloaded from disk.");
    }

    /** 将当前配置持久化到磁盘。 */
    public static void save() {
        holder().save();
    }

    private static ConfigHolder<ChunkScannerConfig> holder() {
        if (holder == null) {
            throw new IllegalStateException(
                    "ConfigLoader.register() must be called before accessing the config.");
        }
        return holder;
    }

    /** 检测旧版配置格式并告警。 */
    private static void warnIfLegacyFormat() {
        try {
            Path path = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILENAME);
            if (!Files.exists(path)) return;
            String content = Files.readString(path, StandardCharsets.UTF_8);
            // 旧格式特征：顶层含 "defaults" 节点，且不含新格式的 "scanner" 节点
            if (content.contains("\"defaults\"") && !content.contains("\"scanner\"")) {
                ChunkScannerMod.LOGGER.warn(
                        "Legacy config format detected at {}. The config structure has changed in this version; "
                                + "settings will be reset to defaults and the file rewritten in the new format.",
                        path);
            }
        } catch (Exception e) {
            ChunkScannerMod.LOGGER.warn("Failed to inspect existing config file: {}", e.getMessage());
        }
    }
}

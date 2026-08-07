package com.billy65536.chunkscanner;

import java.util.Collection;
import java.util.List;

import com.billy65536.chunkscanner.config.ChunkScannerConfig;
import com.billy65536.chunkscanner.config.ConfigLoader;
import com.billy65536.infrastructure.core.config.ConfigDescriptor;
import com.billy65536.infrastructure.core.config.ConfigPath;
import com.billy65536.infrastructure.core.module.IModule;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * chunkscanner 作为 infrastructure 模块的接入点（{@link IModule} 实现）。
 *
 * <p>经由 Java SPI（{@code META-INF/services/...IModule}）由 infrastructure 的
 * {@code ModuleRegistry#discover()} 自动发现并登记，无需在启动代码中显式注册。
 * 登记后：</p>
 * <ul>
 *   <li>{@code /inf config get|set|reset|gui|reload chunkscanner:config/...} 可统一读写其配置；</li>
 *   <li>{@code /inf info chunkscanner} 列出其贡献的命令与配置路径；</li>
 *   <li>配置锁定由 infrastructure 的安全策略框架提供，本模块的默认锁经
 *       {@link com.billy65536.chunkscanner.security.ChunkscannerSecurityProvider}
 *       扩展点贡献。</li>
 * </ul>
 *
 * <p>原 chunkscanner 自有的 {@code /cs config get|set|reset|gui|reload} 已全部移除，
 * 配置访问统一收归 {@code /inf config}。</p>
 */
public final class ChunkscannerModule implements IModule {

    private static final String ID = "chunkscanner";

    /** 供 Java SPI 实例化；登记由 infrastructure ModuleRegistry.discover() 统一触发。 */
    public ChunkscannerModule() {}

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getVersion() {
        return ChunkScannerMod.getVersion();
    }

    @Override
    public Text getName() {
        return Text.literal("Chunk Scanner");
    }

    @Override
    public Text getDescription() {
        return Text.translatable("chunkscanner.msg.module_desc");
    }

    // ==================== 配置 ====================

    @Override
    public List<ConfigDescriptor> getConfigDescriptors() {
        // 段名 "config"：完整路径形如 chunkscanner:config/components.qshop.highlightEnabled。
        // 含危险配置项（dangerous=true）：components.qshop.highlightEnabled 为 server-lock 强制值。
        ConfigPath path = ConfigPath.of(ID, "config", "");
        return List.of(ConfigDescriptor.dangerous(
                path,
                ChunkScannerMod::getConfig,
                new ChunkScannerConfig(),
                () -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client != null) {
                        Screen parent = client.currentScreen;
                        client.setScreen(
                                com.billy65536.chunkscanner.integration.ClothConfigIntegration
                                        .createConfigScreen(parent));
                    }
                }));
    }

    @Override
    public void saveConfig() {
        ConfigLoader.save();
    }

    // ==================== 命令 ====================

    // 命令（/cs、/chunkscanner、/csc）仍由 ChunkScannerMod 在 onInitializeClient 中统一注册，
    // 本模块不重复贡献命令树，避免别名（cs/csc）丢失与重复挂载。
    @Override
    public LiteralArgumentBuilder<FabricClientCommandSource> buildCommands() {
        // 命令树（/cs、/chunkscanner、/csc）由 ChunkScannerMod 在 onInitializeClient 通过
        // ClientCommandRegistrationCallback 统一注册；返回 null 依赖 ModuleCommandRegistrar 对 null
        // 判空且不会依据 getCommandLiterals() 二次挂载，否则会与已注册命令冲突。
        return null;
    }

    @Override
    public Collection<String> getCommandLiterals() {
        return List.of();
    }
}

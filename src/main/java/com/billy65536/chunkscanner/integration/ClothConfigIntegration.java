package com.billy65536.chunkscanner.integration;

import com.billy65536.chunkscanner.config.ChunkScannerConfig;

import me.shedaniel.autoconfig.AutoConfig;
import net.minecraft.client.gui.screen.Screen;

/**
 * Cloth Config / AutoConfig 集成。
 *
 * <p>配置界面由 {@link ChunkScannerConfig} 的对象结构自动生成，分组与
 * {@code /cs get|set|reset} 的点分路径一一对应。条目标签与工具提示来自语言文件
 * （{@code text.autoconfig.chunkscanner.option.*} 及其 {@code .@Tooltip} 后缀）。
 *
 * <p>Cloth Config 是必需依赖（见 {@code fabric.mod.json}），无需运行时降级检测。
 */
public class ClothConfigIntegration {

    private ClothConfigIntegration() {}

    /**
     * 创建配置界面。
     *
     * @param parent 返回时的父界面
     */
    public static Screen createConfigScreen(Screen parent) {
        return AutoConfig.getConfigScreen(ChunkScannerConfig.class, parent).get();
    }
}

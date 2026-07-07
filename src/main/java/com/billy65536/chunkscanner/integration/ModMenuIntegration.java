package com.billy65536.chunkscanner.integration;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu API 集成入口。
 *
 * 实现 ModMenuApi 接口，使得 ChunkScanner 在 ModMenu 模组列表中
 * 显示"设置"按钮，点击后跳转到 Cloth Config 配置界面。
 *
 * 仅在 ModMenu 被加载时生效（通过 fabric.mod.json 中的 entrypoint）。
 */
public class ModMenuIntegration implements ModMenuApi {

    /** 提供配置界面工厂，委托给 ClothConfigIntegration。 */
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ClothConfigIntegration::createConfigScreen;
    }
}

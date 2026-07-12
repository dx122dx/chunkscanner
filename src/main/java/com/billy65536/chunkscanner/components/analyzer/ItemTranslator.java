package com.billy65536.chunkscanner.components.analyzer;

import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * 物品译名 → 注册名映射表。
 *
 * <p>在进入服务器/世界时构建，遍历 {@link Registries#ITEM} 中所有物品，
 * 将其翻译名称（当前客户端语言）映射到注册名（如 "stone" → "minecraft:stone"）。
 *
 * <p>QShop 扫描时使用此映射表，通过告示牌上的商品显示名反查物品注册 ID。
 */
public final class ItemTranslator {

    /** 译名（翻译后的显示名）→ 注册名（不含 minecraft: 前缀时存储完整 Identifier）。 */
    private static final Map<String, String> TRANSLATION_TO_ID = new HashMap<>();

    private ItemTranslator() {}

    /** 遍历物品注册表，构建译名 → 注册名映射。在登录服务器/世界时调用。 */
    public static void buildMapping() {
        TRANSLATION_TO_ID.clear();
        for (Item item : Registries.ITEM) {
            Identifier id = Registries.ITEM.getId(item);
            if (id == null) continue;
            String displayName = item.getName().getString();
            if (displayName == null || displayName.isEmpty()) continue;
            // 注册名：保留完整 ID 字符串（如 "minecraft:stone"）
            TRANSLATION_TO_ID.put(displayName, id.toString());
        }
    }

    /**
     * 通过译名查找注册名。
     *
     * @param translatedName 物品的翻译显示名（告示牌第三行）
     * @return 注册名（如 "minecraft:diamond"），未找到返回 null
     */
    public static String lookup(String translatedName) {
        return TRANSLATION_TO_ID.get(translatedName);
    }

    /** 当前映射表条目数（调试用途）。 */
    public static int size() {
        return TRANSLATION_TO_ID.size();
    }

    /** 清空映射表（断开连接时可选调用）。 */
    public static void clear() {
        TRANSLATION_TO_ID.clear();
    }
}

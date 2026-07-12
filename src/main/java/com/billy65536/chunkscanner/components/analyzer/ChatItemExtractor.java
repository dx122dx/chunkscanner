package com.billy65536.chunkscanner.components.analyzer;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.text.*;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * 从 Minecraft 聊天消息的 Text 组件树中提取 QuickShop 物品信息。
 *
 * <p>QuickShop 通过 BungeeCord Chat API 发送商店 Item 行消息，包含：
 * <ul>
 *   <li>HoverEvent.Action.SHOW_ITEM — 悬停显示物品详情，值为 ItemStack</li>
 *   <li>ClickEvent.Action.RUN_COMMAND — 点击执行 /qs silentpreview {UUID}</li>
 * </ul>
 *
 * <p>本提取器遍历 Text 组件树，识别同时包含 SHOW_ITEM hover 和
 * /qs silentpreview click 的组件，提取其中的物品数据。
 *
 * <p><b>线程安全</b>：所有方法均为静态、无状态，可在任意线程调用。
 */
public final class ChatItemExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger("chunkscanner.chat");

    private ChatItemExtractor() {}

    // ==================== 提取结果 ====================

    /**
     * 从单条聊天消息中提取的 QuickShop 物品数据。
     *
     * @param registryId 物品注册名（如 "minecraft:diamond"）
     * @param nbtHash    物品 NBT 的 CRC32 哈希（0 表示无 NBT）
     * @param enchants    附魔列表（不可变，可能为空）
     */
    public record ExtractedItem(String registryId, int nbtHash, List<String> enchants) {
        /** 是否有附魔。 */
        public boolean hasEnchants() {
            return enchants != null && !enchants.isEmpty();
        }
    }

    // ==================== 公共接口 ====================

    /**
     * 从一条聊天消息 Text 中尝试提取 QuickShop 物品数据。
     *
     * @param message 聊天消息的根 Text 组件
     * @return 提取的物品数据，如果不是 QuickShop Item 行则返回 null
     */
    public static ExtractedItem extract(Text message) {
        if (message == null) return null;

        // 快速预检：整个消息中是否包含 "/qs silentpreview"
        if (!containsSilentPreview(message)) return null;

        // 深度遍历，找到同时有 SHOW_ITEM 和 silentpreview 的组件
        return findItemComponent(message);
    }

    // ==================== 内部遍历逻辑 ====================

    /**
     * 检查 Text 组件树中是否包含 "/qs silentpreview" 命令。
     */
    private static boolean containsSilentPreview(Text text) {
        Style style = text.getStyle();
        if (style != null) {
            ClickEvent click = style.getClickEvent();
            if (click != null && click.getAction() == ClickEvent.Action.RUN_COMMAND) {
                if (click.getValue().startsWith("/qs silentpreview")) {
                    return true;
                }
            }
        }

        // 递归检查子组件
        for (Text sibling : text.getSiblings()) {
            if (containsSilentPreview(sibling)) return true;
        }
        return false;
    }

    /**
     * 在组件树中查找 SHOW_ITEM hover 并提取 ItemStack 数据。
     *
     * <p>注意：QuickShop 将 HoverEvent（物品详情）和 ClickEvent（/qs silentpreview）
     * 放在<b>不同</b>的 Text 组件上（物品名称带 hover，[物品预览] 带 click）。
     * 因此本方法只查找 SHOW_ITEM hover，前置的 {@link #containsSilentPreview}
     * 已确认这是 QuickShop 消息。</p>
     */
    private static ExtractedItem findItemComponent(Text text) {
        Style style = text.getStyle();
        if (style != null) {
            HoverEvent hover = style.getHoverEvent();
            if (hover != null && hover.getAction() == HoverEvent.Action.SHOW_ITEM) {
                ItemStack stack = extractItemStack(hover);
                if (stack != null && !stack.isEmpty()) {
                    return buildExtractedItem(stack);
                }
            }
        }

        // 递归检查子组件
        for (Text sibling : text.getSiblings()) {
            ExtractedItem result = findItemComponent(sibling);
            if (result != null) return result;
        }
        return null;
    }

    /**
     * 从 HoverEvent 中提取 ItemStack。
     *
     * <p>Minecraft 1.20.1 中 SHOW_ITEM 的 value 类型为
     * {@link HoverEvent.ItemStackContent}，通过 getValue() 获取。
     */
    @SuppressWarnings("unchecked")
    private static ItemStack extractItemStack(HoverEvent hover) {
        try {
            Object value = hover.getValue(HoverEvent.Action.SHOW_ITEM);
            if (value instanceof HoverEvent.ItemStackContent content) {
                return content.asStack();
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to extract ItemStack from HoverEvent: {}", e.getMessage());
        }
        return null;
    }

    // ==================== 数据构建 ====================

    /**
     * 从 ItemStack 构建 ExtractedItem。
     */
    private static ExtractedItem buildExtractedItem(ItemStack stack) {
        // 获取注册名
        Identifier id = Registries.ITEM.getId(stack.getItem());
        String registryId = id != null ? id.toString() : "unknown";

        // 提取附魔列表
        List<String> enchants = extractEnchantments(stack);

        // 计算 NBT 哈希（排除附魔，因为附魔已有单独字段）
        int nbtHash = computeNbtHash(stack);

        return new ExtractedItem(registryId, nbtHash, enchants);
    }

    /**
     * 从 ItemStack 中提取附魔列表（格式："minecraft:sharpness:5"）。
     */
    private static List<String> extractEnchantments(ItemStack stack) {
        List<String> result = new ArrayList<>();
        NbtCompound nbt = stack.getNbt();
        if (nbt == null) return result;

        // 附魔存储在 tag.Enchantments 或 tag.StoredEnchantments 中
        extractEnchFromList(nbt, "Enchantments", result);
        extractEnchFromList(nbt, "StoredEnchantments", result);

        return result;
    }

    private static void extractEnchFromList(NbtCompound nbt, String key, List<String> result) {
        if (!nbt.contains(key, NbtElement.LIST_TYPE)) return;
        NbtList list = nbt.getList(key, NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound ench = list.getCompound(i);
            String enchId = ench.getString("id");
            int lvl = ench.getShort("lvl");
            if (enchId != null && !enchId.isEmpty()) {
                result.add(enchId + ":" + lvl);
            }
        }
    }

    /**
     * 计算 ItemStack NBT 的 CRC32 哈希（排除附魔列表，因为已有单独字段）。
     */
    private static int computeNbtHash(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        if (nbt == null) return 0;

        // 创建 NBT 副本，移除附魔相关键
        NbtCompound filtered = nbt.copy();
        filtered.remove("Enchantments");
        filtered.remove("StoredEnchantments");

        // 移除 display（显示名、Lore 等不重要的字段）
        filtered.remove("display");

        // 移除 Damage（耐久度，每件物品不同，无意义）
        filtered.remove("Damage");

        // 移除 RepairCost
        filtered.remove("RepairCost");

        // 如果过滤后为空，返回 0
        if (filtered.isEmpty()) return 0;

        // 计算 CRC32
        byte[] nbtBytes = filtered.toString().getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(nbtBytes);
        return (int) crc.getValue();
    }
}

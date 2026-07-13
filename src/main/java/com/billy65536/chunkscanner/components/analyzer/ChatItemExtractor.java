package com.billy65536.chunkscanner.components.analyzer;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.text.*;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
 * <h3>特殊物品处理</h3>
 * <ul>
 *   <li><b>潜影盒（Shulker Box）</b>：若已满（27 格）且内容物全部相同，
 *       自动"展开"——将内容物作为实际商品，设置 S 标志。</li>
 *   <li><b>成书（Written Book）</b>：提取标题作为商品名，设置 B 标志。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：所有方法均为静态、无状态，可在任意线程调用。
 */
public final class ChatItemExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger("chunkscanner.chat");

    /** 潜影盒注册名集合（含所有染色变种）。 */
    private static final Set<String> SHULKER_BOX_IDS = Set.of(
            "minecraft:shulker_box",
            "minecraft:white_shulker_box", "minecraft:orange_shulker_box",
            "minecraft:magenta_shulker_box", "minecraft:light_blue_shulker_box",
            "minecraft:yellow_shulker_box", "minecraft:lime_shulker_box",
            "minecraft:pink_shulker_box", "minecraft:gray_shulker_box",
            "minecraft:light_gray_shulker_box", "minecraft:cyan_shulker_box",
            "minecraft:purple_shulker_box", "minecraft:blue_shulker_box",
            "minecraft:brown_shulker_box", "minecraft:green_shulker_box",
            "minecraft:red_shulker_box", "minecraft:black_shulker_box"
    );

    /** 潜影盒满箱槽位数。 */
    private static final int SHULKER_SLOTS = 27;

    private ChatItemExtractor() {}

    // ==================== 提取结果 ====================

    /** 无特殊标志。 */
    public static final int FLAG_NONE = 0;
    /** 潜影盒已展开（内容物作为商品）。 */
    public static final int FLAG_SHULKER_EXPANDED = 0x01;
    /** 成书（商品名替换为标题）。 */
    public static final int FLAG_BOOK = 0x02;

    /**
     * 从单条聊天消息中提取的 QuickShop 物品数据。
     *
     * @param registryId    物品注册名（潜影盒展开后为内容物注册名）
     * @param nbtHash       物品 NBT 的 CRC32 哈希（0 表示无 NBT）
     * @param enchants      附魔列表（不可变，可能为空）
     * @param fullNbtString 物品完整 NBT 的 JSON 字符串（用于 Detail 列悬停展示）
     * @param flags         特殊物品标志（参见 {@link #FLAG_SHULKER_EXPANDED} 等）
     * @param displayName   物品显示名（潜影盒展开后为内容物名称，成书为标题）
     */
    public record ExtractedItem(String registryId, int nbtHash, List<String> enchants,
                                 String fullNbtString, int flags, String displayName) {
        /** 是否有附魔。 */
        public boolean hasEnchants() {
            return enchants != null && !enchants.isEmpty();
        }

        /** 是否为潜影盒展开（S 标志）。 */
        public boolean isShulkerExpanded() {
            return (flags & FLAG_SHULKER_EXPANDED) != 0;
        }

        /** 是否为成书（B 标志）。 */
        public boolean isBook() {
            return (flags & FLAG_BOOK) != 0;
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

        // 深度遍历，找到 SHOW_ITEM 组件
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
     *
     * <p>处理特殊物品：
     * <ul>
     *   <li>潜影盒：检查 BlockEntityTag.Items，若满箱且内容物一致则展开</li>
     *   <li>成书：提取 tag.title 作为商品名</li>
     * </ul>
     */
    private static ExtractedItem buildExtractedItem(ItemStack stack) {
        // 获取注册名
        Identifier id = Registries.ITEM.getId(stack.getItem());
        String registryId = id != null ? id.toString() : "unknown";

        // 用于 Detail 列悬停的完整 NBT（始终使用原始物品）
        String fullNbtString = serializeFullNbt(stack);

        int flags = FLAG_NONE;
        String displayName = stack.getName().getString();

        // 特殊处理：潜影盒展开
        ShulkerExpansion shulker = tryExpandShulker(stack, registryId);
        if (shulker != null) {
            stack = shulker.contentStack();
            registryId = shulker.contentId();
            displayName = stack.getName().getString();
            flags |= FLAG_SHULKER_EXPANDED;
            LOGGER.debug("Shulker box expanded: {} -> {}", id, registryId);
        }

        // 特殊处理：成书标题
        if ("minecraft:written_book".equals(registryId)) {
            String title = extractBookTitle(stack);
            if (title != null && !title.isEmpty()) {
                displayName = title;
                flags |= FLAG_BOOK;
                LOGGER.debug("Written book title extracted: '{}'", title);
            }
        }

        // 提取附魔列表（潜影盒展开后为内容物的附魔）
        List<String> enchants = extractEnchantments(stack);

        // 计算 NBT 哈希（排除附魔等不稳定字段）
        int nbtHash = computeNbtHash(stack);

        return new ExtractedItem(registryId, nbtHash, enchants, fullNbtString, flags, displayName);
    }

    // ==================== 潜影盒展开 ====================

    /**
     * 潜影盒展开结果。
     *
     * @param contentStack 内容物 ItemStack（堆叠大小为 1，NBT 与箱内物品一致）
     * @param contentId    内容物注册名
     */
    private record ShulkerExpansion(ItemStack contentStack, String contentId) {}

    /**
     * 尝试展开潜影盒。
     *
     * <p>条件：物品是潜影盒（含染色变种），BlockEntityTag.Items 恰好有 27 个非空槽位，
     * 且所有槽位的物品完全相同（同注册名 + 同 NBT）。</p>
     *
     * @param stack      原始 ItemStack
     * @param registryId 物品注册名
     * @return 展开结果，不满足条件则返回 null
     */
    private static ShulkerExpansion tryExpandShulker(ItemStack stack, String registryId) {
        if (!SHULKER_BOX_IDS.contains(registryId)) return null;

        NbtCompound nbt = stack.getNbt();
        if (nbt == null) return null;

        // BlockEntityTag.Items
        NbtCompound blockEntityTag = nbt.getCompound("BlockEntityTag");
        if (blockEntityTag == null || !blockEntityTag.contains("Items", NbtElement.LIST_TYPE)) return null;

        NbtList items = blockEntityTag.getList("Items", NbtElement.COMPOUND_TYPE);
        if (items.size() != SHULKER_SLOTS) return null;

        // 检查所有槽位是否非空且物品相同
        ItemStack firstStack = null;
        String firstId = null;
        String firstItemNbt = null;
        boolean firstUnbreakable = false;

        for (int i = 0; i < SHULKER_SLOTS; i++) {
            NbtCompound slot = items.getCompound(i);
            if (slot.isEmpty()) return null; // 有空槽，不展开

            String slotId = slot.getString("id");
            if (slotId.isEmpty()) return null;

            byte count = slot.getByte("Count");
            if (count <= 0) return null;

            if (i == 0) {
                firstId = slotId;
                firstUnbreakable = slot.contains("tag") && hasUnbreakable(slot.getCompound("tag"));
                // 提取物品 NBT 用于比较（排除槽位号和堆叠数量）
                firstItemNbt = extractItemNbtForCompare(slot);
            } else {
                if (!firstId.equals(slotId)) return null;
                // 比较 NBT（排除 Unbreakable）
                String curItemNbt = extractItemNbtForCompare(slot);
                if (!firstItemNbt.equals(curItemNbt)) return null;
                // Unbreakable 也必须一致
                boolean curUnbreakable = slot.contains("tag") && hasUnbreakable(slot.getCompound("tag"));
                if (firstUnbreakable != curUnbreakable) return null;
            }
        }

        // 构造内容物 ItemStack（堆叠 1 个，保留原始 NBT）
        try {
            NbtCompound firstSlot = items.getCompound(0);
            ItemStack contentStack = ItemStack.fromNbt(firstSlot);
            if (contentStack == null || contentStack.isEmpty()) return null;
            contentStack.setCount(1);
            return new ShulkerExpansion(contentStack, firstId);
        } catch (Exception e) {
            LOGGER.debug("Failed to create shulker content ItemStack: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 检查 NBT 是否包含 Unbreakable:1b。
     */
    private static boolean hasUnbreakable(NbtCompound tag) {
        return tag.contains("Unbreakable") && tag.getBoolean("Unbreakable");
    }

    /**
     * 提取物品 NBT 用于比较（排除 slot、Count、Unbreakable）。
     */
    private static String extractItemNbtForCompare(NbtCompound slot) {
        NbtCompound tag = slot.getCompound("tag");
        if (tag == null || tag.isEmpty()) return "";
        NbtCompound copy = tag.copy();
        copy.remove("Unbreakable");
        copy.remove("display"); // display 可能因物品不同有细微差异
        return copy.isEmpty() ? "" : copy.toString();
    }

    // ==================== 成书标题 ====================

    /**
     * 从成书 ItemStack 中提取标题。
     *
     * @param stack 成书 ItemStack
     * @return 标题字符串，提取失败返回 null
     */
    private static String extractBookTitle(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        if (nbt == null) return null;
        if (!nbt.contains("title", NbtElement.STRING_TYPE)) return null;
        String title = nbt.getString("title");
        return (title != null && !title.isEmpty()) ? title : null;
    }

    // ==================== 附魔提取 ====================

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

    // ==================== NBT 序列化与哈希 ====================

    /**
     * 序列化 ItemStack 完整 NBT 为 JSON 字符串（用于 Detail 列悬停展示）。
     */
    private static String serializeFullNbt(ItemStack stack) {
        try {
            NbtCompound full = stack.writeNbt(new NbtCompound());
            return full.toString();
        } catch (Exception e) {
            LOGGER.debug("Failed to serialize ItemStack NBT: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * 计算 ItemStack NBT 的 CRC32 哈希（排除附魔列表、display、Damage、RepairCost）。
     */
    private static int computeNbtHash(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        if (nbt == null) return 0;

        // 创建 NBT 副本，移除不稳定字段
        NbtCompound filtered = nbt.copy();
        filtered.remove("Enchantments");
        filtered.remove("StoredEnchantments");
        filtered.remove("display");
        filtered.remove("Damage");
        filtered.remove("RepairCost");

        // 对潜影盒展开后的内容物，还需移除 BlockEntityTag（因为原潜影盒的该字段已在展开时处理）
        filtered.remove("BlockEntityTag");

        if (filtered.isEmpty()) return 0;

        // 计算 CRC32
        byte[] nbtBytes = filtered.toString().getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(nbtBytes);
        return (int) crc.getValue();
    }
}

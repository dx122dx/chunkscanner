package com.billy65536.chunkscanner.components.view_provider;

import com.billy65536.chunkscanner.components.analyzer.QShopContract;
import com.billy65536.chunkscanner.components.analyzer.QShopDbAdapter;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;

/**
 * QShop 展示辅助工具。
 */
public final class QShopDisplayUtil {

    private QShopDisplayUtil() {}

    private static final int SHULKER_SLOTS = 27;

    /**
     * 将标志位转换为简写字符显示。每个置位的标志用一个单字符表示。
     * 如果 flags 为 0，返回空字符串。
     */
    public static String formatFlagsShort(int flags) {
        if (flags == 0) return "";
        StringBuilder sb = new StringBuilder();
        if ((flags & QShopContract.FLAG_ID_RECOVERED) != 0) {
            sb.append("R");
        }
        if ((flags & QShopContract.FLAG_ENHANCED_DATA) != 0) {
            sb.append("E");
        }
        if ((flags & QShopContract.FLAG_SHULKER_EXPANDED) != 0) {
            sb.append("S");
        }
        if ((flags & QShopContract.FLAG_BOOK) != 0) {
            sb.append("B");
        }
        return sb.toString();
    }

    /**
     * 构建标志位的 tooltip 文本列表。每行一个标志："{缩写} - {描述}"。
     * 返回 null 表示无需 tooltip。
     */
    public static List<Text> formatFlagsTooltip(int flags) {
        if (flags == 0) return null;
        List<Text> lines = new java.util.ArrayList<>();
        if ((flags & QShopContract.FLAG_ID_RECOVERED) != 0) {
            lines.add(Text.translatable("chunkscanner.qshop.flag.id_recovered"));
        }
        if ((flags & QShopContract.FLAG_ENHANCED_DATA) != 0) {
            lines.add(Text.translatable("chunkscanner.qshop.flag.enhanced"));
        }
        if ((flags & QShopContract.FLAG_SHULKER_EXPANDED) != 0) {
            lines.add(Text.translatable("chunkscanner.qshop.flag.shulker_expanded"));
        }
        if ((flags & QShopContract.FLAG_BOOK) != 0) {
            lines.add(Text.translatable("chunkscanner.qshop.flag.book"));
        }
        return lines.isEmpty() ? null : lines;
    }

    /**
     * 从 detailNbtString 解析 ItemStack，解析失败返回 null。
     */
    public static ItemStack parseDetailItemStack(QShopDbAdapter.Record record) {
        if (record.detailNbtString() == null || record.detailNbtString().isEmpty()) return null;
        try {
            NbtCompound nbt = StringNbtReader.parse(record.detailNbtString());
            ItemStack stack = ItemStack.fromNbt(nbt);
            return (stack != null && !stack.isEmpty()) ? stack : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 构建 Detail 列物品悬停 tooltip。
     */
    public static List<Text> buildDetailTooltip(QShopDbAdapter.Record record) {
        ItemStack stack = parseDetailItemStack(record);
        if (stack == null) return null;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return null;
        try {
            return Screen.getTooltipFromItem(client, stack);
        } catch (Exception e) {
            return List.of(Text.literal(record.itemId()));
        }
    }

    /**
     * 构建 S 标志（潜影盒展开）的单价 tooltip。
     */
    public static List<Text> buildShulkerUnitPriceTooltip(QShopDbAdapter.Record record) {
        try {
            double unitPrice = getEstimatedUnitPriceCents(record) / 100.0;
            if (unitPrice <= 0) return null;

            return List.of(Text.translatable("chunkscanner.qshop.shulker_unit_price",
                            String.format("%.2f", unitPrice))
                    .formatted(Formatting.LIGHT_PURPLE));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取潜影盒条目的有效商品名称：用内部 ItemStack 显示名替代告示牌文本。
     * 非潜影盒条目直接返回原始 itemName。
     */
    public static Text getEffectiveItemName(QShopDbAdapter.Record r) {
        if ((r.flags() & QShopContract.FLAG_SHULKER_EXPANDED) == 0) {
            return Text.literal(r.itemName());
        }
        ItemStack inner = parseDetailItemStack(r);
        if (inner != null) {
            return inner.getName();
        }
        return Text.literal(r.itemName());
    }

    /** 获取物品的堆叠上限。用于潜影盒单价计算。 */
    public static int getMaxStackCount(QShopDbAdapter.Record r) {
        if (r.itemId() != null && !r.itemId().isEmpty()) {
            Identifier id = Identifier.tryParse(r.itemId());
            if (id != null) {
                Item item = Registries.ITEM.get(id);
                return item != null ? item.getMaxCount() : 64;
            }
        }
        return 64;
    }

    /**
     * 获取潜影盒条目的有效数量（供展示与筛选共用）。
     * 潜影盒条目返回箱内物品总数（槽位数 × 堆叠上限）；普通条目返回原始数量。
     */
    public static int getEffectiveQty(QShopDbAdapter.Record r) {
        if ((r.flags() & QShopContract.FLAG_SHULKER_EXPANDED) == 0) {
            return r.quantity();
        }
        if (r.quantity() == QShopContract.INFINITE_QUANTITY) return QShopContract.INFINITE_QUANTITY;
        int maxStack = getMaxStackCount(r);
        if (maxStack <= 0) maxStack = 64;
        return SHULKER_SLOTS * maxStack;
    }

    /**
     * 潜影盒条目预估单价（分）。用于 tooltip 展示。
     * 单价 = 商店价格 /（潜影盒槽位数 × 物品堆叠上限）。
     */
    public static double getEstimatedUnitPriceCents(QShopDbAdapter.Record r) {
        int totalItems = getEffectiveQty(r);
        return r.price() * 1.0 / totalItems;
    }

    /** 将价格整型（最小货币单位）格式化为显示字符串，如 50 → "0.50"。 */
    public static String formatPrice(int cents) {
        long abs = Math.abs((long) cents);
        return (cents < 0 ? "-" : "") + String.format("%d.%02d", abs / 100, abs % 100);
    }

    private static final ZoneId timezone = ZoneId.systemDefault();
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT);

    /** 将时间戳（毫秒）转化为时间字符串。 */
    public static String formatTimestamp(long timestamp) {
        Instant instant = Instant.ofEpochMilli(timestamp);
        LocalDateTime dateTime = LocalDateTime.ofInstant(instant, timezone);
        return timeFormatter.format(dateTime);
    }
}

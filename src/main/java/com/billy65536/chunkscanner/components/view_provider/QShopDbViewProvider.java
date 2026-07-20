package com.billy65536.chunkscanner.components.view_provider;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.Screen;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.billy65536.chunkscanner.components.analyzer.QShopAnalyzer;
import com.billy65536.chunkscanner.components.analyzer.QShopDbAdapter;
import com.billy65536.chunkscanner.core.ChunkDb;
import com.billy65536.chunkscanner.core.DbViewProvider;
import com.billy65536.chunkscanner.core.DbViewProviderRegistry;
import com.billy65536.chunkscanner.core.LocatedPosition;
import com.billy65536.chunkscanner.gui.layout.TableLayoutBuilder;
import com.billy65536.chunkscanner.gui.layout.ILayout;

/**
 * QShop 分析器特化的 DbViewProvider。
 *
 * 解析 qshop 分析器生成的二进制 KV 数据，将原始字节转换为可读的商店信息。
 *
 * 键格式（34 字节）：
 *   "qshop:" (6B) | dimPoolId:u32 (4B) | cx:i32 (4B) | cz:i32 (4B) | keyHi:u64 (8B) | keyLo:u64 (8B)
 *
 * 值格式（48 字节）：
 *   keyHi:u64 (8B) | keyLo:u64 (8B) | owner:u32 (4B) | mode+quantity packed:u32 (4B) |
 *   itemName:u32 (4B) | price:u32 (4B) | timestamp:u64 (8B) | itemId:u32 (4B) | flags:u32 (4B)
 *
 *   mode+quantity 打包：byte0 = mode (0=出售,1=收购), bytes1-3 = quantity (24-bit unsigned)
 *   price：整数，真实价格 = price / 100.0
 */
public class QShopDbViewProvider implements DbViewProvider {

    private final ChunkDb db;


    /** 缓存筛选并排序后的记录。仅渲染线程访问，无需同步。 */
    private List<QShopDbAdapter.Record> cachedFilteredSorted;
    private boolean filteredCacheValid = false;

    // ==================== 排序常量 ====================

    public static final int SORT_NONE = 0;
    public static final int SORT_PRICE_ASC = 1;
    public static final int SORT_PRICE_DESC = 2;
    public static final int SORT_QTY_ASC = 3;
    public static final int SORT_QTY_DESC = 4;

    // ==================== 模式常量 ====================

    /** 包含模式：字段包含筛选文本即匹配（现有默认行为）。 */
    public static final int PATTERN_CONTAINS = 0;
    /** 排除模式：字段不包含筛选文本才匹配（包含的排除）。 */
    public static final int PATTERN_EXCLUDE = 1;
    /** 全字模式：字段完全等于筛选文本才匹配（忽略大小写）。 */
    public static final int PATTERN_EXACT = 2;
    /** 正则模式：字段满足正则表达式才匹配。 */
    public static final int PATTERN_REGEX = 3;

    // ==================== 筛选状态 ====================

    /** 模式筛选：0=全部, 1=出售(MODE_SELL), 2=收购(MODE_BUY) */
    private int modeFilter = 0;
    private String dimFilter = null;
    private String ownerFilter = null;
    private String itemFilter = null;

    /** 各文本筛选字段的匹配模式。 */
    private int dimFilterMode = PATTERN_CONTAINS;
    private int ownerFilterMode = PATTERN_CONTAINS;
    private int itemFilterMode = PATTERN_CONTAINS;

    /** 预编译的正则 Pattern 缓存（仅在 PATTERN_REGEX 模式下非 null）。 */
    private Pattern compiledDimPattern = null;
    private Pattern compiledOwnerPattern = null;
    private Pattern compiledItemPattern = null;

    /** 价格范围筛选（null = 不限制）。内部以货币最小单位存储（乘以 100）。 */
    private Integer priceMinFilter = null;
    private Integer priceMaxFilter = null;

    /** 数量范围筛选（null = 不限制）。 */
    private Integer qtyMinFilter = null;
    private Integer qtyMaxFilter = null;

    /** 排序模式。 */
    private int sortMode = SORT_NONE;

    public QShopDbViewProvider(ChunkDb db) {
        this.db = db;
    }

    @Override
    public ChunkDb getDb() {
        return db;
    }

    // ==================== 筛选接口 ====================

    @Override
    public boolean supportsFilter() { return true; }

    @Override
    public int getFilterButtonColor() {
        return isFilterActive() ? 0xFF55FF55 : 0xFF888888;
    }

    @Override
    public boolean isFilterActive() {
        return modeFilter != 0 || dimFilter != null || ownerFilter != null
                || itemFilter != null || sortMode != SORT_NONE
                || priceMinFilter != null || priceMaxFilter != null
                || qtyMinFilter != null || qtyMaxFilter != null;
    }

    @Override
    public Screen createFilterScreen(Screen parent) {
        return new QShopFilterScreen(parent, this);
    }

    // ==================== 筛选字段存取 ====================

    public int getModeFilter() { return modeFilter; }
    public void setModeFilter(int v) { modeFilter = v; }
    public String getDimFilter() { return dimFilter; }
    public void setDimFilter(String v) { dimFilter = v; }
    public int getDimFilterMode() { return dimFilterMode; }
    public void setDimFilterMode(int v) { dimFilterMode = v; }
    public String getOwnerFilter() { return ownerFilter; }
    public void setOwnerFilter(String v) { ownerFilter = v; }
    public int getOwnerFilterMode() { return ownerFilterMode; }
    public void setOwnerFilterMode(int v) { ownerFilterMode = v; }
    public String getItemFilter() { return itemFilter; }
    public void setItemFilter(String v) { itemFilter = v; }
    public int getItemFilterMode() { return itemFilterMode; }
    public void setItemFilterMode(int v) { itemFilterMode = v; }

    public Integer getPriceMinFilter() { return priceMinFilter; }
    public void setPriceMinFilter(Integer v) { priceMinFilter = v; }
    public Integer getPriceMaxFilter() { return priceMaxFilter; }
    public void setPriceMaxFilter(Integer v) { priceMaxFilter = v; }

    public Integer getQtyMinFilter() { return qtyMinFilter; }
    public void setQtyMinFilter(Integer v) { qtyMinFilter = v; }
    public Integer getQtyMaxFilter() { return qtyMaxFilter; }
    public void setQtyMaxFilter(Integer v) { qtyMaxFilter = v; }

    public int getSortMode() { return sortMode; }
    public void setSortMode(int v) { sortMode = v; }

    /** 筛选条件变更后使缓存失效，并预编译正则 Pattern。 */
    public void invalidateCache() {
        filteredCacheValid = false;
        compiledDimPattern = compileIfNeeded(dimFilter, dimFilterMode);
        compiledOwnerPattern = compileIfNeeded(ownerFilter, ownerFilterMode);
        compiledItemPattern = compileIfNeeded(itemFilter, itemFilterMode);
    }

    private static Pattern compileIfNeeded(String filter, int mode) {
        if (mode != PATTERN_REGEX || filter == null || filter.isEmpty()) return null;
        try {
            return Pattern.compile(filter, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    // ==================== ViewLayout ====================

    private static final String[] HEADERS = {"Pos", "Owner", "Type", "Qty", "Item", "Price", "ID", "Preview", "Flags", "Update Time"};

    @Override
    public ILayout getLayout(TextRenderer textRenderer) {
        List<QShopDbAdapter.Record> matched = getFilteredSortedRecords();
        int metaCount;
        try {
            metaCount = db.getAllChunkMetas().size();
        } catch (Exception e) {
            metaCount = 0;
        }

        TableLayoutBuilder b = new TableLayoutBuilder(textRenderer, metaCount, HEADERS);
        for (QShopDbAdapter.Record r : matched) {
            Text modeText;
            Text quantityText;
            if (r.mode() == QShopAnalyzer.MODE_SELL) {
                modeText = Text.translatable("chunkscanner.filter.mode.sell");
                if (r.quantity() == QShopAnalyzer.INFINITE_QUANTITY) {
                    quantityText = Text.translatable("chunkscanner.qshop.infinite");
                } else if (r.quantity() == 0) {
                    quantityText = Text.translatable("chunkscanner.qshop.out_of_stock");
                } else {
                    quantityText = Text.literal(String.valueOf(r.quantity()));
                }
            } else {
                modeText = Text.translatable("chunkscanner.filter.mode.buy");
                if (r.quantity() == QShopAnalyzer.INFINITE_QUANTITY) {
                    quantityText = Text.translatable("chunkscanner.qshop.infinite");
                } else if (r.quantity() == 0) {
                    quantityText = Text.translatable("chunkscanner.qshop.out_of_space");
                } else {
                    quantityText = Text.literal(String.valueOf(r.quantity()));
                }
            }

            LocatedPosition pos = new LocatedPosition(r.dimId(), r.x(), r.y(), r.z());
            boolean shulker = (r.flags() & QShopAnalyzer.FLAG_SHULKER_EXPANDED) != 0;

            TableLayoutBuilder.RowBuilder row = b.addRow()
                    .position(pos)
                    .text(r.owner())
                    .text(modeText)
                    .text(quantityText)
                    .text(r.itemName())
                    .text(formatPrice(r.price()));

            if (shulker) {
                List<Text> unitPriceTip = buildShulkerUnitPriceTooltip(r);
                if (unitPriceTip != null) {
                    row.withColor(0xFFFF55FF);
                    row.withTooltip(unitPriceTip);
                }
            }

            row.text(r.itemId());

            // Detail 列物品图标和 tooltip
            ItemStack icon = parseDetailItemStack(r);
            if (icon != null) {
                row.item(icon);
            } else {
                row.blank();
            }
            List<Text> detailTips = buildDetailTooltip(r);
            if (detailTips != null) {
                row.withTooltip(detailTips);
            }

            // Flags 列
            String flagsStr = formatFlagsShort(r.flags());
            List<Text> flagTips = formatFlagsTooltip(r.flags());
            row.text(flagsStr);
            if (flagTips != null) {
                row.withTooltip(flagTips);
            }

            // Update Time 列
            String updateTime = formatTimestamp(r.timestamp());
            row.text(updateTime);

            if(r.enhancementTimestamp() > 0) {
                row.withTooltip(new String[] {
                    Text.translatable("chunkscanner.qshop.enhancement_update_time", formatTimestamp(r.enhancementTimestamp())).getString()
                });
            }

            row.done();
        }
        return b.build();
    }

    // ==================== 特殊值展示 ====================

    /**
     * 将标志位转换为简写字符显示。每个置位的标志用一个单字符表示。
     * 如果 flags 为 0，返回空字符串。
     */
    public static String formatFlagsShort(int flags) {
        if (flags == 0) return "";
        StringBuilder sb = new StringBuilder();
        if ((flags & QShopAnalyzer.FLAG_ID_RECOVERED) != 0) {
            sb.append("R");
        }
        if ((flags & QShopAnalyzer.FLAG_ENHANCED_DATA) != 0) {
            sb.append("E");
        }
        if ((flags & QShopAnalyzer.FLAG_SHULKER_EXPANDED) != 0) {
            sb.append("S");
        }
        if ((flags & QShopAnalyzer.FLAG_BOOK) != 0) {
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
        List<Text> lines = new ArrayList<>();
        if ((flags & QShopAnalyzer.FLAG_ID_RECOVERED) != 0) {
            lines.add(Text.translatable("chunkscanner.qshop.flag.id_recovered"));
        }
        if ((flags & QShopAnalyzer.FLAG_ENHANCED_DATA) != 0) {
            lines.add(Text.translatable("chunkscanner.qshop.flag.enhanced"));
        }
        if ((flags & QShopAnalyzer.FLAG_SHULKER_EXPANDED) != 0) {
            lines.add(Text.translatable("chunkscanner.qshop.flag.shulker_expanded"));
        }
        if ((flags & QShopAnalyzer.FLAG_BOOK) != 0) {
            lines.add(Text.translatable("chunkscanner.qshop.flag.book"));
        }
        return lines.isEmpty() ? null : lines;
    }

    /**
     * 从 detailNbtString 解析 ItemStack，解析失败返回 null。
     * 供 buildDetailTooltip 和 getLayout() 复用。
     */
    private static ItemStack parseDetailItemStack(QShopDbAdapter.Record record) {
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
     *
     * <p>从 detailNbtString（完整 ItemStack NBT JSON）重建 ItemStack，
     * 调用原版 {@code getTooltip()} 获取物品描述文本。</p>
     */
    private static List<Text> buildDetailTooltip(QShopDbAdapter.Record record) {
        ItemStack stack = parseDetailItemStack(record);
        if (stack == null) return null;

        net.minecraft.client.MinecraftClient client =
                net.minecraft.client.MinecraftClient.getInstance();
        if (client.player == null) return null;
        try {
            // 使用 Screen.getTooltipFromItem 保持与物品栏一致的 tooltip 行为：
            // 自动根据 F3+H 切换 BASIC/ADVANCED，且通过 Fabric Mixin 触发
            // ItemTooltipCallback.EVENT，mod 追加的 tooltip 行也会包含在内
            return net.minecraft.client.gui.screen.Screen.getTooltipFromItem(client, stack);
        } catch (Exception e) {
            return List.of(Text.literal(record.itemId()));
        }
    }

    /**
     * 构建 S 标志（潜影盒展开）的单价 tooltip。
     *
     * <p>单价 = 商店价格 / 满箱物品数 = 商店价格 / (27 × 物品堆叠上限)。
     * 显示格式："单价约 X.XX"。</p>
     */
    private static List<Text> buildShulkerUnitPriceTooltip(QShopDbAdapter.Record record) {
        try {
            int priceCents = record.price();

            // 获取物品堆叠上限
            int maxStack;
            if (record.itemId() != null && !record.itemId().isEmpty()) {
                Identifier itemId = Identifier.tryParse(record.itemId());
                if (itemId != null) {
                    net.minecraft.item.Item item = Registries.ITEM.get(itemId);
                    maxStack = item.getMaxCount();
                } else {
                    maxStack = 64;
                }
            } else {
                maxStack = 64;
            }

            int totalCount = SHULKER_SLOTS * maxStack;
            double unitPrice = (double) priceCents / totalCount / 100.0;

            return List.of(Text.translatable("chunkscanner.qshop.shulker_unit_price",
                            String.format("%.2f", unitPrice))
                    .formatted(Formatting.LIGHT_PURPLE));
        } catch (Exception e) {
            return null;
        }
    }

    private static final int SHULKER_SLOTS = 27;

    /** 获取筛选并排序后的记录列表。 */
    private List<QShopDbAdapter.Record> getFilteredSortedRecords() {
        if (filteredCacheValid && cachedFilteredSorted != null) {
            return cachedFilteredSorted;
        }
        List<QShopDbAdapter.Record> records = new QShopDbAdapter(db).getAllRecords();
        List<QShopDbAdapter.Record> matched = new ArrayList<>();
        for (QShopDbAdapter.Record r : records) {
            if (matchesFilter(r)) {
                matched.add(r);
            }
        }
        if (sortMode != SORT_NONE && matched.size() > 1) {
            matched.sort(getSortComparator());
        }
        cachedFilteredSorted = matched;
        filteredCacheValid = true;
        return matched;
    }

    /**
     * 根据当前排序模式返回对应的比较器。
     */
    private Comparator<QShopDbAdapter.Record> getSortComparator() {
        return switch (sortMode) {
            case SORT_PRICE_ASC -> Comparator.comparingInt(QShopDbAdapter.Record::price);
            case SORT_PRICE_DESC -> (a, b) -> Integer.compare(b.price(), a.price());
            case SORT_QTY_ASC -> Comparator.comparingInt(QShopDbAdapter.Record::quantity);
            case SORT_QTY_DESC -> (a, b) -> Integer.compare(b.quantity(), a.quantity());
            default -> (a, b) -> 0;
        };
    }

    /**
     * 检查一条记录是否满足当前所有筛选条件。
     */
    private boolean matchesFilter(QShopDbAdapter.Record r) {
        // 模式筛选
        if (modeFilter == 1 && r.mode() != QShopAnalyzer.MODE_SELL) return false;
        if (modeFilter == 2 && r.mode() != QShopAnalyzer.MODE_BUY) return false;

        // 文本筛选（null/空串 = 不筛选，否则按指定模式匹配）
        if (!matchesPattern(r.dimId(), dimFilter, dimFilterMode, compiledDimPattern)) return false;
        if (!matchesPattern(r.owner(), ownerFilter, ownerFilterMode, compiledOwnerPattern)) return false;
        if (!matchesPattern(r.itemName(), itemFilter, itemFilterMode, compiledItemPattern)) return false;

        // 价格范围筛选
        if (priceMinFilter != null || priceMaxFilter != null) {
            int priceCents = r.price();
            if (priceMinFilter != null && priceCents < priceMinFilter) return false;
            if (priceMaxFilter != null && priceCents > priceMaxFilter) return false;
        }

        // 数量范围筛选
        if (qtyMinFilter != null && r.quantity() < qtyMinFilter) return false;
        if (qtyMaxFilter != null && r.quantity() > qtyMaxFilter) return false;

        return true;
    }

    /**
     * 根据匹配模式检查字段是否满足筛选条件。
     *
     * @param field           待检查的字段值
     * @param filter          筛选文本（null 或空串 = 不筛选，直接通过）
     * @param mode            匹配模式（PATTERN_CONTAINS/EXCLUDE/EXACT/REGEX）
     * @param compiledPattern 预编译的正则 Pattern（仅 REGEX 模式下非 null）
     * @return true 表示通过筛选
     */
    private boolean matchesPattern(String field, String filter, int mode, Pattern compiledPattern) {
        if (filter == null || filter.isEmpty()) return true;
        if (field == null) return false;
        return switch (mode) {
            case PATTERN_CONTAINS -> field.toLowerCase().contains(filter.toLowerCase());
            case PATTERN_EXCLUDE -> !field.toLowerCase().contains(filter.toLowerCase());
            case PATTERN_EXACT -> field.equalsIgnoreCase(filter);
            case PATTERN_REGEX -> compiledPattern != null && compiledPattern.matcher(field).find();
            default -> true;
        };
    }

    /** 将价格整型（最小货币单位）格式化为显示字符串，如 50 → "0.50"。 */
    private static String formatPrice(int cents) {
        return String.format("%d.%02d", cents / 100, cents % 100);
    }

    private static ZoneId timezone = ZoneId.systemDefault();
    private static DateTimeFormatter timeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT);

    /** 将时间戳（毫秒）转化为时间字符串 */
    private static String formatTimestamp(long timestamp) {
        Instant instant = Instant.ofEpochMilli(timestamp);
        LocalDateTime dateTime = LocalDateTime.ofInstant(instant, timezone);
        return timeFormatter.format(dateTime);
    }


    // ==================== 类型描述符 ====================

    /** QShop 视图类型描述符：解析 QShop 数据为结构化展示。仅适用于 qshop 分析器。 */
    public static class Type implements DbViewProviderRegistry.Type {
        @Override
        public String getId() { return "qshop_view"; }

        @Override
        public String getName() {
            return Text.translatable("chunkscanner.dbview.qshop.name").getString();
        }

        @Override
        public String getDescription() {
            return Text.translatable("chunkscanner.dbview.qshop.desc").getString();
        }

        @Override
        public Set<String> applicableAnalyzers() {
            return Set.of("qshop");
        }

        @Override
        public DbViewProvider create(ChunkDb db) {
            if (!"qshop".equals(db.getAnalyzerName())) return null;
            return new QShopDbViewProvider(db);
        }
    }
}

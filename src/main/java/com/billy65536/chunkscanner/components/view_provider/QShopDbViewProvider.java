package com.billy65536.chunkscanner.components.view_provider;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.billy65536.chunkscanner.components.analyzer.QShopAnalyzer;
import com.billy65536.chunkscanner.components.analyzer.QShopDbAdapter;
import com.billy65536.chunkscanner.components.db.BinaryChunkDb;
import com.billy65536.chunkscanner.core.ChunkDb;
import com.billy65536.chunkscanner.core.DbViewProvider;
import com.billy65536.chunkscanner.core.LocatedPosition;
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

    private final BinaryChunkDb delegate;

    /** 缓存解析后的商店记录（全量，未筛选）。仅渲染线程访问，无需同步。 */
    private List<QShopRecord> cachedRecords;
    /** 缓存筛选并排序后的记录。仅渲染线程访问，无需同步。 */
    private List<QShopRecord> cachedFilteredSorted;
    /** 缓存的单元格物品图标映射。仅渲染线程访问，无需同步。 */
    private Map<Integer, Map<String, ItemStack>> cachedCellItems;
    private boolean cacheValid = false;
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

    public QShopDbViewProvider(BinaryChunkDb delegate) {
        this.delegate = delegate;
    }

    // ==================== 委托方法 ====================

    @Override public Path filePath() { return delegate.filePath(); }
    @Override public String analyzerName() { return delegate.analyzerName(); }
    @Override public String scanId() { return delegate.scanId(); }
    @Override public long fileSize() { return delegate.fileSize(); }
    @Override public long lastModified() { return delegate.lastModified(); }
    @Override public void open() { delegate.open(); }
    @Override public void close() { delegate.close(); }
    @Override public boolean isOpen() { return delegate.isOpen(); }

    @Override
    public int kvCount() {
        return getQShopRecords().size();
    }

    @Override
    public int chunkMetaCount() {
        return delegate.chunkMetaCount();
    }

    @Override
    public List<ChunkDb.Entry> getAllEntries() {
        return List.of();
    }

    @Override
    public List<ChunkDb.ChunkMeta> getAllChunkMetas() {
        return delegate.getAllChunkMetas();
    }

    @Override
    public boolean isSpecialized() {
        return true;
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
        cacheValid = false;
        filteredCacheValid = false;
        cachedCellItems = null;
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

    // ==================== 特化展示 ====================

    @Override
    public String[] getSpecializedHeaders() {
        return new String[]{"位置", "Owner", "Type", "Qty", "Item", "Price", "Item ID", "Detail", "Flags"};
    }

    @Override
    public List<String[]> getSpecializedRows() {
        List<QShopRecord> matched = getFilteredSortedRecords();
        List<String[]> rows = new ArrayList<>(matched.size());
        for (QShopRecord r : matched) {
            rows.add(buildRowData(r));
        }
        return rows;
    }

    /** 将一条 QShopRecord 转换为展示用的字符串数组。 */
    private String[] buildRowData(QShopRecord r) {
        String modeStr;
        String quantityStr;
        if (r.mode() == QShopAnalyzer.MODE_SELL) {
            modeStr = Text.translatable("chunkscanner.filter.mode.sell").getString();
            if (r.quantity() == QShopAnalyzer.INFINITE_QUANTITY) {
                quantityStr = Text.translatable("chunkscanner.qshop.infinite").getString();
            } else if (r.quantity() == 0) {
                quantityStr = Text.translatable("chunkscanner.qshop.out_of_stock").getString();
            } else {
                quantityStr = String.valueOf(r.quantity());
            }
        } else {
            modeStr = Text.translatable("chunkscanner.filter.mode.buy").getString();
            if (r.quantity() == QShopAnalyzer.INFINITE_QUANTITY) {
                quantityStr = Text.translatable("chunkscanner.qshop.infinite").getString();
            } else if (r.quantity() == 0) {
                quantityStr = Text.translatable("chunkscanner.qshop.out_of_space").getString();
            } else {
                quantityStr = String.valueOf(r.quantity());
            }
        }

        String posStr = new LocatedPosition(r.dimId(), r.x(), r.y(), r.z()).toString();
        // Detail 列：有增强数据时显示 "ⓘ"（导出可识别），GUI 中图标由 getCellItems() 提供
        String detailStr = (r.detailNbtString() != null && !r.detailNbtString().isEmpty()) ? "ⓘ" : "";

        return new String[] {
                posStr,
                r.owner(),
                modeStr,
                quantityStr,
                r.itemName(),
                formatPrice(r.price()),
                r.itemId(),
                detailStr,
                formatFlagsShort(r.flags())
        };
    }

    @Override
    public String[] getRowAt(int rowIndex) {
        List<QShopRecord> matched = getFilteredSortedRecords();
        if (rowIndex < 0 || rowIndex >= matched.size()) return null;
        QShopRecord r = matched.get(rowIndex);
        return buildRowData(r);
    }

    @Override
    public LocatedPosition getPositionAt(int rowIndex) {
        List<QShopRecord> matched = getFilteredSortedRecords();
        if (rowIndex < 0 || rowIndex >= matched.size()) return null;
        QShopRecord r = matched.get(rowIndex);
        return new LocatedPosition(r.dimId(), r.x(), r.y(), r.z());
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

    @Override
    public Map<Integer, Map<String, List<Text>>> getSpecializedCellTooltips() {
        List<QShopRecord> matched = getFilteredSortedRecords();
        Map<Integer, Map<String, List<Text>>> result = new HashMap<>();
        for (int i = 0; i < matched.size(); i++) {
            QShopRecord r = matched.get(i);
            Map<String, List<Text>> rowTips = null;

            // Flags 列 tooltip
            List<Text> flagTips = formatFlagsTooltip(r.flags());
            if (flagTips != null) {
                if (rowTips == null) rowTips = new HashMap<>();
                rowTips.put("Flags", flagTips);
            }

            // Detail 列 tooltip（物品悬停预览）
            List<Text> detailTips = buildDetailTooltip(r);
            if (detailTips != null) {
                if (rowTips == null) rowTips = new HashMap<>();
                rowTips.put("Detail", detailTips);
            }

            // Price 列 tooltip（S 标志：显示单价）
            if ((r.flags() & QShopAnalyzer.FLAG_SHULKER_EXPANDED) != 0) {
                List<Text> priceTips = buildShulkerUnitPriceTooltip(r);
                if (priceTips != null) {
                    if (rowTips == null) rowTips = new HashMap<>();
                    rowTips.put("Price", priceTips);
                }
            }

            if (rowTips != null) {
                result.put(i, rowTips);
            }
        }
        return result;
    }

    /**
     * 从 detailNbtString 解析 ItemStack，解析失败返回 null。
     * 供 buildDetailTooltip 和 getCellItems 复用。
     */
    private static ItemStack parseDetailItemStack(QShopRecord record) {
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
    private static List<Text> buildDetailTooltip(QShopRecord record) {
        ItemStack stack = parseDetailItemStack(record);
        if (stack == null) return null;

        net.minecraft.client.MinecraftClient client =
                net.minecraft.client.MinecraftClient.getInstance();
        if (client.player == null) return null;
        try {
            return stack.getTooltip(client.player,
                    net.minecraft.client.item.TooltipContext.Default.BASIC);
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
    private static List<Text> buildShulkerUnitPriceTooltip(QShopRecord record) {
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

    @Override
    public Map<Integer, Map<String, Integer>> getSpecializedCellColors() {
        List<QShopRecord> matched = getFilteredSortedRecords();
        Map<Integer, Map<String, Integer>> result = new HashMap<>();
        for (int i = 0; i < matched.size(); i++) {
            QShopRecord r = matched.get(i);
            if ((r.flags() & QShopAnalyzer.FLAG_SHULKER_EXPANDED) != 0) {
                Map<String, Integer> rowColors = new HashMap<>();
                rowColors.put("Price", 0xFFFF55FF); // 品红色
                result.put(i, rowColors);
            }
        }
        return result;
    }

    /**
     * 获取 Detail 列的物品图标映射，用于在表格中渲染 JEI 风格物品图标。
     *
     * <p>返回 Map&lt;行索引, Map&lt;"Detail", ItemStack&gt;&gt;。仅对有增强数据
     * （detailNbtString 非空）的记录提供图标。结果会被缓存。</p>
     *
     * @return 物品图标映射
     */
    public Map<Integer, Map<String, ItemStack>> getCellItems() {
        if (cachedCellItems != null) return cachedCellItems;
        List<QShopRecord> matched = getFilteredSortedRecords();
        Map<Integer, Map<String, ItemStack>> result = new HashMap<>();
        for (int i = 0; i < matched.size(); i++) {
            QShopRecord r = matched.get(i);
            ItemStack stack = parseDetailItemStack(r);
            if (stack != null) {
                Map<String, ItemStack> rowItems = new HashMap<>();
                rowItems.put("Detail", stack);
                result.put(i, rowItems);
            }
        }
        cachedCellItems = result;
        return result;
    }

    /** 获取筛选并排序后的记录列表。与 getSpecializedRows 返回顺序一致。 */
    private List<QShopRecord> getFilteredSortedRecords() {
        if (filteredCacheValid && cachedFilteredSorted != null) {
            return cachedFilteredSorted;
        }
        List<QShopRecord> records = getQShopRecords();
        List<QShopRecord> matched = new ArrayList<>();
        for (QShopRecord r : records) {
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
    private Comparator<QShopRecord> getSortComparator() {
        return switch (sortMode) {
            case SORT_PRICE_ASC -> Comparator.comparingInt(QShopRecord::price);
            case SORT_PRICE_DESC -> (a, b) -> Integer.compare(b.price(), a.price());
            case SORT_QTY_ASC -> Comparator.comparingInt(QShopRecord::quantity);
            case SORT_QTY_DESC -> (a, b) -> Integer.compare(b.quantity(), a.quantity());
            default -> (a, b) -> 0;
        };
    }

    /**
     * 检查一条记录是否满足当前所有筛选条件。
     */
    private boolean matchesFilter(QShopRecord r) {
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

    // ==================== QShop 特化展示 ====================

    /**
     * 解析 qshop KV 条目为可读格式。
     *
     * 反序列化流程：
     * 1. 从 delegate 获取所有原始 KV 条目
     * 2. 解析值中的 dimPoolId → 通过字符串池 lookup 还原维度 ID
     * 3. 解析坐标、所有者、模式、数量、商品名、价格
     *
     * 结果会被缓存（cacheValid），数据不变时不会重复解析。
     */
    public List<QShopRecord> getQShopRecords() {
        if (cacheValid && cachedRecords != null) {
            return cachedRecords;
        }

        QShopDbAdapter adapter = new QShopDbAdapter(delegate);
        List<QShopDbAdapter.Record> adapterRecords = adapter.getAllRecords();

        List<QShopRecord> records = new ArrayList<>(adapterRecords.size());
        for (QShopDbAdapter.Record r : adapterRecords) {
            records.add(new QShopRecord(r.dimId(), r.x(), r.y(), r.z(),
                    r.owner(), r.mode(), r.quantity(),
                    r.itemName(), r.price(), r.timestamp(),
                    r.itemId(), r.flags(), r.detailNbtString()));
        }

        cachedRecords = records;
        cacheValid = true;
        filteredCacheValid = false;
        return records;
    }

    /**
     * QShop 商店记录。
     *
     * @param dimId    维度 ID
     * @param x,y,z    告示牌坐标
     * @param owner    商店所有者名称
     * @param mode     0=出售, 1=收购
     * @param quantity 剩余数量（出售 mode quantity=0 表示缺货，收购 mode quantity=0 表示空间不足）
     * @param itemName 商品名称（告示牌原文）
     * @param price    单价（最小货币单位整数，真实价格 = price / 100.0）
     * @param timestamp 扫描时间戳
     * @param itemId   商品注册名（如 "minecraft:diamond"），恢复失败为空串
     * @param flags    特殊值标志位（参见 {@link QShopAnalyzer#FLAG_ENHANCED_DATA} 等）
     * @param detailNbtString 物品完整 NBT 的 JSON 字符串（用于 Detail 列悬停展示，null 表示未增强）
     */
    public record QShopRecord(String dimId, int x, int y, int z,
                               String owner, byte mode, int quantity,
                               String itemName, int price, long timestamp,
                               String itemId, int flags,
                               String detailNbtString) {}

    // ==================== DbViewProvider.Type ====================

    /** QShop 视图类型描述符：解析 QShop 数据为结构化展示。仅适用于 qshop 分析器。 */
    public static class DbViewProviderType implements DbViewProvider.Type {
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
            if (!(db instanceof BinaryChunkDb bdb)) return null;
            if (!"qshop".equals(bdb.analyzerName())) return null;
            return new QShopDbViewProvider(bdb);
        }
    }
}

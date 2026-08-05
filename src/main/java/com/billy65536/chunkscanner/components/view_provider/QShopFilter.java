package com.billy65536.chunkscanner.components.view_provider;

import com.billy65536.chunkscanner.components.analyzer.QShopContract;
import com.billy65536.chunkscanner.components.analyzer.QShopDbAdapter;

import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * QShop 筛选状态与匹配逻辑。
 *
 */
public final class QShopFilter {

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
    private int cacheVersion = 0;

    /** 模式筛选：0=全部, 1=出售(MODE_SELL), 2=收购(MODE_BUY) */
    private int modeFilter = 0;
    private String dimFilter = null;
    private String ownerFilter = null;
    private String itemFilter = null;
    private String itemIdFilter = null;
    private String flagsFilter = null;

    /** 各文本筛选字段的匹配模式。 */
    private int dimFilterMode = PATTERN_CONTAINS;
    private int ownerFilterMode = PATTERN_CONTAINS;
    private int itemFilterMode = PATTERN_CONTAINS;
    private int itemIdFilterMode = PATTERN_CONTAINS;
    private int flagsFilterMode = PATTERN_CONTAINS;

    /** 预编译的正则 Pattern 缓存（仅在 PATTERN_REGEX 模式下非 null）。 */
    private Pattern compiledDimPattern = null;
    private Pattern compiledOwnerPattern = null;
    private Pattern compiledItemPattern = null;
    private Pattern compiledItemIdPattern = null;

    /** 价格范围筛选（null = 不限制）。内部以货币最小单位存储（乘以 100）。 */
    private Integer priceMinFilter = null;
    private Integer priceMaxFilter = null;

    /** 数量范围筛选（null = 不限制）。 */
    private Integer qtyMinFilter = null;
    private Integer qtyMaxFilter = null;

    /** 排序模式。 */
    private int sortMode = SORT_NONE;

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

    public String getItemIdFilter() { return itemIdFilter; }
    public void setItemIdFilter(String v) { itemIdFilter = v; }
    public int getItemIdFilterMode() { return itemIdFilterMode; }
    public void setItemIdFilterMode(int v) { itemIdFilterMode = v; }

    public String getFlagsFilter() { return flagsFilter; }
    public void setFlagsFilter(String v) { flagsFilter = v; }
    public int getFlagsFilterMode() { return flagsFilterMode; }
    public void setFlagsFilterMode(int v) { flagsFilterMode = Math.max(0, Math.min(2, v)); }

    /** 筛选条件是否处于激活状态。 */
    public boolean isFilterActive() {
        return modeFilter != 0 || dimFilter != null || ownerFilter != null
                || itemFilter != null || itemIdFilter != null || flagsFilter != null
                || sortMode != SORT_NONE
                || priceMinFilter != null || priceMaxFilter != null
                || qtyMinFilter != null || qtyMaxFilter != null;
    }

    public int getCacheVersion() {
        return cacheVersion;
    }

    /** 筛选条件变更后使缓存失效，并预编译正则 Pattern。 */
    public void invalidateCache() {
        compiledDimPattern = compileIfNeeded(dimFilter, dimFilterMode);
        compiledOwnerPattern = compileIfNeeded(ownerFilter, ownerFilterMode);
        compiledItemPattern = compileIfNeeded(itemFilter, itemFilterMode);
        compiledItemIdPattern = compileIfNeeded(itemIdFilter, itemIdFilterMode);
        cacheVersion++;
    }

    private static Pattern compileIfNeeded(String filter, int mode) {
        if (mode != PATTERN_REGEX || filter == null || filter.isEmpty()) return null;
        try {
            return Pattern.compile(filter, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    // ==================== 匹配与排序 ====================

    /**
     * 检查一条记录是否满足当前所有筛选条件。
     */
    public boolean matches(QShopDbAdapter.Record r) {
        // 模式筛选
        if (modeFilter == 1 && r.mode() != QShopContract.MODE_SELL) return false;
        if (modeFilter == 2 && r.mode() != QShopContract.MODE_BUY) return false;

        // 文本筛选（null/空串 = 不筛选，否则按指定模式匹配）
        if (!matchesPattern(r.dimId(), dimFilter, dimFilterMode, compiledDimPattern)) return false;
        if (!matchesPattern(r.owner(), ownerFilter, ownerFilterMode, compiledOwnerPattern)) return false;
        if (!matchesPattern(r.itemName(), itemFilter, itemFilterMode, compiledItemPattern)) return false;
        if (!matchesPattern(r.itemId(), itemIdFilter, itemIdFilterMode, compiledItemIdPattern)) return false;

        // flags 筛选
        if (!matchesFlags(r.flags(), flagsFilter, flagsFilterMode)) return false;

        // 价格范围筛选
        if (priceMinFilter != null || priceMaxFilter != null) {
            int priceCents = r.price();
            if (priceMinFilter != null && priceCents < priceMinFilter) return false;
            if (priceMaxFilter != null && priceCents > priceMaxFilter) return false;
        }

        // 数量范围筛选（潜影盒条目按有效数量比较）
        if (qtyMinFilter != null || qtyMaxFilter != null) {
            int effectiveQty = getEffectiveQty(r);
            if (qtyMinFilter != null && effectiveQty < qtyMinFilter) return false;
            if (qtyMaxFilter != null && effectiveQty > qtyMaxFilter) return false;
        }

        return true;
    }

    /**
     * 根据匹配模式检查字段是否满足筛选条件。
     */
    private boolean matchesPattern(String field, String filter, int mode, Pattern compiledPattern) {
        if (filter == null || filter.isEmpty()) return true;
        if (field == null) return false;
        // Locale.ROOT：土耳其语等 locale 下 'I'.toLowerCase() 会变成无点 'ı'，
        // 导致玩家用 "Iron" 搜不到 "iron"。
        return switch (mode) {
            case PATTERN_CONTAINS ->
                    field.toLowerCase(java.util.Locale.ROOT).contains(filter.toLowerCase(java.util.Locale.ROOT));
            case PATTERN_EXCLUDE ->
                    !field.toLowerCase(java.util.Locale.ROOT).contains(filter.toLowerCase(java.util.Locale.ROOT));
            case PATTERN_EXACT -> field.equalsIgnoreCase(filter);
            case PATTERN_REGEX -> compiledPattern != null && compiledPattern.matcher(field).find();
            default -> true;
        };
    }

    /**
     * 检查记录 flags 是否满足筛选条件。
     */
    static boolean matchesFlags(int recordFlags, String flagsFilter, int mode) {
        if (flagsFilter == null || flagsFilter.isEmpty()) return true;

        int mask = 0;
        for (int i = 0; i < flagsFilter.length(); i++) {
            mask |= switch (flagsFilter.charAt(i)) {
                case 'R', 'r' -> QShopContract.FLAG_ID_RECOVERED;
                case 'E', 'e' -> QShopContract.FLAG_ENHANCED_DATA;
                case 'S', 's' -> QShopContract.FLAG_SHULKER_EXPANDED;
                case 'B', 'b' -> QShopContract.FLAG_BOOK;
                default -> 0;
            };
        }
        if (mask == 0) return true;

        return switch (mode) {
            case PATTERN_CONTAINS -> (recordFlags & mask) != 0;
            case PATTERN_EXCLUDE -> (recordFlags & mask) == 0;
            case PATTERN_EXACT -> (recordFlags & mask) == mask;
            default -> true;
        };
    }

    /**
     * 根据当前排序模式返回对应的比较器。
     */
    public Comparator<QShopDbAdapter.Record> getSortComparator() {
        return switch (sortMode) {
            case SORT_PRICE_ASC -> Comparator.comparingInt(QShopDbAdapter.Record::price);
            case SORT_PRICE_DESC -> (a, b) -> Integer.compare(b.price(), a.price());
            case SORT_QTY_ASC -> Comparator.comparingInt(this::getEffectiveQty);
            case SORT_QTY_DESC -> (a, b) -> Integer.compare(
                    getEffectiveQty((QShopDbAdapter.Record) b),
                    getEffectiveQty((QShopDbAdapter.Record) a));
            default -> (a, b) -> 0;
        };
    }

    /**
     * 获取排序/筛选时使用的"有效数量"。
     * 潜影盒条目返回箱内物品总数；普通条目返回原始数量。
     */
    /** 获取潜影盒条目的有效数量（供排序/数量筛选），委托 QShopDisplayUtil 统一实现。 */
    int getEffectiveQty(QShopDbAdapter.Record r) {
        return QShopDisplayUtil.getEffectiveQty(r);
    }
}

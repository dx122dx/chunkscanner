package com.billy65536.chunkscanner.components.view_provider;

import com.billy65536.chunkscanner.components.analyzer.QShopContract;
import com.billy65536.chunkscanner.components.analyzer.QShopDbAdapter;

import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * QShop 筛选状态与匹配逻辑。
 *
 * <p>筛选状态现持有于 {@link QShopFilterConfig}：文本维度为 {@link FilterValue}（值 + 匹配模式），
 * 数值维度（mode/pmin/pmax/qmin/qmax/sort）为 {@link Integer}。本类的 public getter/setter
 * 全部桥接到底层配置，签名保持不变，GUI（QShopFilterScreen）无需改动。</p>
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

    /** 底层扁平筛选配置（每个维度为 FilterValue）。 */
    private final QShopFilterConfig cfg;

    private int cacheVersion = 0;

    /** 预编译的正则 Pattern 缓存（仅在 PATTERN_REGEX 模式下非 null）。 */
    private Pattern compiledDimPattern = null;
    private Pattern compiledOwnerPattern = null;
    private Pattern compiledItemPattern = null;
    private Pattern compiledItemIdPattern = null;

    // ==================== 构造函数 ====================

    public QShopFilter() { this(null); }

    public QShopFilter (QShopFilterConfig cfg) {
        this.cfg = cfg == null? new QShopFilterConfig() : cfg;
    }

    // ==================== 筛选字段存取（桥接 cfg） ====================

    public int getModeFilter() {
        return cfg.modeFilter != null ? cfg.modeFilter : 0;
    }

    public void setModeFilter(int v) {
        cfg.modeFilter = v;
    }

    public String getDimFilter() {
        return cfg.dimFilter != null ? cfg.dimFilter.getValue() : null;
    }

    public void setDimFilter(String v) {
        cfg.dimFilter = withValue(cfg.dimFilter, v);
    }

    public int getDimFilterMode() {
        return modeOf(cfg.dimFilter);
    }

    public void setDimFilterMode(int v) {
        cfg.dimFilter = withType(cfg.dimFilter, v);
    }

    public String getOwnerFilter() {
        return cfg.ownerFilter != null ? cfg.ownerFilter.getValue() : null;
    }

    public void setOwnerFilter(String v) {
        cfg.ownerFilter = withValue(cfg.ownerFilter, v);
    }

    public int getOwnerFilterMode() {
        return modeOf(cfg.ownerFilter);
    }

    public void setOwnerFilterMode(int v) {
        cfg.ownerFilter = withType(cfg.ownerFilter, v);
    }

    public String getItemFilter() {
        return cfg.itemFilter != null ? cfg.itemFilter.getValue() : null;
    }

    public void setItemFilter(String v) {
        cfg.itemFilter = withValue(cfg.itemFilter, v);
    }

    public int getItemFilterMode() {
        return modeOf(cfg.itemFilter);
    }

    public void setItemFilterMode(int v) {
        cfg.itemFilter = withType(cfg.itemFilter, v);
    }

    public Integer getPriceMinFilter() {
        return cfg.priceMinFilter;
    }

    public void setPriceMinFilter(Integer v) {
        cfg.priceMinFilter = v;
    }

    public Integer getPriceMaxFilter() {
        return cfg.priceMaxFilter;
    }

    public void setPriceMaxFilter(Integer v) {
        cfg.priceMaxFilter = v;
    }

    public Integer getQtyMinFilter() {
        return cfg.qtyMinFilter;
    }

    public void setQtyMinFilter(Integer v) {
        cfg.qtyMinFilter = v;
    }

    public Integer getQtyMaxFilter() {
        return cfg.qtyMaxFilter;
    }

    public void setQtyMaxFilter(Integer v) {
        cfg.qtyMaxFilter = v;
    }

    public int getSortMode() {
        return cfg.sortMode != null ? cfg.sortMode : SORT_NONE;
    }

    public void setSortMode(int v) {
        cfg.sortMode = v;
    }

    public String getItemIdFilter() {
        return cfg.itemIdFilter != null ? cfg.itemIdFilter.getValue() : null;
    }

    public void setItemIdFilter(String v) {
        cfg.itemIdFilter = withValue(cfg.itemIdFilter, v);
    }

    public int getItemIdFilterMode() {
        return modeOf(cfg.itemIdFilter);
    }

    public void setItemIdFilterMode(int v) {
        cfg.itemIdFilter = withType(cfg.itemIdFilter, v);
    }

    public String getFlagsFilter() {
        return cfg.flagsFilter != null ? cfg.flagsFilter.getValue() : null;
    }

    public void setFlagsFilter(String v) {
        cfg.flagsFilter = withValue(cfg.flagsFilter, v);
    }

    public int getFlagsFilterMode() {
        return modeOf(cfg.flagsFilter);
    }

    public void setFlagsFilterMode(int v) {
        cfg.flagsFilter = withType(cfg.flagsFilter, v);
    }

    // ==================== 桥接辅助 ====================

    /** 保留既有 type（无则默认 CONTAINS），换 value。 */
    private static FilterValue withValue(FilterValue old, String value) {
        int type = old != null ? old.getType() : PATTERN_CONTAINS;
        return new FilterValue(value, type);
    }

    /** 保留既有 value，换 type。 */
    private static FilterValue withType(FilterValue old, int type) {
        String value = old != null ? old.getValue() : null;
        return new FilterValue(value, type);
    }

    /** 取匹配模式（null 视为 CONTAINS）。 */
    private static int modeOf(FilterValue f) {
        return f != null ? f.getType() : PATTERN_CONTAINS;
    }

    // ==================== 状态查询 ====================

    /** 筛选条件是否处于激活状态。 */
    public boolean isFilterActive() {
        return getModeFilter() != 0
                || !isEmpty(cfg.dimFilter) || !isEmpty(cfg.ownerFilter)
                || !isEmpty(cfg.itemFilter) || !isEmpty(cfg.itemIdFilter) || !isEmpty(cfg.flagsFilter)
                || getSortMode() != SORT_NONE
                || getPriceMinFilter() != null || getPriceMaxFilter() != null
                || getQtyMinFilter() != null || getQtyMaxFilter() != null;
    }

    private static boolean isEmpty(FilterValue f) {
        return f == null || f.isEmpty();
    }

    public int getCacheVersion() {
        return cacheVersion;
    }

    /** 筛选条件变更后使缓存失效，并预编译正则 Pattern。 */
    public void invalidateCache() {
        compiledDimPattern = compileIfNeeded(getDimFilter(), getDimFilterMode());
        compiledOwnerPattern = compileIfNeeded(getOwnerFilter(), getOwnerFilterMode());
        compiledItemPattern = compileIfNeeded(getItemFilter(), getItemFilterMode());
        compiledItemIdPattern = compileIfNeeded(getItemIdFilter(), getItemIdFilterMode());
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
        if (getModeFilter() == 1 && r.mode() != QShopContract.MODE_SELL) return false;
        if (getModeFilter() == 2 && r.mode() != QShopContract.MODE_BUY) return false;

        // 文本筛选（null/空串 = 不筛选，否则按指定模式匹配）
        if (!matchesPattern(r.dimId(), getDimFilter(), getDimFilterMode(), compiledDimPattern)) return false;
        if (!matchesPattern(r.owner(), getOwnerFilter(), getOwnerFilterMode(), compiledOwnerPattern)) return false;
        if (!matchesPattern(r.itemName(), getItemFilter(), getItemFilterMode(), compiledItemPattern)) return false;
        if (!matchesPattern(r.itemId(), getItemIdFilter(), getItemIdFilterMode(), compiledItemIdPattern)) return false;

        // flags 筛选
        if (!matchesFlags(r.flags(), getFlagsFilter(), getFlagsFilterMode())) return false;

        // 价格范围筛选
        if (getPriceMinFilter() != null || getPriceMaxFilter() != null) {
            int priceCents = r.price();
            if (getPriceMinFilter() != null && priceCents < getPriceMinFilter()) return false;
            if (getPriceMaxFilter() != null && priceCents > getPriceMaxFilter()) return false;
        }

        // 数量范围筛选（潜影盒条目按有效数量比较）
        if (getQtyMinFilter() != null || getQtyMaxFilter() != null) {
            int effectiveQty = getEffectiveQty(r);
            if (getQtyMinFilter() != null && effectiveQty < getQtyMinFilter()) return false;
            if (getQtyMaxFilter() != null && effectiveQty > getQtyMaxFilter()) return false;
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
        return switch (getSortMode()) {
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
    int getEffectiveQty(QShopDbAdapter.Record r) {
        return QShopDisplayUtil.getEffectiveQty(r);
    }
}

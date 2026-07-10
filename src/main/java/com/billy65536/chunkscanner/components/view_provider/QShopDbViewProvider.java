package com.billy65536.chunkscanner.components.view_provider;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.components.analyzer.QShopAnalyzer;
import com.billy65536.chunkscanner.components.db.BinaryChunkDb;
import com.billy65536.chunkscanner.core.ChunkDb;
import com.billy65536.chunkscanner.core.DbViewProvider;
import com.billy65536.chunkscanner.core.LocatedPosition;
import com.billy65536.chunkscanner.gui.GuiUtil;
/**
 * QShop 分析器特化的 DbViewProvider。
 *
 * 解析 qshop 分析器生成的二进制 KV 数据，将原始字节转换为可读的商店信息。
 *
 * 键格式（34 字节）：
 *   "qshop:" (6B) | dimPoolId:u32 (4B) | cx:i32 (4B) | cz:i32 (4B) | keyHi:u64 (8B) | keyLo:u64 (8B)
 *
 * 值格式（40 字节）：
 *   keyHi:u64 (8B) | keyLo:u64 (8B) | owner:u32 (4B) | mode+quantity packed:u32 (4B) |
 *   itemName:u32 (4B) | price:u32 (4B) | timestamp:u64 (8B)
 *
 *   mode+quantity 打包：byte0 = mode (0=出售,1=收购), bytes1-3 = quantity (24-bit unsigned)
 */
public class QShopDbViewProvider implements DbViewProvider {

    private static final byte[] KEY_PREFIX = "qshop:".getBytes(StandardCharsets.UTF_8);
    private static final int RECORD_SIZE = 40;

    private final BinaryChunkDb delegate;

    /** 缓存解析后的商店记录（全量，未筛选）。 */
    private List<QShopRecord> cachedRecords;
    private volatile boolean cacheValid = false;

    // ==================== 排序常量 ====================

    public static final int SORT_NONE = 0;
    public static final int SORT_PRICE_ASC = 1;
    public static final int SORT_PRICE_DESC = 2;
    public static final int SORT_QTY_ASC = 3;
    public static final int SORT_QTY_DESC = 4;

    // ==================== 筛选状态 ====================

    /** 模式筛选：0=全部, 1=出售(MODE_SELL), 2=收购(MODE_BUY) */
    private int modeFilter = 0;
    private String dimFilter = null;
    private String ownerFilter = null;
    private String itemFilter = null;

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
    public String getOwnerFilter() { return ownerFilter; }
    public void setOwnerFilter(String v) { ownerFilter = v; }
    public String getItemFilter() { return itemFilter; }
    public void setItemFilter(String v) { itemFilter = v; }

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

    /** 筛选条件变更后使缓存失效。 */
    public void invalidateCache() { cacheValid = false; }

    // ==================== 特化展示 ====================

    @Override
    public String[] getSpecializedHeaders() {
        return new String[]{"位置", "Owner", "Type", "Qty", "Item", "Price"};
    }

    @Override
    public List<String[]> getSpecializedRows() {
        List<QShopRecord> matched = getFilteredSortedRecords();

        List<String[]> rows = new ArrayList<>(matched.size());
        for (QShopRecord r : matched) {
            String modeStr;
            String quantityStr;
            if (r.mode() == QShopAnalyzer.MODE_SELL) {
                modeStr = "出售";
                if (r.quantity() == QShopAnalyzer.INFINITE_QUANTITY) {
                    quantityStr = "无限";
                } else if (r.quantity() == 0) {
                    quantityStr = "缺货";
                } else {
                    quantityStr = String.valueOf(r.quantity());
                }
            } else {
                modeStr = "收购";
                quantityStr = r.quantity() == QShopAnalyzer.INFINITE_QUANTITY
                        ? "无限" : String.valueOf(r.quantity());
            }

            String posStr = new LocatedPosition(r.dimId(), r.x(), r.y(), r.z()).toString();

            rows.add(new String[] {
                    posStr,
                    r.owner(),
                    modeStr,
                    quantityStr,
                    r.itemName(),
                    r.price()
            });
        }
        return rows;
    }

    @Override
    public LocatedPosition getPositionAt(int rowIndex) {
        List<QShopRecord> matched = getFilteredSortedRecords();
        if (rowIndex < 0 || rowIndex >= matched.size()) return null;
        QShopRecord r = matched.get(rowIndex);
        return new LocatedPosition(r.dimId(), r.x(), r.y(), r.z());
    }

    /** 获取筛选并排序后的记录列表。与 getSpecializedRows 返回顺序一致。 */
    private List<QShopRecord> getFilteredSortedRecords() {
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
        return matched;
    }

    /**
     * 根据当前排序模式返回对应的比较器。
     */
    private Comparator<QShopRecord> getSortComparator() {
        return switch (sortMode) {
            case SORT_PRICE_ASC -> Comparator.comparingInt(r -> parseNumericPrice(r.price()));
            case SORT_PRICE_DESC -> (a, b) -> Integer.compare(
                    parseNumericPrice(b.price()), parseNumericPrice(a.price()));
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

        // 文本子串匹配（null = 不筛选，空串 = 不筛选，其他 = 包含即可）
        if (dimFilter != null && !dimFilter.isEmpty()
                && !r.dimId().toLowerCase().contains(dimFilter.toLowerCase())) return false;
        if (ownerFilter != null && !ownerFilter.isEmpty()
                && !r.owner().toLowerCase().contains(ownerFilter.toLowerCase())) return false;
        if (itemFilter != null && !itemFilter.isEmpty()
                && !r.itemName().toLowerCase().contains(itemFilter.toLowerCase())) return false;

        // 价格范围筛选
        if (priceMinFilter != null || priceMaxFilter != null) {
            int priceNum = parseNumericPrice(r.price());
            // 无法解析价格的不纳入范围筛选结果
            if (priceMinFilter != null && priceNum < priceMinFilter) return false;
            if (priceMaxFilter != null && priceNum > priceMaxFilter) return false;
        }

        // 数量范围筛选
        if (qtyMinFilter != null && r.quantity() < qtyMinFilter) return false;
        if (qtyMaxFilter != null && r.quantity() > qtyMaxFilter) return false;

        return true;
    }

    /** 提取价格字符串中首个数字的整数表示（乘以 100，两位小数精度）。无法解析则返回 -1。 */
    private static final Pattern PRICE_NUM_PATTERN = Pattern.compile("(\\d+\\.?\\d*)");
    static int parseNumericPrice(String price) {
        if (price == null) return -1;
        Matcher m = PRICE_NUM_PATTERN.matcher(price.trim());
        if (m.find()) {
            try {
                return (int) (Double.parseDouble(m.group(1)) * 100.0);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return -1;
    }

    // ==================== QShop 特化展示 ====================

    /**
     * 解析 qshop KV 条目为可读格式。
     *
     * 反序列化流程：
     * 1. 从 delegate 获取所有原始 KV 条目
     * 2. 过滤出 key 以 "qshop:" 为前缀的条目
     * 3. 解析值中的 dimPoolId → 通过字符串池 lookup 还原维度 ID
     * 4. 解析坐标、所有者、模式、数量、商品名、价格
     *
     * 结果会被缓存（cacheValid），数据不变时不会重复解析。
     */
    public List<QShopRecord> getQShopRecords() {
        if (cacheValid && cachedRecords != null) {
            return cachedRecords;
        }

        List<QShopRecord> records = new ArrayList<>();
        List<ChunkDb.Entry> entries;
        try {
            entries = delegate.getAllEntries();
        } catch (Exception e) {
            ChunkScannerMod.LOGGER.warn("QShopDbViewProvider: failed to get entries: {}", e.getMessage());
            cachedRecords = records;
            cacheValid = true;
            return records;
        }

        for (ChunkDb.Entry entry : entries) {
            try {
                byte[] key = entry.key();
                // 跳过非 qshop 前缀的条目
                if (key.length < KEY_PREFIX.length) continue;
                if (!GuiUtil.startsWith(key, KEY_PREFIX)) continue;

                byte[] val = entry.value();
                if (val.length < RECORD_SIZE) continue;

                ByteBuffer vb = ByteBuffer.wrap(val).order(ByteOrder.LITTLE_ENDIAN);
                long keyHi = vb.getLong();
                long keyLo = vb.getLong();

                // 从 keyHi 提取 dimPoolId 并通过字符串池还原维度名
                int dimPoolId = (int) (keyHi >> 32);
                String dimId = delegate.lookup(dimPoolId);
                // keyHi 低 32 位 = X 坐标，keyLo 高 32 位 = Z，低 32 位 = Y
                int x = (int) (keyHi & 0xFFFFFFFFL);
                int z = (int) (keyLo >> 32);
                int y = (int) (keyLo & 0xFFFFFFFFL);

                int ownerId = vb.getInt();
                int modePacked = vb.getInt();   // [quantity(24bit) | mode(8bit)]
                int itemNameId = vb.getInt();
                int priceId = vb.getInt();
                long ts = vb.getLong();

                byte mode = (byte) (modePacked & 0xFF);
                int quantity = (modePacked >> 8) & 0xFFFFFF;

                // 通过字符串池还原可读文本
                String owner = delegate.lookup(ownerId);
                String itemName = delegate.lookup(itemNameId);
                String price = delegate.lookup(priceId);

                records.add(new QShopRecord(dimId, x, y, z, owner, mode, quantity, itemName, price, ts));
            } catch (Exception e) {
                ChunkScannerMod.LOGGER.warn("QShopDbViewProvider: failed to parse entry: {}", e.getMessage());
            }
        }

        cachedRecords = records;
        cacheValid = true;
        return records;
    }

    /**
     * QShop 商店记录。
     *
     * @param dimId    维度 ID
     * @param x,y,z    告示牌坐标
     * @param owner    商店所有者名称
     * @param mode     0=出售, 1=收购
     * @param quantity 剩余数量（出售模式 quantity=0 表示缺货）
     * @param itemName 商品名称
     * @param price    单价文本（含货币符号）
     * @param timestamp 扫描时间戳
     */
    public record QShopRecord(String dimId, int x, int y, int z,
                               String owner, byte mode, int quantity,
                               String itemName, String price, long timestamp) {}

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
        public DbViewProvider create(BinaryChunkDb db) {
            if (!"qshop".equals(db.analyzerName())) return null;
            return new QShopDbViewProvider(db);
        }
    }
}

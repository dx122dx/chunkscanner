package com.billy65536.chunkscanner.components.analyzer;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.inventory.Inventory;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.AnalyzeResult;
import com.billy65536.chunkscanner.core.ChunkAnalyzer;
import com.billy65536.chunkscanner.core.ChunkDb;

/**
 * QShop 商店分析器：扫描贴在容器上的特化告示牌，识别 QShop 格式商店。
 *
 * 告示牌必须贴靠在箱子、木桶等具有物品栏的容器方块上，只有正面有文字。
 * 解析所用的正则模式和关键词完全由配置文件驱动，支持任意语言的 QShop 告示牌格式。
 *
 * <h3>默认中文格式</h3>
 * <pre>
 *   第一行：玩家名称或"系统商店"（商店所有者）
 *   第二行：出售/收购 + 空格 + 数量/无限  或  缺货/空间不足
 *   第三行：商品名称
 *   第四行：单价：数字 货币符号（如 "单价：0.50 ₦"）
 * </pre>
 *
 * 系统商店：数量为"无限"，用 quantity=0xFFFFFF 表示。
 * 价格以最小货币单位存储（整数，乘以 100）。
 *
 * 键格式（34 字节）：
 *   "qshop:" (6B) | dimPoolId:u32 (4B) | cx:i32 (4B) | cz:i32 (4B) | keyHi:u64 (8B) | keyLo:u64 (8B)
 *
 * 值格式（48 字节）：
 *   keyHi:u64 (8B) | keyLo:u64 (8B) | owner:u32 (4B) | mode+quantity packed:u32 (4B) |
 *   itemName:u32 (4B) | price:u32 (4B) | timestamp:u64 (8B) | itemId:u32 (4B) | flags:u32 (4B)
 *
 *   mode+quantity 打包：byte0 = mode (0=出售,1=收购), bytes1-3 = quantity (24-bit unsigned)
 *   price：整数，真实价格 = price / 100.0（如 50 = 0.50）
 *
 *   特殊值 flag bits:
 *     FLAG_ID_RECOVERED     (0x01) — 物品注册名通过译名映射表恢复（R）
 *     FLAG_ENHANCED_DATA     (0x02) — 此记录包含增强数据
 *     FLAG_SHULKER_EXPANDED  (0x04) — 潜影盒已展开（S），内容物作为商品
 *     FLAG_BOOK              (0x08) — 成书（B），商品名已替换为标题
 */
public class QShopAnalyzer implements ChunkAnalyzer {

    /** 值记录大小（48 字节）。 */
    public static final int RECORD_SIZE = 48;
    private static final byte[] KEY_PREFIX = "qshop:".getBytes(StandardCharsets.UTF_8);
    // "qshop:"(6) + dimPoolId(4) + cx(4) + cz(4) + keyHi(8) + keyLo(8)
    private static final int KEY_SIZE = KEY_PREFIX.length + 4 + 4 + 4 + 8 + 8; // 34
    private static final int CHUNK_PREFIX_LEN = KEY_PREFIX.length + 4 + 4 + 4; // 18

    /** 特殊值：物品注册名通过译名映射表恢复（R）。 */
    public static final int FLAG_ID_RECOVERED = 0x01;
    /** 特殊值：此记录包含增强数据存储在子数据库 1。 */
    public static final int FLAG_ENHANCED_DATA = 0x02;
    /** 特殊值：潜影盒已展开（S），内容物作为商品。 */
    public static final int FLAG_SHULKER_EXPANDED = 0x04;
    /** 特殊值：成书（B），商品名已替换为标题。 */
    public static final int FLAG_BOOK = 0x08;

    /** quantity 的最大值 (24-bit)，用作"无限"的哨兵值 */
    public static final int INFINITE_QUANTITY = 0xFFFFFF;

    public static final byte MODE_SELL = 0;
    public static final byte MODE_BUY  = 1;

    // ==================== 正则模式（由配置文件驱动） ====================

    /**
     * QuickShop 告示牌解析所需的正则模式。
     * 由 {@link #getPatterns()} 根据当前配置动态构建并缓存。
     */
    private record LocalePatterns(
            Pattern sellBuyPattern,
            Pattern infinitePattern,
            Pattern outOfStockPattern,
            Pattern outOfSpacePattern,
            Pattern pricePattern,
            String modeSell,
            String modeBuy
    ) {
        boolean isSell(String group1) {
            return modeSell.equalsIgnoreCase(group1);
        }
    }

    /** 缓存的编译后模式及其对应的配置哈希，配置变更时自动重建。 */
    private static volatile LocalePatterns cachedPatterns = null;
    private static volatile String cachedConfigHash = null;

    /** 从当前全局配置构建（或复用缓存）LocalePatterns。 */
    private static LocalePatterns getPatterns() {
        com.billy65536.chunkscanner.config.ChunkScannerConfig cfg = ChunkScannerMod.CONFIG;
        String hash = cfg.qshopSellBuyPattern + "\0"
                + cfg.qshopInfinitePattern + "\0"
                + cfg.qshopOutOfStockPattern + "\0"
                + cfg.qshopOutOfSpacePattern + "\0"
                + cfg.qshopPricePattern + "\0"
                + cfg.qshopSellKeyword + "\0"
                + cfg.qshopBuyKeyword;
        LocalePatterns p = cachedPatterns;
        if (p != null && hash.equals(cachedConfigHash)) {
            return p;
        }
        try {
            p = new LocalePatterns(
                    Pattern.compile(cfg.qshopSellBuyPattern),
                    Pattern.compile(cfg.qshopInfinitePattern),
                    Pattern.compile(cfg.qshopOutOfStockPattern),
                    Pattern.compile(cfg.qshopOutOfSpacePattern),
                    Pattern.compile(cfg.qshopPricePattern),
                    cfg.qshopSellKeyword,
                    cfg.qshopBuyKeyword
            );
        } catch (java.util.regex.PatternSyntaxException e) {
            ChunkScannerMod.LOGGER.error("QShopAnalyzer: invalid regex pattern in config, using defaults", e);
            // 回退到硬编码默认值
            p = new LocalePatterns(
                    Pattern.compile("^\\s*(出售|收购)\\s+(\\d+)"),
                    Pattern.compile("^\\s*(出售|收购)\\s+无限"),
                    Pattern.compile("^\\s*缺货"),
                    Pattern.compile("^\\s*空间不足"),
                    Pattern.compile("单价[：:]\\s*(.+)"),
                    "出售",
                    "收购"
            );
        }
        cachedPatterns = p;
        cachedConfigHash = hash;
        return p;
    }

    @Override
    public AnalyzeResult analyze(WorldChunk chunk, int cx, int cz, String dimId, ChunkDb db, long now) {
        return analyze(chunk, cx, cz, dimId, db, now, null);
    }

    @Override
    public AnalyzeResult analyze(WorldChunk chunk, int cx, int cz, String dimId, ChunkDb db, long now, World world) {
        int dimPoolId = db.intern(dimId);

        // 从全局配置读取 QShop 正则模式（缓存编译结果）
        LocalePatterns patterns = getPatterns();

        List<byte[]> records = new ArrayList<>();

        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (!(be instanceof SignBlockEntity sign)) continue;

            BlockPos signPos = sign.getPos();

            // 检查告示牌是否贴靠在具有物品栏的容器方块上
            // 优先使用 World 级查找（支持跨区块边界），fallback 到 chunk 级
            if (world != null) {
                if (!isAttachedToContainer(world, signPos)) continue;
            } else {
                if (!isAttachedToContainer(chunk, signPos)) continue;
            }

            // QShop 格式的告示牌只在正面有文字
            SignText frontText = sign.getFrontText();
            String[] lines = new String[4];
            boolean hasContent = false;
            for (int i = 0; i < 4; i++) {
                lines[i] = frontText.getMessage(i, false).getString();
                if (lines[i] != null && !lines[i].trim().isEmpty()) hasContent = true;
            }
            if (!hasContent) continue;

            // 正则匹配 QShop 四行格式（使用当前语言环境的模式）
            QShopParsed parsed = parseQShop(lines, patterns);
            if (parsed == null) continue;

            // keyHi: [dimPoolId(32bit) | x(32bit)]，keyLo: [z(32bit) | y(32bit)]
            long keyHi = ((long) dimPoolId << 32) | (signPos.getX() & 0xFFFFFFFFL);
            long keyLo = ((long) signPos.getZ() << 32) | (signPos.getY() & 0xFFFFFFFFL);

            // 字符串池化（避免每条记录重复存储相同字符串）
            int ownerId = db.intern(parsed.owner());
            int itemNameId = db.intern(parsed.itemName());

            // 通过译名映射表尝试恢复物品注册名
            int itemIdPoolId = 0; // StringPoolId 0 = null/空串
            int flags = 0;
            String registryId = ItemTranslator.lookup(parsed.itemName());
            if (registryId != null) {
                itemIdPoolId = db.intern(registryId);
                flags |= FLAG_ID_RECOVERED;
            }

            // 构造 60 字节的值
            ByteBuffer vb = ByteBuffer.allocate(RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            vb.putLong(keyHi);
            vb.putLong(keyLo);
            vb.putInt(ownerId);
            // mode(低 8 位) + quantity(高 24 位) 打包为一个 u32
            int modePacked = (parsed.mode() & 0xFF) | ((parsed.quantity() & 0xFFFFFF) << 8);
            vb.putInt(modePacked);
            vb.putInt(itemNameId);
            vb.putInt(parsed.price()); // 价格：整型，最小货币单位
            vb.putLong(now); // 扫描时间戳
            vb.putInt(itemIdPoolId);
            vb.putInt(flags);

            byte[] key = makeKey(keyHi, keyLo, dimPoolId, cx, cz);
            records.add(key);
            records.add(vb.array());
        }

        // 先删除该 chunk 旧记录，再写入新记录
        // 注意：若 putAll 写入失败存在数据丢失风险，但 removeAllWithPrefix
        // 是前缀匹配，先写后删会把刚写入的数据也删掉，故只能先删后写。
        byte[] chunkPrefix = makeChunkPrefix(dimPoolId, cx, cz);
        db.removeAllWithPrefix(chunkPrefix);

        if (records.isEmpty()) {
            return AnalyzeResult.skipped();
        }

        // 批量写入数据库
        List<ChunkDb.Entry> entries = new ArrayList<>(records.size() / 2);
        for (int i = 0; i < records.size(); i += 2) {
            entries.add(ChunkDb.Entry.of(records.get(i), records.get(i + 1)));
        }
        db.putAll(entries);

        return AnalyzeResult.found("qshops=" + entries.size());
    }

    /**
     * 检查告示牌是否贴靠在容器方块上。
     * 遍历告示牌周围 6 个相邻位置，通过 World 级查找容器方块实体。
     * 支持跨区块容器（边界上的告示牌）。
     */
    private static boolean isAttachedToContainer(World world, BlockPos signPos) {
        for (Direction dir : Direction.values()) {
            BlockPos adjacent = signPos.offset(dir);
            BlockEntity be = world.getBlockEntity(adjacent);
            if (be instanceof Inventory) {
                return true;
            }
        }
        return false;
    }

    /**
     * 兜底：使用 WorldChunk 级查找（仅能查到同区块内的容器方块）。
     */
    private static boolean isAttachedToContainer(WorldChunk chunk, BlockPos signPos) {
        for (Direction dir : Direction.values()) {
            BlockPos adjacent = signPos.offset(dir);
            BlockEntity be = chunk.getBlockEntity(adjacent);
            if (be instanceof Inventory) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析 QShop 告示牌四行文字。
     *
     * <p>根据给定的 LocalePatterns 解析第二行状态/数量和第四行价格。
     * 支持四种第二行格式：
     * <ul>
     *   <li>{@code 出售/收购 N} (zh_cn) 或 {@code Selling/Buying N} (en_us) — 普通玩家商店</li>
     *   <li>{@code 出售/收购 无限} 或 {@code Selling/Buying Unlimited} — 系统商店，无限库存</li>
     *   <li>{@code 缺货} 或 {@code Out of Stock} — 出售模式，余量为 0</li>
     *   <li>{@code 空间不足} 或 {@code Out of Space} — 收购模式，余量为 0</li>
     * </ul>
     *
     * @param lines    四行文本（未去除首尾空白）
     * @param patterns 当前语言环境的正则模式
     * @return 解析结果，格式不符则返回 null
     */
    static QShopParsed parseQShop(String[] lines, LocalePatterns patterns) {
        // 第一行：所有者名称（不能为空）
        String owner = lines[0];
        if (owner == null || owner.trim().isEmpty()) return null;

        // 第二行：出售/收购 + 数量/无限  或  缺货/空间不足
        String line2 = lines[1];
        if (line2 == null || line2.trim().isEmpty()) return null;
        line2 = line2.trim();

        byte mode;
        int quantity;

        Matcher sellBuyMatcher = patterns.sellBuyPattern().matcher(line2);
        Matcher infiniteMatcher = patterns.infinitePattern().matcher(line2);
        if (sellBuyMatcher.matches()) {
            mode = patterns.isSell(sellBuyMatcher.group(1)) ? MODE_SELL : MODE_BUY;
            try {
                quantity = Integer.parseInt(sellBuyMatcher.group(2));
            } catch (NumberFormatException e) {
                return null;
            }
        } else if (infiniteMatcher.matches()) {
            mode = patterns.isSell(infiniteMatcher.group(1)) ? MODE_SELL : MODE_BUY;
            quantity = INFINITE_QUANTITY;
        } else if (patterns.outOfStockPattern().matcher(line2).matches()) {
            // 缺货 = 出售模式，余量为 0
            mode = MODE_SELL;
            quantity = 0;
        } else if (patterns.outOfSpacePattern().matcher(line2).matches()) {
            // 空间不足 = 收购模式，余量为 0
            mode = MODE_BUY;
            quantity = 0;
        } else {
            return null;
        }

        // 第三行：商品名称
        String itemName = lines[2];
        if (itemName == null || itemName.trim().isEmpty()) return null;

        // 第四行：单价（解析数字部分，转换为最小货币单位整数）
        String line4 = lines[3];
        if (line4 == null || line4.trim().isEmpty()) return null;

        Matcher priceMatcher = patterns.pricePattern().matcher(line4.trim());
        String priceText;
        if (priceMatcher.matches()) {
            priceText = priceMatcher.group(1).trim();
        } else {
            priceText = line4.trim();
        }

        int price = parsePrice(priceText);
        if (price < 0) return null;

        return new QShopParsed(owner, mode, quantity, itemName, price);
    }

    /** 构造完整键（34 字节）。 */
    private static byte[] makeKey(long keyHi, long keyLo, int dimPoolId, int cx, int cz) {
        ByteBuffer bb = ByteBuffer.allocate(KEY_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(KEY_PREFIX);
        bb.putInt(dimPoolId);
        bb.putInt(cx);
        bb.putInt(cz);
        bb.putLong(keyHi);
        bb.putLong(keyLo);
        return bb.array();
    }

    /** 构造 chunk 级删除前缀（18 字节）。 */
    private static byte[] makeChunkPrefix(int dimPoolId, int cx, int cz) {
        ByteBuffer bb = ByteBuffer.allocate(CHUNK_PREFIX_LEN).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(KEY_PREFIX);
        bb.putInt(dimPoolId);
        bb.putInt(cx);
        bb.putInt(cz);
        return bb.array();
    }

    /**
     * 从价格文本中提取数字并转换为最小货币单位整数（乘以 100）。
     * 支持 "0.50 ₦"、"100"、"1,234.56" 等格式。
     *
     * @param priceText 价格文本（可能含货币符号）
     * @return 最小货币单位整数，无法解析则返回 -1
     */
    private static final Pattern PRICE_NUM_PATTERN = Pattern.compile("(\\d+(?:,\\d{3})*)(?:\\.(\\d{1,2}))?");
    static int parsePrice(String priceText) {
        if (priceText == null) return -1;
        Matcher m = PRICE_NUM_PATTERN.matcher(priceText.trim());
        if (m.matches()) {
            try {
                String intPart = m.group(1).replace(",", "");
                String fracPart = m.group(2);
                int integerPart = Integer.parseInt(intPart);
                int fractionalPart = 0;
                if (fracPart != null) {
                    if (fracPart.length() == 1) {
                        fractionalPart = Integer.parseInt(fracPart) * 10;
                    } else {
                        fractionalPart = Integer.parseInt(fracPart);
                    }
                }
                return integerPart * 100 + fractionalPart;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return -1;
    }

    // ==================== 内部数据记录 ====================

    /**
     * 解析后的 QShop 数据。
     *
     * @param owner    商店所有者名称
     * @param mode     0=出售, 1=收购
     * @param quantity 剩余数量（出售模式 quantity=0 表示缺货，收购模式 quantity=0 表示空间不足）
     * @param itemName 商品名称
     * @param price    单价（最小货币单位，整数；真实价格 = price / 100.0）
     */
    record QShopParsed(String owner, byte mode, int quantity, String itemName, int price) {}

    // ==================== 公开工具方法 ====================

    /**
     * 快速检查一个告示牌方块实体是否为 QShop 格式的商店告示牌。
     * 供 {@link QShopChatListener} 在监听取块事件时使用，
     * 避免重复完整的解析流程。
     *
     * @param sign 告示牌方块实体
     * @return 是否为 QShop 商店告示牌
     */
    public static boolean isQShopSign(SignBlockEntity sign) {
        SignText frontText = sign.getFrontText();
        String[] lines = new String[4];
        for (int i = 0; i < 4; i++) {
            lines[i] = frontText.getMessage(i, false).getString();
        }
        return parseQShop(lines, getPatterns()) != null;
    }

    // ==================== 分析器元数据 ====================

    @Override
    public String getId() {
        return "qshop";
    }

    @Override
    public Text getName() {
        return Text.translatable("chunkscanner.analyzer.qshop.name");
    }

    @Override
    public Text getDescription() {
        return Text.translatable("chunkscanner.analyzer.qshop.desc");
    }
}

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

import com.billy65536.chunkscanner.core.AnalyzeResult;
import com.billy65536.chunkscanner.core.ChunkAnalyzer;
import com.billy65536.chunkscanner.core.ChunkDb;

/**
 * QShop 商店分析器：扫描贴在容器上的特化告示牌，识别 QShop 格式商店。
 *
 * 告示牌必须贴靠在箱子、木桶等具有物品栏的容器方块上，只有正面有文字，且格式为：
 * <pre>
 *   第一行：玩家名称或"系统商店"（商店所有者）
 *   第二行：出售/收购 + 空格 + 数量/无限  或  缺货（前面可能有空格缩进）
 *   第三行：商品名称
 *   第四行：单价：数字 货币符号（如 "单价：0.50 ₦"）
 * </pre>
 *
 * 系统商店：第一行为"系统商店"，数量为"无限"，用 quantity=0xFFFFFF 表示。
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
public class QShopAnalyzer implements ChunkAnalyzer {

    private static final int RECORD_SIZE = 40; // 8+8+4+4+4+4+8
    private static final byte[] KEY_PREFIX = "qshop:".getBytes(StandardCharsets.UTF_8);
    // "qshop:"(6) + dimPoolId(4) + cx(4) + cz(4) + keyHi(8) + keyLo(8)
    private static final int KEY_SIZE = KEY_PREFIX.length + 4 + 4 + 4 + 8 + 8; // 34
    private static final int CHUNK_PREFIX_LEN = KEY_PREFIX.length + 4 + 4 + 4; // 18

    /** 销售/收购：第二行 "出售/收购" + 空格 + 数量（前面可能有空格缩进） */
    private static final Pattern SELL_BUY_PATTERN = Pattern.compile("^\\s*(出售|收购)\\s+(\\d+)");
    /** 无限库存：第二行 "出售/收购" + 空格 + 无限（系统商店） */
    private static final Pattern INFINITE_PATTERN = Pattern.compile("^\\s*(出售|收购)\\s+无限");
    /** 缺货（前面可能有空格缩进） */
    private static final Pattern OUT_OF_STOCK_PATTERN = Pattern.compile("^\\s*缺货");
    /** 单价：【数字】【货币符号】 */
    private static final Pattern PRICE_PATTERN = Pattern.compile("单价[：:]\\s*(.+)");

    /** quantity 的最大值 (24-bit)，用作"无限"的哨兵值 */
    public static final int INFINITE_QUANTITY = 0xFFFFFF;

    public static final byte MODE_SELL = 0;
    public static final byte MODE_BUY  = 1;

    @Override
    public AnalyzeResult analyze(WorldChunk chunk, int cx, int cz, String dimId, ChunkDb db, long now) {
        return analyze(chunk, cx, cz, dimId, db, now, null);
    }

    @Override
    public AnalyzeResult analyze(WorldChunk chunk, int cx, int cz, String dimId, ChunkDb db, long now, World world) {
        int dimPoolId = db.intern(dimId);

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

            // 正则匹配 QShop 四行格式
            QShopParsed parsed = parseQShop(lines);
            if (parsed == null) continue;

            // keyHi: [dimPoolId(32bit) | x(32bit)]，keyLo: [z(32bit) | y(32bit)]
            long keyHi = ((long) dimPoolId << 32) | (signPos.getX() & 0xFFFFFFFFL);
            long keyLo = ((long) signPos.getZ() << 32) | (signPos.getY() & 0xFFFFFFFFL);

            // 字符串池化（避免每条记录重复存储相同字符串）
            int ownerId = db.intern(parsed.owner());
            int itemNameId = db.intern(parsed.itemName());
            int priceId = db.intern(parsed.price());

            // 构造 40 字节的值
            ByteBuffer vb = ByteBuffer.allocate(RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            vb.putLong(keyHi);
            vb.putLong(keyLo);
            vb.putInt(ownerId);
            // mode(低 8 位) + quantity(高 24 位) 打包为一个 u32
            int modePacked = (parsed.mode() & 0xFF) | ((parsed.quantity() & 0xFFFFFF) << 8);
            vb.putInt(modePacked);
            vb.putInt(itemNameId);
            vb.putInt(priceId);
            vb.putLong(now); // 扫描时间戳

            byte[] key = makeKey(keyHi, keyLo, dimPoolId, cx, cz);
            records.add(key);
            records.add(vb.array());
        }

        // 先收集后替换：先删除旧记录再批量写入，确保异常安全（不在收集前删除）
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
     * <p>支持三种第二行格式：
     * <ul>
     *   <li>{@code 出售/收购 N} — 普通玩家商店，指定数量</li>
     *   <li>{@code 出售/收购 无限} — 系统商店，无限库存</li>
     *   <li>{@code 缺货} — 出售模式，余量为 0</li>
     * </ul>
     *
     * @param lines 四行文本（未去除首尾空白）
     * @return 解析结果，格式不符则返回 null
     */
    static QShopParsed parseQShop(String[] lines) {
        // 第一行：所有者名称（不能为空）
        String owner = lines[0];
        if (owner == null || owner.trim().isEmpty()) return null;

        // 第二行：出售/收购 + 数量/无限  或  缺货
        String line2 = lines[1];
        if (line2 == null || line2.trim().isEmpty()) return null;
        line2 = line2.trim();

        byte mode;
        int quantity;

        Matcher sellBuyMatcher = SELL_BUY_PATTERN.matcher(line2);
        Matcher infiniteMatcher = INFINITE_PATTERN.matcher(line2);
        if (sellBuyMatcher.matches()) {
            mode = "出售".equals(sellBuyMatcher.group(1)) ? MODE_SELL : MODE_BUY;
            try {
                quantity = Integer.parseInt(sellBuyMatcher.group(2));
            } catch (NumberFormatException e) {
                return null;
            }
        } else if (infiniteMatcher.matches()) {
            mode = "出售".equals(infiniteMatcher.group(1)) ? MODE_SELL : MODE_BUY;
            quantity = INFINITE_QUANTITY;
        } else if (OUT_OF_STOCK_PATTERN.matcher(line2).matches()) {
            // 缺货 = 出售模式，余量为 0
            mode = MODE_SELL;
            quantity = 0;
        } else {
            return null;
        }

        // 第三行：商品名称
        String itemName = lines[2];
        if (itemName == null || itemName.trim().isEmpty()) return null;

        // 第四行：单价：【数字】【货币符号】
        String line4 = lines[3];
        if (line4 == null || line4.trim().isEmpty()) return null;

        Matcher priceMatcher = PRICE_PATTERN.matcher(line4.trim());
        String price;
        if (priceMatcher.matches()) {
            price = priceMatcher.group(1).trim();
        } else {
            price = line4.trim();
        }

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

    // ==================== 内部数据记录 ====================

    /**
     * 解析后的 QShop 数据。
     *
     * @param owner    商店所有者名称
     * @param mode     0=出售, 1=收购
     * @param quantity 剩余数量（出售模式且 quantity=0 表示缺货）
     * @param itemName 商品名称
     * @param price    单价文本（含货币符号）
     */
    record QShopParsed(String owner, byte mode, int quantity, String itemName, String price) {}

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

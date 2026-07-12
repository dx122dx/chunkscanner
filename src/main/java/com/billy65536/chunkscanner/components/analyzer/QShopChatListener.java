package com.billy65536.chunkscanner.components.analyzer;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.ChunkDb;
import com.billy65536.chunkscanner.core.CoreUtil;

/**
 * 监听客户端聊天消息，捕获 QuickShop 商店 Item 行中的增强物品数据。
 *
 * <h3>工作原理</h3>
 * <ol>
 *   <li>玩家左键点击 QShop 商店 → 服务器发送多条聊天消息</li>
 *   <li>本监听器检测 Item 行（同时包含 SHOW_ITEM hover 和 /qs silentpreview click）</li>
 *   <li>从 HoverEvent 中提取 ItemStack 详情（注册名、附魔、NBT 哈希）</li>
 *   <li>通过告示牌上的物品译名，在已扫描的 QShop 数据库中匹配对应记录</li>
 *   <li>将增强数据写回数据库记录</li>
 * </ol>
 *
 * <h3>匹配策略</h3>
 * <p>由于 QuickShop 的 UUID 是临时的（重启后失效），无法直接通过 UUID 关联。
 * 替代方案：通过聊天消息中的物品注册名 → ItemTranslator 反向查找物品译名 →
 * 在数据库中通过译名匹配对应记录。如果存在多个同名物品的商店（同一物品不同价格），
 * 则无法精确匹配，跳过增强。</p>
 *
 * <h3>线程安全</h3>
 * <p>聊天消息在渲染线程接收，但消息处理被缓冲（每 5 秒批量处理一次），
 * 避免在聊天高峰期频繁写入数据库。</p>
 *
 * <h3>防发包频率</h3>
 * <p>本监听器<b>不发送任何数据包</b>，仅被动监听已接收的聊天消息，
 * 因此不会触发服务器反作弊检测。数据库写入操作通过 {@link ChunkDb} 的标准接口执行，
 * 与扫描器共享同一个数据库实例。</p>
 */
public final class QShopChatListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("chunkscanner.chat");

    /** 批量处理间隔（毫秒）。聊天消息先缓存，定期批量处理。 */
    private static final long PROCESS_INTERVAL_MS = 5000;

    /** 单次批量处理最大消息数。防止瞬间大量消息堆积。 */
    private static final int MAX_BATCH_SIZE = 32;

    /** 数据库键前缀。 */
    private static final byte[] KEY_PREFIX = "qshop:".getBytes(StandardCharsets.UTF_8);
    private static final int KEY_PREFIX_LEN = KEY_PREFIX.length;

    /** 待处理的消息队列（线程安全）。 */
    private static final ConcurrentLinkedQueue<PendingMessage> pendingMessages = new ConcurrentLinkedQueue<>();

    /** 上次处理时间戳（毫秒）。 */
    private static volatile long lastProcessTime = 0;

    /** 是否已注册监听器。 */
    private static volatile boolean registered = false;

    /** 统计计数器。 */
    private static final AtomicInteger totalDetected = new AtomicInteger(0);
    private static final AtomicInteger totalEnhanced = new AtomicInteger(0);

    private QShopChatListener() {}

    // ==================== 待处理消息 ====================

    /**
     * 缓存一条待处理的聊天消息（含已提取的物品数据，避免批处理时重复解析）。
     */
    private record PendingMessage(ChatItemExtractor.ExtractedItem item, long receivedAt) {}

    // ==================== 注册/注销 ====================

    /**
     * 注册聊天消息监听器。
     * 通过 Fabric API 的 {@link ClientReceiveMessageEvents} 订阅所有聊天消息。
     * 重复调用安全（已注册则跳过）。
     *
     * <p>QuickShop 通过 {@code player.spigot().sendMessage()} 发送商店信息，
     * 在客户端被分类为 GAME 消息（非玩家聊天），因此只监听 GAME 事件即可。
     */
    public static void register() {
        if (registered) return;
        registered = true;

        // QuickShop 通过 spigot().sendMessage() 发送，被归类为 GAME 消息
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            onChatMessage(message);
        });

        LOGGER.info("QShop chat listener registered");
    }

    // ==================== 消息处理 ====================

    /**
     * 收到聊天消息时调用（在渲染线程）。
     * 快速预检后将消息缓存，由定时批量处理。
     */
    private static void onChatMessage(Text message) {
        if (message == null) return;

        // 快速预检：是否包含 "/qs silentpreview"
        String str = message.getString();
        if (str == null || !str.contains("/qs silentpreview")) return;

        // 尝试提取物品数据
        ChatItemExtractor.ExtractedItem item = ChatItemExtractor.extract(message);
        if (item == null) return;

        totalDetected.incrementAndGet();

        // 缓存到队列（存储已提取的物品数据，避免批处理时重复解析）
        pendingMessages.offer(new PendingMessage(item, System.currentTimeMillis()));

        // 限制队列大小，防止内存泄漏
        while (pendingMessages.size() > 200) {
            pendingMessages.poll();
        }

        LOGGER.debug("Detected QShop item: registryId={}, enchants={}, nbtHash={}",
                item.registryId(), item.enchants().size(), item.nbtHash());
    }

    // ==================== 定时处理 ====================

    /**
     * 每 tick 调用，检查是否需要批量处理缓存的消息。
     * 应在客户端 tick 回调中调用（例如 ChunkScanner.onClientTick）。
     */
    public static void tick() {
        long now = System.currentTimeMillis();
        if (now - lastProcessTime < PROCESS_INTERVAL_MS) return;
        if (pendingMessages.isEmpty()) return;

        lastProcessTime = now;
        processBatch();
    }

    /**
     * 批量处理缓存的消息。
     * 收集所有待处理消息，去重后尝试匹配数据库记录。
     */
    private static void processBatch() {
        // 收集消息（限制批量大小）
        List<PendingMessage> batch = new ArrayList<>(MAX_BATCH_SIZE);
        PendingMessage msg;
        while (batch.size() < MAX_BATCH_SIZE && (msg = pendingMessages.poll()) != null) {
            batch.add(msg);
        }

        if (batch.isEmpty()) return;

        // 去重：同一物品注册名 + 附魔列表相同的消息只处理一次
        Map<String, ChatItemExtractor.ExtractedItem> uniqueItems = new LinkedHashMap<>();
        for (PendingMessage pm : batch) {
            ChatItemExtractor.ExtractedItem item = pm.item();
            if (item == null) continue;
            String dedupKey = item.registryId() + "|" + String.join(",", item.enchants()) + "|" + item.nbtHash();
            uniqueItems.putIfAbsent(dedupKey, item);
        }

        LOGGER.debug("Processing {} chat messages ({} unique items)", batch.size(), uniqueItems.size());

        // 获取当前活跃的扫描会话（所有 QShop 扫描器共享的数据库）
        var scanner = ChunkScannerMod.getScanner();
        if (scanner == null) return;

        int enhanced = 0;
        for (ChatItemExtractor.ExtractedItem item : uniqueItems.values()) {
            try {
                if (tryEnhanceRecords(scanner, item)) {
                    enhanced++;
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to enhance record for {}: {}", item.registryId(), e.getMessage());
            }
        }

        if (enhanced > 0) {
            totalEnhanced.addAndGet(enhanced);
            LOGGER.debug("Enhanced {} QShop records with chat data (total: {})",
                    enhanced, totalEnhanced.get());
        }
    }

    // ==================== 数据库匹配与增强 ====================

    /**
     * 尝试在活跃的 QShop 扫描会话中匹配并增强记录。
     *
     * <p>匹配流程：
     * <ol>
     *   <li>遍历所有活跃会话，找到 QShop 分析器对应的会话</li>
     *   <li>从物品注册名通过 ItemTranslator 反向查找译名</li>
     *   <li>在数据库中查找译名匹配的记录</li>
     *   <li>如果恰好匹配到一条记录，写入增强数据</li>
     *   <li>如果匹配到多条（同名不同价），无法精确区分，跳过</li>
     * </ol>
     *
     * @return 是否成功增强了至少一条记录
     */
    private static boolean tryEnhanceRecords(com.billy65536.chunkscanner.core.ChunkScanner scanner,
                                             ChatItemExtractor.ExtractedItem item) {
        boolean enhanced = false;

        for (var session : scanner.getActiveSessions()) {
            if (!"qshop".equals(session.analyzer.getId())) continue;

            com.billy65536.chunkscanner.core.ChunkDb db = session.db;
            if (db == null) continue;

            // 通过注册名反查译名
            String translatedName = reverseLookupItemName(item.registryId());
            if (translatedName == null) continue;

            // 查找匹配的数据库记录
            List<MatchResult> matches = findMatchingRecords(db, translatedName);
            if (matches.size() != 1) {
                // 0 条：没有匹配；>1 条：同名不同价，无法区分
                continue;
            }

            // 精确匹配：更新记录
            MatchResult match = matches.get(0);
            byte[] enhancedValue = buildEnhancedValue(match.value(), item);
            if (enhancedValue != null) {
                db.put(match.key(), enhancedValue);
                enhanced = true;
                LOGGER.debug("Enhanced QShop record: {} at ({}, {}, {}) with enchants={}",
                        item.registryId(), match.x(), match.y(), match.z(), item.enchants().size());
            }
        }

        return enhanced;
    }

    // ==================== 译名反查 ====================

    /**
     * 通过注册名反查物品译名。
     * 遍历 ItemTranslator 的映射表找到对应译名。
     * 注意：可能存在多个译名映射到同一个注册名（罕见），取第一个。
     */
    private static String reverseLookupItemName(String registryId) {
        if (registryId == null || registryId.isEmpty()) return null;

        // ItemTranslator 是 displayName → registryId 的映射
        // 需要反向查找
        // 由于 ItemTranslator 不暴露内部 Map，这里直接使用 Registries 反向查询
        try {
            net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse(registryId);
            if (id == null) return null;
            net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(id);
            if (item == null) return null;
            return item.getName().getString();
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 数据库记录匹配 ====================

    /**
     * 数据库匹配结果。
     */
    private record MatchResult(byte[] key, byte[] value, int x, int y, int z) {}

    /**
     * 在数据库中查找译名匹配的 QShop 记录。
     *
     * @param db             数据库实例
     * @param translatedName 物品译名（告示牌第三行）
     * @return 匹配的记录列表
     */
    private static List<MatchResult> findMatchingRecords(ChunkDb db, String translatedName) {
        List<MatchResult> results = new ArrayList<>();

        // 获取所有条目并过滤 qshop 前缀
        List<ChunkDb.Entry> entries;
        try {
            entries = db.getAllEntries();
        } catch (Exception e) {
            return results;
        }

        for (ChunkDb.Entry entry : entries) {
            byte[] key = entry.key();
            if (key.length < KEY_PREFIX_LEN) continue;
            if (!CoreUtil.startsWith(key, KEY_PREFIX)) continue;

            byte[] val = entry.value();
            if (val.length < 48) continue; // 至少需要基础 48 字节

            try {
                ByteBuffer vb = ByteBuffer.wrap(val).order(ByteOrder.LITTLE_ENDIAN);
                // 跳过 keyHi(8) + keyLo(8) + ownerId(4) + modePacked(4)
                vb.position(24);
                int itemNameId = vb.getInt();
                // 跳过 priceId(4) + timestamp(8) + itemId(4) + flags(4)
                // 不需要这些字段

                String itemName = db.lookup(itemNameId);
                if (translatedName.equals(itemName)) {
                    // 提取坐标
                    vb.position(0);
                    long keyHi = vb.getLong();
                    long keyLo = vb.getLong();
                    int x = (int) (keyHi & 0xFFFFFFFFL);
                    int z = (int) (keyLo >> 32);
                    int y = (int) (keyLo & 0xFFFFFFFFL);
                    results.add(new MatchResult(key, val, x, y, z));
                }
            } catch (Exception e) {
                LOGGER.debug("Error parsing QShop record during match: {}", e.getMessage());
            }
        }

        return results;
    }

    // ==================== 增强值构建 ====================

    /**
     * 在现有 value 基础上追加增强数据。
     *
     * <p>现有 value 格式（48 字节）：
     * <pre>
     *   keyHi:u64 (8) | keyLo:u64 (8) | owner:u32 (4) | modePacked:u32 (4) |
     *   itemName:u32 (4) | price:u32 (4) | timestamp:u64 (8) | itemId:u32 (4) | flags:u32 (4)
     * </pre>
     *
     * <p>增强后 value（56 字节）：
     * <pre>
     *   [0..47] 同上
     *   enchantsCount:u16 (2) | itemNbtHash:u32 (4) | reserved:u16 (2)
     * </pre>
     *
     * <p>flags 字段新增位：
     * <pre>
     *   FLAG_ENHANCED_DATA = 0x02  — 标记此记录包含增强数据
     *   FLAG_HAS_ENCHANTS   = 0x04  — 标记物品有附魔
     * </pre>
     */
    private static byte[] buildEnhancedValue(byte[] originalValue, ChatItemExtractor.ExtractedItem item) {
        try {
            ByteBuffer vb = ByteBuffer.wrap(originalValue).order(ByteOrder.LITTLE_ENDIAN);
            if (vb.remaining() < 48) return null;

            // 读取并更新 flags
            // flags 位于偏移 44 (48 - 4)
            int flagsPos = 44;
            int flags = vb.getInt(flagsPos);

            // 如果已经增强过，不重复处理
            if ((flags & QShopAnalyzer.FLAG_ENHANCED_DATA) != 0) return null;

            // 设置增强标志
            flags |= QShopAnalyzer.FLAG_ENHANCED_DATA;
            if (item.hasEnchants()) {
                flags |= QShopAnalyzer.FLAG_HAS_ENCHANTS;
            }

            // 构建新的 56 字节值
            ByteBuffer newBuf = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);

            // 复制原始 48 字节（除了 flags）
            vb.position(0);
            byte[] head = new byte[44];
            vb.get(head);
            newBuf.put(head);

            // 写入更新后的 flags
            newBuf.putInt(flags);

            // 写入增强数据：enchantsCount(2) + nbtHash(4) + reserved(2)
            int enchCount = item.hasEnchants() ? item.enchants().size() : 0;
            newBuf.putShort((short) Math.min(enchCount, 65535));
            newBuf.putInt(item.nbtHash());
            newBuf.putShort((short) 0); // reserved

            return newBuf.array();
        } catch (Exception e) {
            LOGGER.debug("Failed to build enhanced value: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 统计信息 ====================

    /** 获取累计检测到的 QuickShop Item 行数量。 */
    public static int getTotalDetected() {
        return totalDetected.get();
    }

    /** 获取累计增强的记录数量。 */
    public static int getTotalEnhanced() {
        return totalEnhanced.get();
    }
}

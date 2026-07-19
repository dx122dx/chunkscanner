package com.billy65536.chunkscanner.components.analyzer;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.ChunkDb;

/**
 * 监听客户端聊天消息和按键事件，捕获 QuickShop 商店 Item 行中的增强物品数据。
 *
 * <h3>工作原理</h3>
 * <ol>
 *   <li>在 {@link ClientTickEvents#START_CLIENT_TICK} 中手动追踪攻击键状态变化，
 *       检测玩家是否左键点击了 QShop 告示牌</li>
 *   <li>QShop 服务器发送聊天消息（Item 行）→ 本监听器从 HoverEvent 中提取 ItemStack 详情</li>
 *   <li>批量处理时，用记录的告示牌位置构造精确数据库键，直接查找并增强对应记录</li>
 * </ol>
 *
 * <h3>匹配策略</h3>
 * <p>通过追踪攻击键按下事件捕获点击位置，替代了之前基于物品译名的模糊匹配。
 * 即使同一维度的多个商店出售相同物品，也能精确匹配到被点击的那个告示牌。
 * 新点击会清空之前缓存的未处理消息和点击，确保消息与点击正确对应。</p>
 *
 * <h3>线程安全</h3>
 * <p>聊天消息回调（GAME 通道）和按键检测在渲染线程执行，
 * 但系统消息通过 Mixin 在网络线程注入。pendingMessages 和 pendingClicks
 * 通过同步块保护，确保网络线程与渲染线程之间的操作安全。</p>
 *
 * <h3>防发包频率</h3>
 * <p>本监听器<b>不发送任何数据包</b>，仅被动监听，不会触发服务器反作弊检测。</p>
 */
public final class QShopChatListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("chunkscanner.chat");

    /** 批量处理间隔（毫秒）。 */
    private static final long PROCESS_INTERVAL_MS = 5000;

    /** 单次批量处理最大消息数。 */
    private static final int MAX_BATCH_SIZE = 32;

    /** 点击有效期限（毫秒），超过此时间的点击将被丢弃。 */
    private static final long MAX_CLICK_AGE_MS = 15_000;

    /** 数据库键前缀。 */
    private static final byte[] KEY_PREFIX = "qshop:".getBytes(StandardCharsets.UTF_8);

    /** 完整键长度："qshop:"(6) + dimPoolId(4) + cx(4) + cz(4) + keyHi(8) + keyLo(8) = 34 */
    private static final int KEY_SIZE = 34;

    /** 待处理的消息队列。 */
    private static final ConcurrentLinkedQueue<PendingMessage> pendingMessages = new ConcurrentLinkedQueue<>();

    /** 待处理的点击队列。 */
    private static final ConcurrentLinkedQueue<PendingClick> pendingClicks = new ConcurrentLinkedQueue<>();

    /** 上次处理时间戳（毫秒）。 */
    private static volatile long lastProcessTime = 0;

    /** 是否已注册监听器。 */
    private static volatile boolean registered = false;

    /** 攻击键上一帧是否按下（用于检测按下瞬间）。 */
    private static boolean prevAttackPressed = false;

    /** 统计计数器。 */
    private static final AtomicInteger totalDetected = new AtomicInteger(0);
    private static final AtomicInteger totalEnhanced = new AtomicInteger(0);

    private QShopChatListener() {}

    // ==================== 内部数据记录 ====================

    /** 缓存的聊天消息（含已提取的物品数据）。 */
    private record PendingMessage(ChatItemExtractor.ExtractedItem item, long receivedAt) {}

    /** 缓存的告示牌点击信息。 */
    private record PendingClick(String dimId, int x, int y, int z, long clickedAt) {}

    // ==================== 注册/注销 ====================

    /**
     * 注册聊天消息监听器和按键检测。
     * 重复调用安全（已注册则跳过）。
     */
    public static void register() {
        if (registered) return;
        registered = true;

        // 系统消息（QuickShop 通过 Bukkit player.sendMessage() 发送，走 ClientboundSystemChatPacket）
        // 通过 SystemChatMixin 注入拦截，此处无需额外注册

        // GAME 消息作为兜底（某些服务器配置下 QuickShop 可能走此通道）
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            onChatMessage(message);
        });

        // 在 START_CLIENT_TICK 检测攻击键按下（wasPressed 已被 MC 消费，手动追踪 isPressed 状态变化）
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            detectSignClick(client);
        });

        LOGGER.info("QShop chat listener registered (system chat via mixin + game msg + key press detection)");
    }

    /**
     * 接收由 {@link com.billy65536.chunkscanner.mixin.SystemChatMixin} 拦截的系统消息。
     *
     * <p>MC 1.20.1 中 {@code ChatMessageS2CPacket} 无 overlay 字段，
     * 所有系统消息均在聊天区域显示，无需 action bar 过滤。</p>
     */
    public static void onSystemMessage(Text message) {
        if (message == null) return;
        onChatMessage(message);
    }

    // ==================== 按键检测 ====================

    /**
     * 每帧检测攻击键状态变化。
     * 使用 {@code isPressed()} 手动追踪状态转换（false→true），
     * 因为 {@code wasPressed()} 在 START_CLIENT_TICK 之前已被 Minecraft 内部消费。
     */
    private static void detectSignClick(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            prevAttackPressed = false;
            return;
        }

        boolean nowPressed = client.options.attackKey.isPressed();
        boolean justPressed = nowPressed && !prevAttackPressed;
        prevAttackPressed = nowPressed;

        if (!justPressed) return;

        // 检查准星目标是否为方块
        if (!(client.crosshairTarget instanceof BlockHitResult hit)) return;
        BlockPos pos = hit.getBlockPos();

        // 检查是否为 QShop 告示牌
        BlockEntity be = client.world.getBlockEntity(pos);
        if (!(be instanceof SignBlockEntity sign)) return;
        if (!QShopAnalyzer.isQShopSign(sign)) return;

        // 记录点击（使用同步块确保 clear 和 offer 操作的原子性，
        // 避免网络线程在两次 clear() 之间插入新消息导致消息与点击错误关联）
        String dimId = client.world.getRegistryKey().getValue().toString();
        synchronized (pendingMessages) {
            pendingMessages.clear();
            pendingClicks.clear();
            pendingClicks.offer(new PendingClick(dimId, pos.getX(), pos.getY(), pos.getZ(),
                    System.currentTimeMillis()));
        }

        LOGGER.info("QShop sign clicked at ({}, {}, {}) in {}", pos.getX(), pos.getY(), pos.getZ(), dimId);
    }

    // ==================== 聊天消息处理 ====================

    /**
     * 收到聊天消息时调用（在渲染线程）。
     * 快速预检后将消息缓存，由定时批量处理。
     */
    private static void onChatMessage(Text message) {
        if (message == null) return;

        // 提取物品数据（ChatItemExtractor.extract 内部通过遍历 Text 组件树
        // 检测 ClickEvent 和 HoverEvent，不依赖 getString() 纯文本匹配）
        ChatItemExtractor.ExtractedItem item = ChatItemExtractor.extract(message);
        if (item == null) return;

        totalDetected.incrementAndGet();

        // 缓存到队列（与 detectSignClick 中的 clear 操作同步，防止竞态）
        synchronized (pendingMessages) {
            pendingMessages.offer(new PendingMessage(item, System.currentTimeMillis()));

            // 限制队列大小，防止内存泄漏
            while (pendingMessages.size() > 200) {
                pendingMessages.poll();
            }
        }

        LOGGER.debug("Detected QShop item: registryId={}, enchants={}, nbtHash={}",
                item.registryId(), item.enchants().size(), item.nbtHash());
    }

    // ==================== 定时处理（由 ChunkScannerMod 在 END_CLIENT_TICK 调用） ====================

    /**
     * 每 tick 调用，检查是否需要批量处理缓存的消息。
     * 由 {@link ChunkScannerMod} 在 END_CLIENT_TICK 中调用。
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
     * 收集消息、去重，然后通过记录的点击位置精确查找数据库记录并增强。
     */
    private static void processBatch() {
        // 收集消息（限制批量大小）
        List<PendingMessage> batch = new ArrayList<>(MAX_BATCH_SIZE);
        PendingMessage msg;
        while (batch.size() < MAX_BATCH_SIZE && (msg = pendingMessages.poll()) != null) {
            batch.add(msg);
        }

        if (batch.isEmpty()) return;

        // 获取有效的点击
        long now = System.currentTimeMillis();
        List<PendingClick> clicks = new ArrayList<>();
        PendingClick click;
        while ((click = pendingClicks.poll()) != null) {
            if (now - click.clickedAt() <= MAX_CLICK_AGE_MS) {
                clicks.add(click);
            }
        }

        if (clicks.isEmpty()) {
            LOGGER.debug("No valid sign click for {} chat messages, skipping enhancement", batch.size());
            return;
        }

        // 去重：同一物品注册名 + 附魔列表相同的消息只处理一次
        Map<String, ChatItemExtractor.ExtractedItem> uniqueItems = new LinkedHashMap<>();
        for (PendingMessage pm : batch) {
            ChatItemExtractor.ExtractedItem item = pm.item();
            if (item == null) continue;
            String dedupKey = item.registryId() + "|" + String.join(",", item.enchants()) + "|" + item.nbtHash();
            uniqueItems.putIfAbsent(dedupKey, item);
        }

        LOGGER.debug("Processing {} chat messages ({} unique, {} clicks)",
                batch.size(), uniqueItems.size(), clicks.size());

        // 获取当前活跃的扫描会话
        var scanner = ChunkScannerMod.getScanner();
        if (scanner == null) return;

        int enhanced = 0;
        for (PendingClick c : clicks) {
            for (ChatItemExtractor.ExtractedItem item : uniqueItems.values()) {
                try {
                    if (enhanceRecordAt(scanner, c, item)) {
                        enhanced++;
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to enhance record at ({}, {}, {}): {}",
                            c.x(), c.y(), c.z(), e.getMessage());
                }
            }
        }

        if (enhanced > 0) {
            totalEnhanced.addAndGet(enhanced);
            LOGGER.info("Enhanced {} QShop records with chat data (total: {})",
                    enhanced, totalEnhanced.get());
        }
    }

    // ==================== 精确数据库增强 ====================

    /**
     * 通过点击位置精确查找并增强数据库记录。
     *
     * <p>直接使用点击坐标构造数据库键，通过 {@link ChunkDb#get(byte[])} 精确查找。
     * 即使多个商店出售相同物品，也能准确匹配到被点击的那个。</p>
     *
     * <p>增强数据仅写入子数据库（id=1），不修改主数据库，避免触发主数据库全量刷写。
     * 重访区块时主数据库记录会被清除重建，但子数据库的增强数据不受影响。</p>
     *
     * @param scanner 扫描器实例
     * @param click   记录的点击信息（维度、坐标）
     * @param item    从聊天消息提取的物品数据
     * @return 是否成功增强了记录
     */
    private static boolean enhanceRecordAt(com.billy65536.chunkscanner.core.ChunkScanner scanner,
                                           PendingClick click, ChatItemExtractor.ExtractedItem item) {
        for (var session : scanner.getActiveSessions()) {
            if (!"qshop".equals(session.analyzer.getId())) continue;

            ChunkDb db = session.db;
            if (db == null) continue;

            // 构造精确键
            int dimPoolId = db.intern(click.dimId());
            int cx = click.x() >> 4;
            int cz = click.z() >> 4;
            long keyHi = ((long) dimPoolId << 32) | (click.x() & 0xFFFFFFFFL);
            long keyLo = ((long) click.z() << 32) | (click.y() & 0xFFFFFFFFL);

            byte[] key = buildKey(dimPoolId, cx, cz, keyHi, keyLo);
            byte[] value = db.get(key);
            if (value == null) {
                LOGGER.debug("No DB record at ({}, {}, {}), may not be scanned yet",
                        click.x(), click.y(), click.z());
                continue;
            }

            // 获取子数据库（id=1），用于存储增强数据
            ChunkDb subDb = db.getSubDb(1);

            // 检查子数据库中是否已有增强记录
            if (subDb.get(key) != null) {
                LOGGER.debug("Enhanced data already exists for ({}, {}, {}), skipping",
                        click.x(), click.y(), click.z());
                return false;
            }

            // 物品 ID 和 NBT 写入子数据库字符串池
            int itemIdPoolId = item.registryId() != null && !item.registryId().isEmpty()
                    ? subDb.intern(item.registryId()) : 0;
            int detailNbtPoolId = item.fullNbtString() != null
                    ? subDb.intern(item.fullNbtString()) : 0;

            // 构建精简增强数据（20 字节）写入子数据库
            byte[] enhancedValue = buildEnhancedValue(item, itemIdPoolId, detailNbtPoolId);
            subDb.put(key, enhancedValue);
            LOGGER.info("Enhanced QShop record: {} at ({}, {}, {}) enchants={} flags={}",
                    item.registryId(), click.x(), click.y(), click.z(),
                    item.enchants().size(), item.flags());
            return true;
        }

        return false;
    }

    /**
     * 构造完整数据库键（34 字节）。
     */
    private static byte[] buildKey(int dimPoolId, int cx, int cz, long keyHi, long keyLo) {
        ByteBuffer bb = ByteBuffer.allocate(KEY_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(KEY_PREFIX);
        bb.putInt(dimPoolId);
        bb.putInt(cx);
        bb.putInt(cz);
        bb.putLong(keyHi);
        bb.putLong(keyLo);
        return bb.array();
    }

    // ==================== 增强值构建 ====================

    /** 子数据库增强记录大小（20 字节）。 */
    static final int ENHANCED_RECORD_SIZE = 20;

    /**
     * 构建精简增强数据（20 字节）。
     *
     * <p>布局：itemIdPoolId(4) | detailNbtPoolId(4) | flags(4) | updateTime(8)</p>
     *
     * @param item            从聊天消息提取的物品数据
     * @param itemIdPoolId    物品注册名在子数据库字符串池中的 ID
     * @param detailNbtPoolId 完整 NBT 字符串在子数据库字符串池中的 ID
     * @return 增强后的值（20 字节）
     */
    private static byte[] buildEnhancedValue(ChatItemExtractor.ExtractedItem item,
                                             int itemIdPoolId, int detailNbtPoolId) {
        int flags = QShopAnalyzer.FLAG_ENHANCED_DATA;
        if (item.isShulkerExpanded()) {
            flags |= QShopAnalyzer.FLAG_SHULKER_EXPANDED;
        }
        if (item.isBook()) {
            flags |= QShopAnalyzer.FLAG_BOOK;
        }

        ByteBuffer buf = ByteBuffer.allocate(ENHANCED_RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(itemIdPoolId);       // 物品注册名（子数据库字符串池）
        buf.putInt(detailNbtPoolId);    // 完整 NBT（子数据库字符串池）
        buf.putInt(flags);              // 标志位（ENHANCED | SHULKER | BOOK）
        buf.putLong(System.currentTimeMillis()); // 更新时间
        return buf.array();
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

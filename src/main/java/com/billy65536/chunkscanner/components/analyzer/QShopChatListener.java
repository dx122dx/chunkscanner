package com.billy65536.chunkscanner.components.analyzer;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.IChunkDb;

/**
 * 监听客户端聊天消息和按键事件，捕获 QuickShop 商店 Item 行中的增强物品数据。
 *
 * <h3>工作原理</h3>
 * <ol>
 *   <li>在 {@link ClientTickEvents#START_CLIENT_TICK} 中手动追踪攻击键状态变化，
 *       检测玩家是否左键点击了 QShop 告示牌</li>
 *   <li>QShop 服务器发送聊天消息（Item 行）→ 本监听器从 HoverEvent 中提取 ItemStack 详情</li>
 *   <li>处理时用记录的告示牌位置构造精确数据库键，直接查找并增强对应记录</li>
 * </ol>
 *
 * <h3>即时密封处理模式</h3>
 * <p>QShop 对点击的聊天响应是<b>即时</b>的（同一 server tick 内发送）。当用户点击
 * 下一个告示牌时，上一个告示牌的所有 Item 行消息已经到达客户端。因此采用即时密封策略：</p>
 * <ol>
 *   <li>点击 QShop 告示牌 → 若有活跃组则立即密封并处理，然后创建新组</li>
 *   <li>后续到达的消息自然归属于新的活跃组</li>
 *   <li>空闲超时（1s）仅作为最后一个点击的安全兜底</li>
 * </ol>
 * <p>相比旧版排队机制，即时密封无需等待消息空闲窗口，支持极快速连续点击。
 * 即使每秒点击 5 个告示牌，每个点击的消息-点击关联也是正确的。</p>
 *
 * <h3>匹配策略</h3>
 * <p>通过追踪攻击键按下事件捕获点击位置，每次新点击立即密封前一组。
 * 消息通过活跃组归属机制与对应点击关联。
 * 即使同一维度的多个商店出售相同物品，也能通过坐标精确匹配到被点击的告示牌。</p>
 *
 * <h3>线程安全</h3>
 * <p>聊天消息回调（GAME 通道）和按键检测在渲染线程执行，
 * 但系统消息通过 Mixin 在网络线程注入。activeGroup
 * 通过 {@code activeGroupLock} 同步块保护，确保网络线程与渲染线程之间的操作安全。</p>
 *
 * <h3>防发包频率</h3>
 * <p>本监听器<b>不发送任何数据包</b>，仅被动监听，不会触发服务器反作弊检测。</p>
 */
public final class QShopChatListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("chunkscanner.components.qshop.chat");

    /** 定时处理检查间隔（毫秒）。仅用于最后一个点击的空闲兜底。 */
    private static final long PROCESS_INTERVAL_MS = 250;

    /** 单次批量处理最大消息数（用于单组消息限制）。 */
    private static final int MAX_BATCH_SIZE = 32;

    /** 消息空闲超时（毫秒）：最后一条消息后空闲这么久，密封并处理。仅作为最后一个点击的兜底。 */
    private static final long MESSAGE_IDLE_TIMEOUT_MS = 1_000;

    /** 无消息等待超时（毫秒）：点击后这么久无任何消息，视为无效点击并丢弃。 */
    private static final long MAX_WAIT_NO_MESSAGES_MS = 5_000;

    // ==================== 内部数据记录 ====================

    /** 缓存的聊天消息（含已提取的物品数据）。 */
    private record PendingMessage(ChatItemExtractor.ExtractedItem item, long receivedAt) {}

    /** 缓存的告示牌点击信息。 */
    private record PendingClick(String dimId, int x, int y, int z, long clickedAt) {}

    /**
     * 活跃点击组：一个点击及其关联的消息集合。
     * <p>消息自然归属于当前活跃组。新点击到来时立即密封旧组并创建新组。</p>
     */
    private static final class ClickGroup {
        final PendingClick click;
        final List<PendingMessage> messages = new ArrayList<>();
        volatile long lastMessageTime;
        final long startedAt;

        ClickGroup(PendingClick click) {
            this.click = click;
            this.startedAt = click.clickedAt();
        }
    }

    // ==================== 流水线状态 ====================

    /** 当前正在吸收消息的活跃点击组（null 表示无活跃点击）。 */
    private static volatile ClickGroup activeGroup = null;

    /** 保护 activeGroup 切换和消息入队的锁。 */
    private static final Object activeGroupLock = new Object();

    /** 上次处理检查时间戳（毫秒）。 */
    private static volatile long lastProcessTime = 0;

    /** 是否已注册监听器。 */
    private static volatile boolean registered = false;

    /** 攻击键上一帧是否按下（用于检测按下瞬间）。 */
    private static boolean prevAttackPressed = false;

    /** 统计计数器。 */
    private static final AtomicInteger totalDetected = new AtomicInteger(0);
    private static final AtomicInteger totalEnhanced = new AtomicInteger(0);

    private QShopChatListener() {}

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
     *
     * <p>检测到 QShop 告示牌点击时，立即密封前一个活跃组（如有），创建新组。
     * QShop 对点击的聊天响应是即时的，前一组的所有消息在用户点击下一告示牌前已经到达，
     * 因此即时密封不会丢失或错配消息。</p>
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

        // 检查是否为 QShop 告示牌（预先排除非 QShop 点击，不触发任何处理）
        BlockEntity be = client.world.getBlockEntity(pos);
        if (!(be instanceof SignBlockEntity sign)) return;
        if (!QShopAnalyzer.isQShopSign(sign)) return;

        // 记录点击
        String dimId = client.world.getRegistryKey().getValue().toString();
        PendingClick click = new PendingClick(dimId, pos.getX(), pos.getY(), pos.getZ(),
                System.currentTimeMillis());

        ClickGroup toProcess = null;

        synchronized (activeGroupLock) {
            if (activeGroup != null) {
                // 密封旧活跃组（无论是否有消息，都移交处理）
                toProcess = activeGroup;
                LOGGER.debug("Sealing active group at ({}, {}, {}) on new click",
                        activeGroup.click.x(), activeGroup.click.y(), activeGroup.click.z());
            }
            // 创建新活跃组
            activeGroup = new ClickGroup(click);
            LOGGER.info("QShop sign clicked at ({}, {}, {}) in {} [active]",
                    pos.getX(), pos.getY(), pos.getZ(), dimId);
        }

        // 在锁外处理已密封的组（避免阻塞网络线程的消息入队）
        if (toProcess != null) {
            processGroup(toProcess);
        }
    }

    // ==================== 聊天消息处理 ====================

    /**
     * 收到聊天消息时调用（渲染线程或网络线程）。
     * 快速预检后直接添加到当前活跃组。无活跃组时丢弃消息。
     */
    private static void onChatMessage(Text message) {
        if (message == null) return;

        // 提取物品数据（ChatItemExtractor.extract 内部通过遍历 Text 组件树
        // 检测 ClickEvent 和 HoverEvent，不依赖 getString() 纯文本匹配）
        ChatItemExtractor.ExtractedItem item = ChatItemExtractor.extract(message);
        if (item == null) return;

        totalDetected.incrementAndGet();

        PendingMessage msg = new PendingMessage(item, System.currentTimeMillis());

        synchronized (activeGroupLock) {
            if (activeGroup == null) {
                LOGGER.debug("No active click group, dropping QShop item: registryId={}", item.registryId());
                return;
            }

            activeGroup.messages.add(msg);
            activeGroup.lastMessageTime = msg.receivedAt();

            // 限制单组消息数量，防止异常情况下的内存问题
            while (activeGroup.messages.size() > MAX_BATCH_SIZE * 4) {
                activeGroup.messages.remove(0);
            }
        }

        LOGGER.debug("Detected QShop item: registryId={}, nbtHash={}", item.registryId(), item.nbtHash());
    }

    // ==================== 定时处理（由 ChunkScannerMod 在 END_CLIENT_TICK 调用） ====================

    /**
     * 每 tick 调用，检查最后一个活跃组是否需要密封处理（空闲兜底）。
     * 由 {@link ChunkScannerMod} 在 END_CLIENT_TICK 中调用。
     */
    public static void tick() {
        long now = System.currentTimeMillis();
        if (now - lastProcessTime < PROCESS_INTERVAL_MS) return;

        lastProcessTime = now;
        checkIdleSeal(now);
    }

    /**
     * 检查当前活跃组是否空闲超时，作为最后一个点击的兜底处理。
     * <ul>
     *   <li>活跃组有消息且空闲超时 → 密封处理</li>
     *   <li>活跃组无消息且超过最大等待 → 丢弃（无效点击）</li>
     * </ul>
     *
     * @param now 当前时间戳
     */
    private static void checkIdleSeal(long now) {
        ClickGroup toProcess = null;

        synchronized (activeGroupLock) {
            if (activeGroup == null) return;

            if (!activeGroup.messages.isEmpty()
                    && now - activeGroup.lastMessageTime > MESSAGE_IDLE_TIMEOUT_MS) {
                // 消息流结束：密封处理
                toProcess = activeGroup;
                activeGroup = null;
                LOGGER.debug("Active group sealed (idle timeout)");
            } else if (activeGroup.messages.isEmpty()
                    && now - activeGroup.startedAt > MAX_WAIT_NO_MESSAGES_MS) {
                // 无效点击：丢弃
                LOGGER.debug("Active group at ({}, {}, {}) expired without messages, discarding",
                        activeGroup.click.x(), activeGroup.click.y(), activeGroup.click.z());
                activeGroup = null;
            }
        }

        if (toProcess != null) {
            processGroup(toProcess);
        }
    }

    /**
     * 处理一个已密封的点击组。
     * <p>对组内消息去重后，通过坐标精确查找数据库记录并增强。</p>
     *
     * @param group 已密封的点击组
     */
    private static void processGroup(ClickGroup group) {
        if (group.messages.isEmpty()) return;

        // 去重：同一物品注册名 + NBT 哈希相同的消息只处理一次
        Map<String, ChatItemExtractor.ExtractedItem> uniqueItems = new LinkedHashMap<>();
        for (PendingMessage pm : group.messages) {
            ChatItemExtractor.ExtractedItem item = pm.item();
            if (item == null) continue;
            String dedupKey = item.registryId() + "|" + item.nbtHash();
            uniqueItems.putIfAbsent(dedupKey, item);
        }

        LOGGER.debug("Processing click group at ({}, {}, {}): {} messages, {} unique",
                group.click.x(), group.click.y(), group.click.z(),
                group.messages.size(), uniqueItems.size());

        // 获取当前活跃的扫描会话
        var scanner = ChunkScannerMod.getScanner();
        if (scanner == null) return;

        int enhanced = 0;
        for (ChatItemExtractor.ExtractedItem item : uniqueItems.values()) {
            try {
                if (enhanceRecordAt(scanner, group.click, item)) {
                    enhanced++;
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to enhance record at ({}, {}, {}): {}",
                        group.click.x(), group.click.y(), group.click.z(), e.getMessage());
            }
        }

        if (enhanced > 0) {
            totalEnhanced.addAndGet(enhanced);
            LOGGER.info("Enhanced {} QShop records with chat data (total: {})",
                    enhanced, totalEnhanced.get());
            // 发送消息通知玩家增强成功
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(
                        Text.translatable("chunkscanner.msg.qshop_enhanced", enhanced)
                                .formatted(Formatting.GREEN),
                        false);
            }
        }
    }

    // ==================== 精确数据库增强 ====================

    /**
     * 通过点击位置精确查找并增强数据库记录。
     *
     * <p>直接使用点击坐标构造数据库键，通过 {@link IChunkDb#get(byte[])} 精确查找。
     * 即使多个商店出售相同物品，也能准确匹配到被点击的那个。</p>
     *
     * <p>增强数据仅写入子数据库（id=1），不修改主数据库，避免触发主数据库全量刷写。
     * 重访区块时主数据库记录会被清除重建，但子数据库的增强数据不受影响。</p>
     *
     * <p><b>总是更新增强数据</b>：不检查是否已有增强记录，每次点击都覆盖更新，
     * 确保物品数据始终是最新的（例如玩家更换了商店中的物品）。</p>
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

            IChunkDb db = session.db;
            if (db == null) continue;

            QShopDbAdapter adapter = new QShopDbAdapter(db);
            int cx = click.x() >> 4;
            int cz = click.z() >> 4;

            if (!adapter.hasRecord(click.dimId(), cx, cz, click.x(), click.y(), click.z())) {
                LOGGER.debug("No DB record at ({}, {}, {}), may not be scanned yet",
                        click.x(), click.y(), click.z());
                continue;
            }

            // 总是写入增强数据，不检查是否已存在（支持更新）
            adapter.enhanceRecord(click.dimId(), cx, cz, click.x(), click.y(), click.z(),
                    item.registryId(),
                    item.isBook(), item.isShulkerExpanded(),
                    item.fullNbtString());

            LOGGER.info("Enhanced QShop record: {} at ({}, {}, {}) flags={}",
                    item.registryId(), click.x(), click.y(), click.z(), item.flags());
            return true;
        }

        return false;
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

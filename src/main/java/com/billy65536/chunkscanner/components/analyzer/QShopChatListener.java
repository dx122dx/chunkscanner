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
import net.minecraft.util.math.Direction;
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
 *       检测玩家是否左键点击了 QShop 告示牌或其附着的容器</li>
 *   <li>QShop 服务器发送聊天消息（Item 行）→ 本监听器从 HoverEvent 中提取 ItemStack 详情</li>
 *   <li>处理时用告示牌位置构造精确数据库键，直接查找并增强对应记录</li>
 * </ol>
 *
 * <h3>即时密封 + 排水窗口 + 商品名校验</h3>
 * <p>QShop 对点击的聊天响应通常即时发送，但复杂物品（带 NBT、潜影盒）可能分多条
 * Item 行到达。采用即时密封 + 排水窗口 + 商品名校验三重保障：</p>
 * <ol>
 *   <li>点击 QShop 告示牌（或其附着容器）→ 提取告示牌商品名，旧活跃组进入排水状态</li>
 *   <li>消息到达后提取物品 → 校验消息文本是否包含目标告示牌商品名（不匹配则丢弃）</li>
 *   <li>排水窗口期内，消息通过时间窗口 + 商品名双重匹配路由到正确点击组</li>
 *   <li>排水窗口过期后，排水组被最终处理；新消息进入新活跃组</li>
 * </ol>
 * <p>QShop 每次点击发送的消息中，有且仅有一条包含告示牌显示的商品名及其物品栈。
 * 利用此特性可精确过滤无关消息，杜绝快速连续点击时的消息错配。</p>
 *
 * <h3>附着容器检测</h3>
 * <p>QShop 告示牌贴附在箱子等容器上。点击容器也会触发商店交互和 Item 行消息，
 * 本监听器会检查点击位置 6 个相邻方块，若存在 QShop 告示牌则使用告示牌坐标。
 * 确保无论点击告示牌还是容器，都能正确定位到数据库中的告示牌记录。</p>
 *
 * <h3>匹配策略</h3>
 * <p>通过追踪攻击键按下事件捕获点击位置。消息通过排水窗口 + 活跃组归属机制
 * 与对应点击关联，延迟消息不会错误归属到后续点击。</p>
 *
 * <h3>线程安全</h3>
 * <p>聊天消息回调（GAME 通道）和按键检测在渲染线程执行，
 * 但系统消息通过 Mixin 在网络线程注入。activeGroup、drainingGroups
 * 通过 {@code pipelineLock} 同步块保护。</p>
 *
 * <h3>防发包频率</h3>
 * <p>本监听器<b>不发送任何数据包</b>，仅被动监听，不会触发服务器反作弊检测。</p>
 */
public final class QShopChatListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("chunkscanner.components.qshop.chat");

    /** 定时处理检查间隔（毫秒）。仅用于最后一个点击的空闲兜底和排水组清理。 */
    private static final long PROCESS_INTERVAL_MS = 250;

    /** 单次批量处理最大消息数（用于单组消息限制）。 */
    private static final int MAX_BATCH_SIZE = 32;

    /** 消息空闲超时（毫秒）：最后一条消息后空闲这么久，密封并处理。仅作为最后一个点击的兜底。 */
    private static final long MESSAGE_IDLE_TIMEOUT_MS = 1_000;

    /** 无消息等待超时（毫秒）：点击后这么久无任何消息，视为无效点击并丢弃。 */
    private static final long MAX_WAIT_NO_MESSAGES_MS = 5_000;

    /** 排水窗口时长（毫秒）：密封后旧组继续吸收延迟消息的时间窗口。 */
    private static final long DRAIN_WINDOW_MS = 200;

    /** 排水组最大数量，防止内存无限增长。 */
    private static final int MAX_DRAINING_GROUPS = 32;

    /** 消息有效窗口（毫秒）：消息到达时间与点击时间之差超过此值则丢弃。 */
    private static final long MAX_MESSAGE_AGE_MS = 5_000;

    // ==================== 内部数据记录 ====================

    /** 缓存的聊天消息（含已提取的物品数据）。 */
    private record PendingMessage(ChatItemExtractor.ExtractedItem item, long receivedAt) {}

    /** 缓存的告示牌点击信息。 */
    private record PendingClick(String dimId, int x, int y, int z, long clickedAt,
                                String signItemName) {}

    /**
     * 点击组：一个点击及其关联的消息集合。
     * <p>支持三种状态：活跃（接收消息）、排水（密封后短窗口内继续接收）、已密封（等待处理）。</p>
     */
    private static final class ClickGroup {
        final PendingClick click;
        final List<PendingMessage> messages = new ArrayList<>();
        volatile long lastMessageTime;
        /** 排水截止时间戳（毫秒），0 表示活跃组或已处理。 */
        volatile long drainUntil;
        final long startedAt;

        ClickGroup(PendingClick click) {
            this.click = click;
            this.startedAt = click.clickedAt();
            this.drainUntil = 0;
        }

        boolean isDraining() {
            return drainUntil > 0;
        }

        boolean isDrainExpired(long now) {
            return drainUntil > 0 && now > drainUntil;
        }
    }

    // ==================== 流水线状态 ====================

    /** 当前正在吸收消息的活跃点击组（null 表示无活跃点击）。 */
    private static volatile ClickGroup activeGroup = null;

    /** 排水中的点击组列表（密封后短窗口内继续吸收延迟消息，按点击时间排序）。 */
    private static final List<ClickGroup> drainingGroups = new ArrayList<>();

    /** 保护 activeGroup、drainingGroups 和消息入队的锁。 */
    private static final Object pipelineLock = new Object();

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
     */
    public static void onSystemMessage(Text message) {
        if (message == null) return;
        onChatMessage(message);
    }

    // ==================== QShop 告示牌定位 ====================

    /**
     * 在点击位置及其相邻方块中查找 QShop 告示牌。
     *
     * <p>用户可能点击告示牌本身，也可能点击告示牌附着的容器（箱子等）。
     * QShop 对两种点击都会触发商店交互和 Item 行消息，
     * 因此需要统一定位到告示牌方块以匹配数据库记录。</p>
     *
     * <p>查找策略：检查点击位置及 6 个相邻方块，返回第一个 QShop 告示牌实体。
     * 返回告示牌实体本身（而非仅坐标），以便后续提取商品名用于消息校验。</p>
     *
     * @param world 客户端世界
     * @param clickedPos 点击位置
     * @return QShop 告示牌方块实体，未找到则返回 null
     */
    private static SignBlockEntity findQShopSign(net.minecraft.world.World world, BlockPos clickedPos) {
        // 先检查点击位置本身
        BlockEntity be = world.getBlockEntity(clickedPos);
        if (be instanceof SignBlockEntity sign && QShopAnalyzer.isQShopSign(sign)) {
            return sign;
        }

        // 检查 6 个相邻方块
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = clickedPos.offset(dir);
            BlockEntity nb = world.getBlockEntity(neighbor);
            if (nb instanceof SignBlockEntity sign && QShopAnalyzer.isQShopSign(sign)) {
                return sign;
            }
        }

        return null;
    }

    // ==================== 按键检测 ====================

    /**
     * 每帧检测攻击键状态变化。
     *
     * <p>检测到带有 QShop 告示牌的点击时，执行即时密封流程：
     * 旧活跃组进入排水状态（{@link #DRAIN_WINDOW_MS} 窗口），
     * 新点击创建新活跃组。</p>
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
        BlockPos clickedPos = hit.getBlockPos();

        // 查找 QShop 告示牌（可能是点击位置本身，也可能是相邻方块）
        SignBlockEntity sign = findQShopSign(client.world, clickedPos);
        if (sign == null) return;

        BlockPos signPos = sign.getPos();

        // 提取告示牌商品名（第 3 行/index 2），用于后续消息合法性校验
        String signItemName = sign.getFrontText().getMessage(2, false).getString();

        // 记录点击（使用告示牌坐标和商品名）
        String dimId = client.world.getRegistryKey().getValue().toString();
        long now = System.currentTimeMillis();
        PendingClick click = new PendingClick(dimId, signPos.getX(), signPos.getY(), signPos.getZ(), now,
                signItemName);

        synchronized (pipelineLock) {
            // 旧活跃组进入排水状态
            if (activeGroup != null) {
                activeGroup.drainUntil = now + DRAIN_WINDOW_MS;
                drainingGroups.add(activeGroup);
                // 限制排水组数量
                while (drainingGroups.size() > MAX_DRAINING_GROUPS) {
                    ClickGroup oldest = drainingGroups.remove(0);
                    if (oldest.isDraining()) {
                        LOGGER.debug("Dropping oldest draining group at ({}, {}, {}) due to limit",
                                oldest.click.x(), oldest.click.y(), oldest.click.z());
                    }
                }
                LOGGER.debug("Group at ({}, {}, {}) entering drain window ({}ms)",
                        activeGroup.click.x(), activeGroup.click.y(), activeGroup.click.z(),
                        DRAIN_WINDOW_MS);
            }

            // 创建新活跃组
            activeGroup = new ClickGroup(click);
            LOGGER.info("QShop sign at ({}, {}, {}) in {} item={} [active, source block at ({}, {}, {})]",
                    signPos.getX(), signPos.getY(), signPos.getZ(), dimId, signItemName,
                    clickedPos.getX(), clickedPos.getY(), clickedPos.getZ());
        }
    }

    // ==================== 聊天消息处理 ====================

    /**
     * 收到聊天消息时调用（渲染线程或网络线程）。
     *
     * <p>QShop 每次点击发送的消息中，有且仅有一条包含告示牌上显示的商品名
     * 及其物品栈（HoverEvent）。利用此特性校验消息合法性：</p>
     * <ol>
     *   <li>从消息中提取物品数据（HoverEvent → ItemStack）</li>
     *   <li>校验消息文本是否包含目标告示牌的商品名（不匹配则丢弃）</li>
     *   <li>通过时间窗口 + 商品名双重匹配路由到正确的点击组</li>
     * </ol>
     */
    private static void onChatMessage(Text message) {
        if (message == null) return;

        ChatItemExtractor.ExtractedItem item = ChatItemExtractor.extract(message);
        if (item == null) return;

        totalDetected.incrementAndGet();

        PendingMessage msg = new PendingMessage(item, System.currentTimeMillis());

        synchronized (pipelineLock) {
            // 优先匹配排水组（从新到旧遍历）
            for (int i = drainingGroups.size() - 1; i >= 0; i--) {
                ClickGroup dg = drainingGroups.get(i);
                if (dg.isDraining()
                        && msg.receivedAt() >= dg.startedAt
                        && msg.receivedAt() <= dg.drainUntil) {
                    if (matchesSignItem(message, dg.click.signItemName())) {
                        dg.messages.add(msg);
                        dg.lastMessageTime = msg.receivedAt();
                        limitGroupSize(dg);
                        LOGGER.debug("Routed msg to draining group at ({}, {}, {}) item={}",
                                dg.click.x(), dg.click.y(), dg.click.z(),
                                dg.click.signItemName());
                        return;
                    }
                    // 时间匹配但商品名不匹配，继续检查下一个排水组或活跃组
                    LOGGER.debug("Msg in drain window but signItemName mismatch, trying next group");
                }
            }

            // 其次匹配活跃组
            if (activeGroup != null) {
                long age = msg.receivedAt() - activeGroup.startedAt;
                if (age >= 0 && age <= MAX_MESSAGE_AGE_MS) {
                    if (matchesSignItem(message, activeGroup.click.signItemName())) {
                        activeGroup.messages.add(msg);
                        activeGroup.lastMessageTime = msg.receivedAt();
                        limitGroupSize(activeGroup);
                        LOGGER.debug("Routed msg to active group at ({}, {}, {}) item={}",
                                activeGroup.click.x(), activeGroup.click.y(), activeGroup.click.z(),
                                activeGroup.click.signItemName());
                        return;
                    }
                    // 活跃组时间匹配但商品名不匹配，丢弃
                    LOGGER.debug("Msg in active window but signItemName mismatch, discarding");
                    return;
                }
            }

            LOGGER.debug("No matching group for QShop item: registryId={}", item.registryId());
        }
    }

    /**
     * 校验消息文本是否包含目标告示牌上的商品名。
     *
     * <p>QShop 点击后发送的消息中，有且仅有一段特殊文字，其内容为对应的
     * QShop 告示牌所显示的商品名。通过校验消息纯文本是否包含该商品名，
     * 可精确过滤掉不属于当前点击的无关消息（如其他商店的消息、系统通知等）。</p>
     *
     * @param message      聊天消息
     * @param signItemName 告示牌上显示的商品名（纯文本，无格式化代码）
     * @return 消息文本包含商品名时为 true
     */
    private static boolean matchesSignItem(Text message, String signItemName) {
        if (signItemName == null || signItemName.isEmpty()) {
            // 无法校验时接受所有消息（兜底）
            return true;
        }
        return message.getString().contains(signItemName);
    }

    /**
     * 限制单组消息数量，防止异常情况下的内存问题。
     */
    private static void limitGroupSize(ClickGroup group) {
        while (group.messages.size() > MAX_BATCH_SIZE * 4) {
            group.messages.remove(0);
        }
    }

    // ==================== 定时处理（由 ChunkScannerMod 在 END_CLIENT_TICK 调用） ====================

    /**
     * 每 tick 调用，处理已过期的排水组和空闲活跃组。
     */
    public static void tick() {
        long now = System.currentTimeMillis();
        if (now - lastProcessTime < PROCESS_INTERVAL_MS) return;

        lastProcessTime = now;
        checkAndProcess(now);
    }

    /**
     * 检查并处理已过期的排水组和空闲活跃组。
     */
    private static void checkAndProcess(long now) {
        List<ClickGroup> toProcess = new ArrayList<>();

        synchronized (pipelineLock) {
            // 收集已过期的排水组
            Iterator<ClickGroup> it = drainingGroups.iterator();
            while (it.hasNext()) {
                ClickGroup dg = it.next();
                if (dg.isDrainExpired(now)) {
                    dg.drainUntil = 0; // 标记为已处理
                    toProcess.add(dg);
                    it.remove();
                    LOGGER.debug("Draining group at ({}, {}, {}) expired, processing",
                            dg.click.x(), dg.click.y(), dg.click.z());
                }
            }

            // 检查活跃组空闲超时（最后一个点击的兜底）
            if (activeGroup != null) {
                if (!activeGroup.messages.isEmpty()
                        && now - activeGroup.lastMessageTime > MESSAGE_IDLE_TIMEOUT_MS) {
                    toProcess.add(activeGroup);
                    activeGroup = null;
                    LOGGER.debug("Active group sealed (idle timeout)");
                } else if (activeGroup.messages.isEmpty()
                        && now - activeGroup.startedAt > MAX_WAIT_NO_MESSAGES_MS) {
                    LOGGER.debug("Active group at ({}, {}, {}) expired without messages, discarding",
                            activeGroup.click.x(), activeGroup.click.y(), activeGroup.click.z());
                    activeGroup = null;
                }
            }
        }

        // 在锁外处理
        for (ClickGroup group : toProcess) {
            processGroup(group);
        }
    }

    /**
     * 处理一个已密封的点击组。
     */
    private static void processGroup(ClickGroup group) {
        if (group.messages.isEmpty()) return;

        // 去重
        Map<String, ChatItemExtractor.ExtractedItem> uniqueItems = new LinkedHashMap<>();
        for (PendingMessage pm : group.messages) {
            ChatItemExtractor.ExtractedItem item = pm.item();
            if (item == null) continue;
            String dedupKey = item.registryId() + "|" + item.nbtHash();
            uniqueItems.putIfAbsent(dedupKey, item);
        }

        LOGGER.debug("Processing group at ({}, {}, {}): {} messages, {} unique",
                group.click.x(), group.click.y(), group.click.z(),
                group.messages.size(), uniqueItems.size());

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
     * 通过告示牌位置精确查找并增强数据库记录。
     *
     * <p><b>总是更新增强数据</b>：不检查是否已有增强记录，每次点击都覆盖更新。</p>
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

    public static int getTotalDetected() {
        return totalDetected.get();
    }

    public static int getTotalEnhanced() {
        return totalEnhanced.get();
    }
}

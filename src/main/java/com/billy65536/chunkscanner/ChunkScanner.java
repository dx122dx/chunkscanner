package com.billy65536.chunkscanner;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 异步渐进式区块扫描引擎 —— 支持同时运行多个独立扫描任务。
 *
 * 命令：
 *   /cs task begin [name] [id]
 *   /cs task stop [id]
 *   /cs task pause [id]
 *   /cs task resume [id]
 *   /cs task stopall
 *   /cs task status
 *   /cs task list
 *   /cs task gui
 *   /cs task help
 *   /cs db gui
 *   /cs db open [id]
 *   /cs db delete [id]
 *   /cs db reboot [id]
 *   /cs db list
 */
public class ChunkScanner {
    private final ChunkScannerConfig config;

    /** 所有已注册的分析器（全局）。 */
    private final Map<String, ChunkAnalyzer> analyzerRegistry;

    /** 所有活跃的扫描任务，key = scanId。 */
    private final ConcurrentHashMap<String, ScanSession> sessions;

    public ChunkScanner(ChunkScannerConfig config) {
        this.config = config;
        this.analyzerRegistry = new LinkedHashMap<>();
        this.sessions = new ConcurrentHashMap<>();

        registerAnalyzer(new SignAnalyzer());
        registerAnalyzer(new QShopAnalyzer());
    }

    // ==================== 分析器注册 ====================

    public void registerAnalyzer(ChunkAnalyzer analyzer) {
        analyzerRegistry.put(analyzer.getId(), analyzer);
        ChunkScannerMod.LOGGER.info("Registered analyzer: {}", analyzer.getId());
    }

    public Collection<ChunkAnalyzer> getAnalyzers() {
        return Collections.unmodifiableCollection(analyzerRegistry.values());
    }

    public ChunkAnalyzer getAnalyzer(String id) {
        return analyzerRegistry.get(id);
    }

    /** 获取全局配置引用（只读）。 */
    public ChunkScannerConfig getConfig() {
        return config;
    }

    // ==================== 本地化键常量 ====================

    private static final String PREFIX = "chunkscanner";

    private static final String KEY_NOT_IN_WORLD    = PREFIX + ".msg.not_in_world";
    private static final String KEY_UNKNOWN_ANALYZER = PREFIX + ".msg.unknown_analyzer";
    private static final String KEY_SCAN_EXISTS      = PREFIX + ".msg.scan_exists";
    private static final String KEY_SCAN_STARTED     = PREFIX + ".msg.scan_started";
    private static final String KEY_SCAN_STOPPED     = PREFIX + ".msg.scan_stopped";
    private static final String KEY_SCAN_PAUSED      = PREFIX + ".msg.scan_paused";
    private static final String KEY_SCAN_RESUMED     = PREFIX + ".msg.scan_resumed";
    private static final String KEY_SCAN_NOT_PAUSABLE= PREFIX + ".msg.scan_not_pausable";
    private static final String KEY_SCAN_NOT_FOUND   = PREFIX + ".msg.scan_not_found";
    private static final String KEY_NO_ACTIVE_SCANS  = PREFIX + ".msg.no_active_scans";
    private static final String KEY_STOPPED_ALL      = PREFIX + ".msg.stopped_all";
    private static final String KEY_IDLE             = PREFIX + ".msg.idle";

    private static final String KEY_STATUS_TITLE     = PREFIX + ".status.title";
    private static final String KEY_STATUS_STOP_HINT = PREFIX + ".status.stop_hint";
    private static final String KEY_STATUS_PAUSE_HINT= PREFIX + ".status.pause_hint";
    private static final String KEY_STATUS_RESUME_HINT=PREFIX + ".status.resume_hint";
    private static final String KEY_STATUS_PENDING   = PREFIX + ".status.pending";
    private static final String KEY_STATUS_SCANNED   = PREFIX + ".status.scanned";
    private static final String KEY_STATUS_FOUND     = PREFIX + ".status.found";
    private static final String KEY_STATUS_ERRORS    = PREFIX + ".status.errors";
    private static final String KEY_STATUS_RATE      = PREFIX + ".status.rate";
    private static final String KEY_STATUS_DB        = PREFIX + ".status.db";

    private static final String KEY_LIST_TITLE       = PREFIX + ".list.title";
    private static final String KEY_HELP_TITLE       = PREFIX + ".help.title";

    private static final String KEY_CMD_START_USAGE  = PREFIX + ".command.start.usage";
    private static final String KEY_CMD_STOP_USAGE   = PREFIX + ".command.stop.usage";
    private static final String KEY_CMD_STOPALL_USAGE= PREFIX + ".command.stopall.usage";
    private static final String KEY_CMD_STATUS_USAGE = PREFIX + ".command.status.usage";
    private static final String KEY_CMD_LIST_USAGE   = PREFIX + ".command.list.usage";
    private static final String KEY_CMD_HELP_USAGE   = PREFIX + ".command.help.usage";
    private static final String KEY_CMD_RELOAD_USAGE = PREFIX + ".command.reload.usage";
    private static final String KEY_CMD_PAUSE_USAGE  = PREFIX + ".command.pause.usage";
    private static final String KEY_CMD_RESUME_USAGE = PREFIX + ".command.resume.usage";
    private static final String KEY_CMD_DB_GUI_USAGE  = PREFIX + ".command.db_gui.usage";
    private static final String KEY_CMD_DB_OPEN_USAGE = PREFIX + ".command.db_open.usage";
    private static final String KEY_CMD_DB_DELETE_USAGE=PREFIX + ".command.db_delete.usage";
    private static final String KEY_CMD_DB_REBOOT_USAGE=PREFIX + ".command.db_reboot.usage";
    private static final String KEY_CMD_DB_LIST_USAGE = PREFIX + ".command.db_list.usage";
    private static final String KEY_CMD_TASK_GUI_USAGE= PREFIX + ".command.task_gui.usage";

    // ==================== 命令接口 ====================

    /** 使用默认配置启动扫描任务。scanId 为可选参数，不传则自动生成时间戳 id。 */
    public void start(MinecraftClient client, String analyzerName, String scanId) {
        start(client, analyzerName, scanId, null);
    }

    /**
     * 启动一个新的扫描任务。
     *
     * @param client     Minecraft 客户端实例
     * @param analyzerName 分析器注册 ID
     * @param scanId    扫描任务唯一标识符
     * @param taskConfig 任务级配置（可为 null，使用全局默认）
     */
    public void start(MinecraftClient client, String analyzerName, String scanId, TaskConfig taskConfig) {
        if (client.player == null || client.world == null) {
            sendMsg(client, Text.translatable(KEY_NOT_IN_WORLD).formatted(Formatting.RED));
            return;
        }

        ChunkAnalyzer analyzer = analyzerRegistry.get(analyzerName);
        if (analyzer == null) {
            sendMsg(client, Text.translatable(KEY_UNKNOWN_ANALYZER, analyzerName).formatted(Formatting.RED));
            return;
        }

        if (sessions.containsKey(scanId)) {
            sendMsg(client, Text.translatable(KEY_SCAN_EXISTS, scanId, scanId).formatted(Formatting.YELLOW));
            return;
        }

        ScanSession session = new ScanSession(scanId, analyzer, taskConfig);
        sessions.put(scanId, session);
        session.start(client);

        int effectiveRevisit = session.sessionConfig.minRevisitIntervalSec;

        sendMsg(client, Text.translatable(KEY_SCAN_STARTED).formatted(Formatting.GREEN)
                .append(Text.literal(" | "))
                .append(Text.literal("id: ").formatted(Formatting.WHITE))
                .append(Text.literal("\"" + scanId + "\"").formatted(Formatting.GOLD))
                .append(Text.literal(" ").formatted(Formatting.WHITE))
                .append(Text.literal("analyzer: ").formatted(Formatting.WHITE))
                .append(Text.literal(analyzer.getId()).formatted(Formatting.YELLOW))
                .append(Text.literal(" ").formatted(Formatting.WHITE))
                .append(Text.translatable(KEY_STATUS_PENDING).formatted(Formatting.WHITE))
                .append(Text.literal(": ").formatted(Formatting.WHITE))
                .append(Text.literal(String.valueOf(session.pendingChunks.size())).formatted(Formatting.YELLOW))
                .append(Text.literal(" revisit: ").formatted(Formatting.WHITE))
                .append(Text.literal(effectiveRevisit + "s").formatted(Formatting.YELLOW))
                .append(taskConfig != null && taskConfig.toDisplayString().length() > 0
                        ? Text.literal(" (" + taskConfig.toDisplayString() + ")").formatted(Formatting.GRAY)
                        : Text.literal("")));
    }

    public void stop(MinecraftClient client, String scanId) {
        ScanSession session = sessions.remove(scanId);
        if (session == null) {
            sendMsg(client, Text.translatable(KEY_SCAN_NOT_FOUND, scanId).formatted(Formatting.YELLOW));
            return;
        }
        session.doStop();

        sendMsg(client, Text.translatable(KEY_SCAN_STOPPED).formatted(Formatting.GREEN)
                .append(Text.literal(" | "))
                .append(Text.literal("id: ").formatted(Formatting.WHITE))
                .append(Text.literal("\"" + scanId + "\"").formatted(Formatting.GOLD))
                .append(Text.literal(" ").formatted(Formatting.WHITE))
                .append(Text.translatable(KEY_STATUS_SCANNED).formatted(Formatting.WHITE))
                .append(Text.literal(": ").formatted(Formatting.WHITE))
                .append(Text.literal(String.valueOf(session.totalScannedChunks.get())).formatted(Formatting.YELLOW))
                .append(Text.literal(" ").formatted(Formatting.WHITE))
                .append(Text.translatable(KEY_STATUS_FOUND).formatted(Formatting.WHITE))
                .append(Text.literal(": ").formatted(Formatting.WHITE))
                .append(Text.literal(String.valueOf(session.totalFoundChunks.get())).formatted(Formatting.YELLOW))
                .append(Text.literal(" ").formatted(Formatting.WHITE))
                .append(Text.translatable(KEY_STATUS_ERRORS).formatted(Formatting.WHITE))
                .append(Text.literal(": ").formatted(Formatting.WHITE))
                .append(Text.literal(String.valueOf(session.totalErrors.get())).formatted(Formatting.YELLOW))
                .append(Text.literal(" ").formatted(Formatting.WHITE))
                .append(Text.translatable(KEY_STATUS_DB).formatted(Formatting.WHITE))
                .append(Text.literal(": ").formatted(Formatting.WHITE))
                .append(Text.literal(String.valueOf(session.db != null ? session.db.size() : 0))
                        .formatted(Formatting.YELLOW)));
    }

    public void pause(MinecraftClient client, String scanId) {
        ScanSession session = sessions.get(scanId);
        if (session == null) {
            sendMsg(client, Text.translatable(KEY_SCAN_NOT_FOUND, scanId).formatted(Formatting.YELLOW));
            return;
        }
        if (session.paused) {
            sendMsg(client, Text.translatable(KEY_SCAN_NOT_PAUSABLE, scanId).formatted(Formatting.YELLOW));
            return;
        }
        session.paused = true;
        session.enqueuedChunks.clear();
        sendMsg(client, Text.translatable(KEY_SCAN_PAUSED).formatted(Formatting.YELLOW)
                .append(Text.literal(" | id: ").formatted(Formatting.WHITE))
                .append(Text.literal("\"" + scanId + "\"").formatted(Formatting.GOLD)));
    }

    public void resume(MinecraftClient client, String scanId) {
        ScanSession session = sessions.get(scanId);
        if (session == null) {
            sendMsg(client, Text.translatable(KEY_SCAN_NOT_FOUND, scanId).formatted(Formatting.YELLOW));
            return;
        }
        if (!session.paused) {
            sendMsg(client, Text.translatable(KEY_SCAN_NOT_PAUSABLE, scanId).formatted(Formatting.YELLOW));
            return;
        }
        session.paused = false;
        sendMsg(client, Text.translatable(KEY_SCAN_RESUMED).formatted(Formatting.GREEN)
                .append(Text.literal(" | id: ").formatted(Formatting.WHITE))
                .append(Text.literal("\"" + scanId + "\"").formatted(Formatting.GOLD)));
    }

    /**
     * 从已有数据库文件恢复扫描任务（使用全局默认配置）。
     * 适用于 /cs db reboot 命令。
     */
    public void startWithDb(MinecraftClient client, String scanId, String analyzerId, ChunkDb existingDb) {
        startWithDb(client, scanId, analyzerId, null, existingDb);
    }

    /**
     * 从已有数据库文件恢复扫描任务。
     * 与 start() 的区别：不创建新的 BinaryChunkDb，而是复用已有的数据库实例。
     * 这会保留之前扫描的所有数据，继续在已有基础上扫描。
     */
    public void startWithDb(MinecraftClient client, String scanId, String analyzerId, TaskConfig taskConfig, ChunkDb existingDb) {
        if (client.player == null || client.world == null) {
            sendMsg(client, Text.translatable(KEY_NOT_IN_WORLD).formatted(Formatting.RED));
            return;
        }
        ChunkAnalyzer analyzer = analyzerRegistry.get(analyzerId);
        if (analyzer == null) {
            sendMsg(client, Text.translatable(KEY_UNKNOWN_ANALYZER, analyzerId).formatted(Formatting.RED));
            return;
        }
        if (sessions.containsKey(scanId)) {
            sendMsg(client, Text.translatable(KEY_SCAN_EXISTS, scanId, scanId).formatted(Formatting.YELLOW));
            return;
        }
        ScanSession session = new ScanSession(scanId, analyzer, taskConfig, existingDb);
        sessions.put(scanId, session);
        session.start(client);
        sendMsg(client, Text.translatable(KEY_SCAN_STARTED).formatted(Formatting.GREEN)
                .append(Text.literal(" | id: ").formatted(Formatting.WHITE))
                .append(Text.literal("\"" + scanId + "\"").formatted(Formatting.GOLD))
                .append(Text.literal(" (rebooted)").formatted(Formatting.GRAY)));
    }

    public void stopAll(MinecraftClient client) {
        if (sessions.isEmpty()) {
            sendMsg(client, Text.translatable(KEY_NO_ACTIVE_SCANS).formatted(Formatting.GRAY));
            return;
        }
        int count = sessions.size();
        for (ScanSession session : sessions.values()) {
            session.doStop();
        }
        sessions.clear();
        sendMsg(client, Text.translatable(KEY_STOPPED_ALL, count).formatted(Formatting.GREEN));
    }

    public void reportStatus(MinecraftClient client) {
        if (sessions.isEmpty()) {
            sendMsg(client, Text.translatable(KEY_IDLE).formatted(Formatting.GRAY)
                    .append(Text.literal(" "))
                    .append(Text.literal("/cs task begin <name> [id]").formatted(Formatting.GOLD)));
            return;
        }

        sendMsg(client, Text.translatable(KEY_STATUS_TITLE)
                .formatted(Formatting.GOLD, Formatting.BOLD));

        for (ScanSession s : sessions.values()) {
            // 停止按钮
            Text stopBtn = Text.literal("[✕]")
                    .formatted(Formatting.RED, Formatting.BOLD)
                    .styled(style -> style
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                    "/cs task stop " + s.scanId))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Text.translatable(KEY_STATUS_STOP_HINT))));

            // 暂停/恢复按钮
            Text pauseResumeBtn;
            if (s.paused) {
                pauseResumeBtn = Text.literal("[▸]")
                        .formatted(Formatting.GREEN, Formatting.BOLD)
                        .styled(style -> style
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                        "/cs task resume " + s.scanId))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        Text.translatable(KEY_STATUS_RESUME_HINT))));
            } else {
                pauseResumeBtn = Text.literal("[⏸]")
                        .formatted(Formatting.YELLOW, Formatting.BOLD)
                        .styled(style -> style
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                        "/cs task pause " + s.scanId))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        Text.translatable(KEY_STATUS_PAUSE_HINT))));
            }

            sendMsg(client, Text.literal("")
                    .append(pauseResumeBtn)
                    .append(Text.literal(" "))
                    .append(stopBtn)
                    .append(Text.literal(" ").formatted(Formatting.WHITE))
                    .append(Text.literal("\"" + s.scanId + "\"").formatted(Formatting.GOLD))
                    .append(s.paused ? Text.literal(" [PAUSED]").formatted(Formatting.YELLOW) : Text.literal(""))
                    .append(Text.literal(" [").formatted(Formatting.GRAY))
                    .append(Text.literal(s.analyzer.getId()).formatted(Formatting.YELLOW))
                    .append(Text.literal("]").formatted(Formatting.GRAY)));

            sendMsg(client, Text.literal("  ")
                    .append(Text.translatable(KEY_STATUS_PENDING).formatted(Formatting.GRAY))
                    .append(Text.literal(": ").formatted(Formatting.GRAY))
                    .append(Text.literal(String.valueOf(s.pendingChunks.size())).formatted(Formatting.WHITE))
                    .append(Text.literal("  ").formatted(Formatting.GRAY))
                    .append(Text.translatable(KEY_STATUS_SCANNED).formatted(Formatting.GRAY))
                    .append(Text.literal(": ").formatted(Formatting.GRAY))
                    .append(Text.literal(String.valueOf(s.totalScannedChunks.get())).formatted(Formatting.WHITE))
                    .append(Text.literal("  ").formatted(Formatting.GRAY))
                    .append(Text.translatable(KEY_STATUS_FOUND).formatted(Formatting.GRAY))
                    .append(Text.literal(": ").formatted(Formatting.GRAY))
                    .append(Text.literal(String.valueOf(s.totalFoundChunks.get())).formatted(Formatting.GREEN))
                    .append(Text.literal("  ").formatted(Formatting.GRAY))
                    .append(Text.translatable(KEY_STATUS_ERRORS).formatted(Formatting.GRAY))
                    .append(Text.literal(": ").formatted(Formatting.GRAY))
                    .append(Text.literal(String.valueOf(s.totalErrors.get())).formatted(Formatting.RED))
                    .append(Text.literal("  ").formatted(Formatting.GRAY))
                    .append(Text.translatable(KEY_STATUS_RATE).formatted(Formatting.GRAY))
                    .append(Text.literal(": ").formatted(Formatting.GRAY))
                    .append(Text.literal(s.tasksPerTick + "/tick").formatted(Formatting.WHITE))
                    .append(Text.literal("  ").formatted(Formatting.GRAY))
                    .append(Text.translatable(KEY_STATUS_DB).formatted(Formatting.GRAY))
                    .append(Text.literal(": ").formatted(Formatting.GRAY))
                    .append(Text.literal(String.valueOf(s.db != null ? s.db.size() : 0))
                            .formatted(Formatting.WHITE)));
        }
    }

    public void listAnalyzers(MinecraftClient client) {
        sendMsg(client, Text.translatable(KEY_LIST_TITLE)
                .formatted(Formatting.GOLD, Formatting.BOLD));

        for (ChunkAnalyzer a : analyzerRegistry.values()) {
            sendMsg(client, Text.literal("  ")
                    .append(Text.literal(a.getId()).formatted(Formatting.YELLOW, Formatting.BOLD))
                    .append(Text.literal(" — ").formatted(Formatting.GRAY))
                    .append(a.getDescription()));
        }
    }

    public void showHelp(MinecraftClient client) {
        sendMsg(client, Text.translatable(KEY_HELP_TITLE)
                .formatted(Formatting.GOLD, Formatting.BOLD));

        String[][] helpLines = {
            { KEY_CMD_START_USAGE,    "YELLOW" },
            { KEY_CMD_STOP_USAGE,     "YELLOW" },
            { KEY_CMD_PAUSE_USAGE,    "YELLOW" },
            { KEY_CMD_RESUME_USAGE,   "YELLOW" },
            { KEY_CMD_STOPALL_USAGE,  "YELLOW" },
            { KEY_CMD_STATUS_USAGE,   "YELLOW" },
            { KEY_CMD_LIST_USAGE,     "YELLOW" },
            { KEY_CMD_TASK_GUI_USAGE, "YELLOW" },
            { KEY_CMD_DB_GUI_USAGE,   "YELLOW" },
            { KEY_CMD_DB_OPEN_USAGE,  "YELLOW" },
            { KEY_CMD_DB_DELETE_USAGE,"YELLOW" },
            { KEY_CMD_DB_REBOOT_USAGE,"YELLOW" },
            { KEY_CMD_DB_LIST_USAGE,  "YELLOW" },
            { KEY_CMD_RELOAD_USAGE,   "YELLOW" },
            { KEY_CMD_HELP_USAGE,     "YELLOW" },
        };

        for (String[] entry : helpLines) {
            Formatting fmt = entry[1].equals("YELLOW") ? Formatting.YELLOW : Formatting.GRAY;
            sendMsg(client, Text.translatable(entry[0]).formatted(fmt));
        }
    }

    // ==================== 热重载 ====================

    /**
     * 轻量热重载：将配置变更传播到所有活跃的扫描会话（更新 sessionConfig 副本）。
     * 不需要重启线程池，只 clamp 已捕获的配置值。
     * 清除已入队记录，下次 dispatch 会用新的 revisitInterval 重入队。
     *
     * @return 受影响的会话数量
     */
    public int reloadConfig() {
        int count = 0;
        for (ScanSession s : sessions.values()) {
            // 更新 sessionConfig 副本
            ChunkScannerConfig defaults = ChunkScanner.this.config;
            s.sessionConfig.minRevisitIntervalSec = defaults.minRevisitIntervalSec;
            s.sessionConfig.maxTasksPerTick = defaults.maxTasksPerTick;
            s.sessionConfig.initialTasksPerTick = defaults.initialTasksPerTick;
            s.sessionConfig.targetTickNs = defaults.targetTickNs;
            s.sessionConfig.flushIntervalTicks = defaults.flushIntervalTicks;
            s.sessionConfig.workerThreads = defaults.workerThreads;
            s.sessionConfig.scanRadiusMultiplier = defaults.scanRadiusMultiplier;

            // clamp 自适应速率到新范围
            if (s.tasksPerTick > defaults.maxTasksPerTick) {
                s.tasksPerTick = defaults.maxTasksPerTick;
            }
            if (s.tasksPerTick < 1) {
                s.tasksPerTick = 1;
            }
            // 清除已入队记录，下次 dispatch 会用新的 revisitInterval 重入队
            s.enqueuedChunks.clear();
            count++;
        }
        return count;
    }

    /**
     * 完全重启：停止所有活跃会话，重新加载配置，再用新参数重建。
     * 适用于 workerThreads 等需要重建线程池的配置变更。
     *
     * @return 重启的会话数量
     */
    public int restartAllSessions(MinecraftClient client) {
        // 收集当前会话信息
        record SessionInfo(String scanId, String analyzerId) {}
        List<SessionInfo> toRestart = new ArrayList<>();
        for (ScanSession s : sessions.values()) {
            toRestart.add(new SessionInfo(s.scanId, s.analyzer.getId()));
        }

        if (toRestart.isEmpty()) return 0;

        // 静默停止（不发聊天消息）
        for (ScanSession s : sessions.values()) {
            s.doStop();
        }
        sessions.clear();

        // 用新配置重启（新建 ScanSession 会自动复制当前 config）
        int restarted = 0;
        for (SessionInfo si : toRestart) {
            ChunkAnalyzer analyzer = analyzerRegistry.get(si.analyzerId);
            if (analyzer == null) continue;
            ScanSession session = new ScanSession(si.scanId, analyzer);
            sessions.put(si.scanId, session);
            session.start(client);
            restarted++;
        }

        ChunkScannerMod.LOGGER.info("Restarted {} scan sessions with new config.", restarted);
        return restarted;
    }

    /** 关闭所有扫描会话并释放资源。通常在 JVM shutdown hook 中调用。 */
    public void shutdown() {
        for (ScanSession s : sessions.values()) {
            s.doStop();
        }
        sessions.clear();
    }

    /** 获取所有活跃的 scanId，用于命令补全。 */
    public Set<String> getActiveScanIds() {
        return Collections.unmodifiableSet(sessions.keySet());
    }

    /** 获取所有活跃的 ScanSession（只读），用于 GUI。 */
    public Collection<ScanSession> getActiveSessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    /** 获取指定 id 的 ScanSession，可能为 null。 */
    public ScanSession getSession(String id) {
        return sessions.get(id);
    }

    public int getPendingCount(String scanId) {
        ScanSession s = sessions.get(scanId);
        return s != null ? s.pendingChunks.size() : 0;
    }

    // ==================== Tick ====================

    /**
     * 客户端每 tick 调用的主入口。
     * 1. dispatchLoadedChunks — 将可见且已加载的区块分发到各会话
     * 2. 各 Session.tick() — 提交分析任务、排空结果、自适应速率
     */
    public void onClientTick(MinecraftClient client) {
        if (sessions.isEmpty()) return;
        if (client.player == null || client.world == null) return;

        // 总分配器：遍历可见范围，检查哪些区块在内存中，只向在内存中的区块分发到所有扫描任务
        dispatchLoadedChunks(client);

        // 各任务处理结果
        for (ScanSession s : sessions.values()) {
            s.tick(client);
        }
    }

    /**
     * 总分配器：遍历玩家可见范围内的区块，检查是否已加载到内存中，
     * 若已加载则分发给所有非暂停状态的扫描任务。
     *
     * 设计要点：
     * 1. 只使用一个遍历循环，对所有活跃会话共享，避免重复区块加载检查。
     * 2. 扫描半径取所有会话中最大的 scanRadiusMultiplier（"覆盖最广者"）。
     * 3. "不在内存中"的区块不统计、不入队——这是纯客户端模组的设计前提。
     */
    private void dispatchLoadedChunks(MinecraftClient client) {
        // 收集所有非暂停的活跃会话，同时计算最大扫描半径倍率
        List<ScanSession> activeSessions = new ArrayList<>();
        double maxMultiplier = 1.0;
        for (ScanSession s : sessions.values()) {
            if (!s.paused && s.active) {
                activeSessions.add(s);
                if (s.sessionConfig.scanRadiusMultiplier > maxMultiplier) {
                    maxMultiplier = s.sessionConfig.scanRadiusMultiplier;
                }
            }
        }
        if (activeSessions.isEmpty()) return;

        // 可视范围（chunk 坐标）：玩家所在 chunk 向四周扩展 vd 个 chunk
        int vd = (int) Math.round(client.options.getViewDistance().getValue() * maxMultiplier);
        BlockPos pp = client.player.getBlockPos();
        String dimId = client.world.getRegistryKey().getValue().toString();
        long nowSec = System.currentTimeMillis() / 1000; // 秒级用于重访期比较

        int minCX = ChunkSectionPos.getSectionCoord(pp.getX()) - vd;
        int maxCX = ChunkSectionPos.getSectionCoord(pp.getX()) + vd;
        int minCZ = ChunkSectionPos.getSectionCoord(pp.getZ()) - vd;
        int maxCZ = ChunkSectionPos.getSectionCoord(pp.getZ()) + vd;

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                // 创建新 chunk 实例的标志为 false：仅查询已加载的区块
                WorldChunk chunk = client.world.getChunkManager().getWorldChunk(cx, cz, false);
                if (chunk == null) continue; // 不在内存中，跳过

                // 分发给所有活跃会话，各会话自行检查重访期和去重
                for (ScanSession s : activeSessions) {
                    s.receiveChunk(dimId, cx, cz, nowSec);
                }
            }
        }
    }

    // ==================== 内部类: ScanSession ====================

    /** 单个区块在可见范围内的状态分类（用于状态条渲染）。 */
    public record ChunkStatusBreakdown(
        int pending, int scannedNoFind, int scannedFound,
        int pastRevisitNoFind, int pastRevisitFound, int error, int foundError
    ) {
        public int total() {
            return pending + scannedNoFind + scannedFound
                    + pastRevisitNoFind + pastRevisitFound + error + foundError;
        }
    }

    /**
     * 单个扫描任务的内部状态和逻辑。
     *
     * 每个 ScanSession 拥有：
     * - 独立的线程池（scanExecutor）用于异步执行分析器
     * - 优先队列（pendingChunks）：按上次扫描时间排序，优先处理最久未扫描的区块
     * - 结果队列（resultQueue）：工作线程将分析结果写入此队列
     * - 自适应速率控制：根据 tick 耗时动态调整 tasksPerTick
     *
     * 线程安全模型：
     * - pendingChunks/enqueuedChunks：由 dispatch 线程（客户端 tick）写入，tick() 消费
     * - resultQueue：工作线程写入，tick() → drainResults() 消费
     * - active/paused：volatile，原子读写
     * - totalXXX/foundChunks/errorChunks：AtomicInteger 或 ConcurrentHashMap.keySet
     */
    public class ScanSession {
        public final String scanId;
        public final ChunkAnalyzer analyzer;
        public final ChunkDb db;
        /** 此任务独立的配置副本（合并了全局默认值和任务级配置覆盖）。 */
        private final ChunkScannerConfig sessionConfig;
        /** 原始任务配置引用（用于显示、数据库持久化和任务恢复）。 */
        private TaskConfig taskConfig;

        /** 待扫描区块优先队列：按 lastScanTime 升序，最久未扫描的优先处理。 */
        final PriorityBlockingQueue<ChunkEntry> pendingChunks;
        /** 已入队区块集合：用于快速去重，避免同一区块重复进入 pendingChunks。 */
        final Set<Long> enqueuedChunks;
        /** 分析结果队列：工作线程写入，主线程 tick 中消费。有界队列（512），背压防止 OOM。 */
        final LinkedBlockingQueue<TaskResult> resultQueue;
        /** 分析器工作线程池。 */
        ExecutorService scanExecutor;

        /** 当前 tick 的扫描速率（每 tick 处理 chunk 数），自适应调节。 */
        volatile int tasksPerTick;
        /** 当前玩家所在维度 ID（字符串形式，如 "minecraft:overworld"）。 */
        volatile String currentDimensionId = "";

        /** 累计已扫描区块数（原子递增）。 */
        public final AtomicInteger totalScannedChunks = new AtomicInteger(0);
        /** 累计有发现的区块数（原子递增）。 */
        public final AtomicInteger totalFoundChunks = new AtomicInteger(0);
        /** 累计有错误的区块数（原子递增）。 */
        public final AtomicInteger totalErrors = new AtomicInteger(0);
        /** 刷写计数器：每 tick 递增，达到 flushIntervalTicks 时刷写数据库。 */
        int flushCounter = 0;

        /** 每个区块是否有发现（packedPos → true），用于 GUI 状态条渲染。 */
        final Set<Long> foundChunks = ConcurrentHashMap.newKeySet();
        /** 每个区块是否有错误（packedPos → true），用于 GUI 状态条渲染。 */
        final Set<Long> errorChunks = ConcurrentHashMap.newKeySet();

        /** 会话是否活跃（start() 后为 true，doStop() 后为 false）。 */
        public volatile boolean active = false;
        /** 会话是否暂停（暂停时仍排空结果并刷写，但不提交新任务）。 */
        public volatile boolean paused = false;

        /** 缓存的可见范围状态分解（每 500ms 刷新一次）。 */
        private volatile ChunkStatusBreakdown cachedBreakdown;
        private volatile long lastBreakdownTime = 0;

        /** 新建会话（自动创建 BinaryChunkDb）。 */
        ScanSession(String scanId, ChunkAnalyzer analyzer) {
            this(scanId, analyzer, null, new BinaryChunkDb(scanId, analyzer.getId()));
        }

        /** 从已有数据库恢复会话。 */
        ScanSession(String scanId, ChunkAnalyzer analyzer, ChunkDb existingDb) {
            this(scanId, analyzer, null, existingDb);
        }

        /** 新建会话并应用任务级配置。 */
        ScanSession(String scanId, ChunkAnalyzer analyzer, TaskConfig taskConfig) {
            this(scanId, analyzer, taskConfig, new BinaryChunkDb(scanId, analyzer.getId()));
        }

        /**
         * 完整构造器。
         * @param scanId 扫描任务唯一标识
         * @param analyzer 使用的分析器实例
         * @param taskConfig 任务级配置覆盖（可为 null，使用全局默认）
         * @param existingDb 已有数据库实例（用于恢复扫描）
         */
        ScanSession(String scanId, ChunkAnalyzer analyzer, TaskConfig taskConfig, ChunkDb existingDb) {
            this.scanId = scanId;
            this.analyzer = analyzer;
            this.db = existingDb;
            // 合并任务配置与全局配置：非 null 字段覆盖，null 字段继承全局默认
            this.sessionConfig = (taskConfig != null ? taskConfig : new TaskConfig()).applyTo(ChunkScanner.this.config);
            this.pendingChunks = new PriorityBlockingQueue<>(1024);
            this.enqueuedChunks = ConcurrentHashMap.newKeySet();
            this.resultQueue = new LinkedBlockingQueue<>(512);
            this.tasksPerTick = sessionConfig.initialTasksPerTick;
            // 保存原始任务配置引用（用于显示和数据库持久化）
            this.taskConfig = taskConfig;
        }

        /**
         * 启动扫描任务：创建工作线程池，标记 active=true。
         * 工作线程设为 daemon 并降低优先级，避免影响游戏主循环。
         */
        void start(MinecraftClient client) {
            this.scanExecutor = Executors.newFixedThreadPool(sessionConfig.workerThreads, r -> {
                Thread t = new Thread(r, "ChunkScanner-" + scanId);
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

            active = true;
            String dim = client.world.getRegistryKey().getValue().toString();
            currentDimensionId = dim;
            // 将任务配置序列化到数据库，便于后续恢复
            if (db instanceof BinaryChunkDb bdb) {
                bdb.setTaskConfig(this.taskConfig);
            }
        }

        /**
         * 停止扫描任务：标记 active=false，清空队列，关闭线程池和数据库。
         * awaitTermination 等待最多 2 秒让正在运行的分析器优雅结束。
         */
        void doStop() {
            active = false;
            pendingChunks.clear();
            enqueuedChunks.clear();
            resultQueue.clear();
            if (scanExecutor != null) {
                scanExecutor.shutdown();
                try { scanExecutor.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            }
            if (db != null) db.close();
        }

        /**
         * 总分配器调用此方法将已确认在内存中的区块分发给本任务。
         * 处理流程：
         * 1. 检查 active 和 paused 状态
         * 2. 通过 enqueuedChunks 去重
         * 3. 检查重访期：距上次扫描不足 minRevisitIntervalSec 的跳过
         * 4. 符合条件的加入优先队列（按 lastScanTime 排序）
         */
        void receiveChunk(String dimId, int cx, int cz, long nowSec) {
            if (!active || paused) return;

            long packed = packChunkPos(cx, cz);
            if (enqueuedChunks.contains(packed)) return;

            long lastMs = db.getChunkScanTime(dimId, cx, cz);
            if (lastMs > 0 && sessionConfig.minRevisitIntervalSec > 0) {
                if (nowSec - (lastMs / 1000) < sessionConfig.minRevisitIntervalSec) return;
            }
            pendingChunks.offer(new ChunkEntry(cx, cz, lastMs));
            enqueuedChunks.add(packed);
        }

        /**
         * 每 tick 由 ChunkScanner.onClientTick 调用。
         *
         * 执行流程：
         * 1. drainResults() — 消费工作线程完成的扫描结果
         * 2. 暂停模式 — 仅处理结果和刷写，不提交新任务
         * 3. 正常模式 — 从 pendingChunks 取最多 tasksPerTick 个区块，
         *    提交到线程池执行分析器
         * 4. 自适应速率 — 根据本 tick 耗时调整 tasksPerTick：
         *    - 耗时 < 目标/2 且满载 → 加速（+1）
         *    - 耗时 > 目标×2 → 减速（-1）
         * 5. 周期性刷写 — flushCounter 达到 flushIntervalTicks 时刷写数据库
         */
        void tick(MinecraftClient client) {
            if (!active) return;

            String dim = client.world.getRegistryKey().getValue().toString();
            currentDimensionId = dim;

            drainResults();

            // 暂停状态下不处理任务，仅排空结果（确保进度不丢失）
            if (paused) {
                if (++flushCounter >= sessionConfig.flushIntervalTicks) {
                    flushCounter = 0;
                    db.flush();
                }
                return;
            }

            long tickStart = System.nanoTime();
            int submitted = 0;

            for (int i = 0; i < tasksPerTick && !pendingChunks.isEmpty(); i++) {
                ChunkEntry entry = pendingChunks.poll();
                if (entry == null) break;
                enqueuedChunks.remove(entry.packedPos());

                // 再次确认区块仍在内存中（可能已被卸载）
                WorldChunk chunk = client.world.getChunkManager().getWorldChunk(entry.cx, entry.cz, false);
                if (chunk == null) continue; // 区块已被卸载，不再统计

                final String dimId = currentDimensionId;
                final long now = System.currentTimeMillis();
                final int ecx = entry.cx, ecz = entry.cz;
                final World world = client.world;

                // 提交分析任务到工作线程池
                scanExecutor.execute(() -> {
                    try {
                        AnalyzeResult r = analyzer.analyze(chunk, ecx, ecz, dimId, db, now, world);
                        resultQueue.offer(new TaskResult(
                                r.isFound() ? 1 : 0,
                                r.isError() ? 1 : 0,
                                ecx, ecz, dimId, now, r.getInfo()));
                    } catch (Exception e) {
                        ChunkScannerMod.LOGGER.warn("[scan:{}] Analyzer '{}' failed on ({},{}): {}",
                                scanId, analyzer.getId(), ecx, ecz, e.getMessage());
                        resultQueue.offer(new TaskResult(0, 1, ecx, ecz, dimId, now, "exception=" + e.getMessage()));
                    }
                });
                submitted++;
            }

            // 自适应速率：基于本 tick 耗时与目标耗时的比较
            long dur = System.nanoTime() - tickStart;
            if (dur < sessionConfig.targetTickNs / 2 && submitted >= tasksPerTick) {
                // 负载很轻，可以加速
                tasksPerTick = Math.min(tasksPerTick + 1, sessionConfig.maxTasksPerTick);
            } else if (dur > sessionConfig.targetTickNs * 2) {
                // 负载过重，需要减速
                tasksPerTick = Math.max(tasksPerTick - 1, 1);
            }

            if (++flushCounter >= sessionConfig.flushIntervalTicks) {
                flushCounter = 0;
                db.flush();
            }
        }

        /**
         * 排空结果队列：批量取出所有已完成的分析结果，
         * 更新数据库的扫描时间戳和统计数据。
         * 使用 drainTo 批量操作，减少锁竞争。
         */
        private void drainResults() {
            List<TaskResult> batch = new ArrayList<>();
            resultQueue.drainTo(batch);
            for (TaskResult t : batch) {
                db.updateChunkScanTime(t.dimId, t.cx, t.cz, t.timestamp);
                totalScannedChunks.incrementAndGet();
                totalFoundChunks.addAndGet(t.foundCount);
                totalErrors.addAndGet(t.errorCount);
                long packed = packChunkPos(t.cx, t.cz);
                if (t.foundCount > 0) foundChunks.add(packed);
                if (t.errorCount > 0) errorChunks.add(packed);
            }
        }

        /**
         * 计算可见范围内各区块状态的分解统计，用于 GUI 状态条渲染。
         *
         * 状态分类：
         * - pending：已入队，等待扫描
         * - scannedNoFind/scannedFound：已扫描，重访期内
         * - pastRevisitNoFind/pastRevisitFound：已超出重访期
         * - error/foundError：有错误的区块
         *
         * 结果缓存 500ms 以避免每帧重复计算（可见范围可多达数百个 chunk）。
         */
        public ChunkStatusBreakdown getStatusBreakdown(MinecraftClient client) {
            long now = System.nanoTime();
            if (cachedBreakdown != null && now - lastBreakdownTime < 500_000_000L) {
                return cachedBreakdown;
            }

            int vd = (int) Math.round(client.options.getViewDistance().getValue()
                    * sessionConfig.scanRadiusMultiplier);
            BlockPos pp = client.player.getBlockPos();
            int minCX = ChunkSectionPos.getSectionCoord(pp.getX()) - vd;
            int maxCX = ChunkSectionPos.getSectionCoord(pp.getX()) + vd;
            int minCZ = ChunkSectionPos.getSectionCoord(pp.getZ()) - vd;
            int maxCZ = ChunkSectionPos.getSectionCoord(pp.getZ()) + vd;

            String dimId = currentDimensionId;
            long nowSec = System.currentTimeMillis() / 1000;
            long revisitSec = sessionConfig.minRevisitIntervalSec;

            int pending = 0, scannedNoFind = 0, scannedFound = 0;
            int pastRevisitNoFind = 0, pastRevisitFound = 0, error = 0, foundError = 0;

            for (int cx = minCX; cx <= maxCX; cx++) {
                for (int cz = minCZ; cz <= maxCZ; cz++) {
                    long packed = packChunkPos(cx, cz);
                    boolean isFound = foundChunks.contains(packed);
                    boolean isErr = errorChunks.contains(packed);
                    boolean isEnqueued = enqueuedChunks.contains(packed);
                    long lastMs = db.getChunkScanTime(dimId, cx, cz);
                    // 超出重访期 = 已扫描过 且 距上次扫描 >= minRevisitIntervalSec
                    boolean pastRevisit = lastMs > 0 && revisitSec > 0
                            && nowSec - (lastMs / 1000) >= revisitSec;

                    // 状态优先级：foundError > error > found > enqueued > scanned > (unscanned=不计)
                    if (isFound && isErr) {
                        foundError++;
                    } else if (isErr) {
                        error++;
                    } else if (isFound) {
                        if (pastRevisit) pastRevisitFound++;
                        else scannedFound++;
                    } else if (isEnqueued) {
                        pending++;
                    } else if (lastMs > 0) {
                        if (pastRevisit) pastRevisitNoFind++;
                        else scannedNoFind++;
                    }
                }
            }

            ChunkStatusBreakdown bd = new ChunkStatusBreakdown(
                    pending, scannedNoFind, scannedFound,
                    pastRevisitNoFind, pastRevisitFound, error, foundError);
            cachedBreakdown = bd;
            lastBreakdownTime = now;
            return bd;
        }

        /** 获取此任务的任务级配置（可能为 null，表示未设置任务级配置）。 */
        public TaskConfig getTaskConfig() {
            return taskConfig;
        }
    }

    // ==================== 工具 ====================

    private static void sendMsg(MinecraftClient client, Text msg) {
        if (client.player != null) {
            client.player.sendMessage(msg, false);
        }
    }

    private static long packChunkPos(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    // ==================== 内部类型 ====================

    /** 工作线程池完成的分析结果，通过 resultQueue 传回主线程。 */
    private record TaskResult(int foundCount, int errorCount, int cx, int cz, String dimId, long timestamp, String info) {}

    /** 待扫描区块条目。按 lastScanTime 排序实现优先队列（最旧优先）。 */
    private record ChunkEntry(int cx, int cz, long lastScanTime) implements Comparable<ChunkEntry> {
        @Override public int compareTo(ChunkEntry o) { return Long.compare(lastScanTime, o.lastScanTime); }
        /** 将区块坐标打包为一个 long，兼容 packChunkPos。 */
        long packedPos() { return packChunkPos(cx, cz); }
    }
}

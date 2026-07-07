package com.billy65536.chunkscanner.core;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.*;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.config.ChunkScannerConfig;
import com.billy65536.chunkscanner.config.TaskConfig;

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
    final ChunkScannerConfig config;

    /** 所有已注册的分析器（全局）。 */
    private final Map<String, ChunkAnalyzer> analyzerRegistry;

    /** 所有活跃的扫描任务，key = scanId。 */
    private final ConcurrentHashMap<String, ScanSession> sessions;

    public ChunkScanner(ChunkScannerConfig config) {
        this.config = config;
        this.analyzerRegistry = new LinkedHashMap<>();
        this.sessions = new ConcurrentHashMap<>();
    }

    // ==================== 分析器注册 ====================

    public void registerAnalyzer(ChunkAnalyzer analyzer) {
        analyzerRegistry.put(analyzer.getId(), analyzer);
        ChunkScannerMod.LOGGER.debug("Registered analyzer: {}", analyzer.getId());
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
            CoreUtil.sendMsg(client, Text.translatable(KEY_NOT_IN_WORLD).formatted(Formatting.RED));
            return;
        }

        ChunkAnalyzer analyzer = analyzerRegistry.get(analyzerName);
        if (analyzer == null) {
            CoreUtil.sendMsg(client, Text.translatable(KEY_UNKNOWN_ANALYZER, analyzerName).formatted(Formatting.RED));
            return;
        }

        if (sessions.containsKey(scanId)) {
            CoreUtil.sendMsg(client, Text.translatable(KEY_SCAN_EXISTS, scanId, scanId).formatted(Formatting.YELLOW));
            return;
        }

        ScanSession session = new ScanSession(this, scanId, analyzer, taskConfig);
        sessions.put(scanId, session);
        session.start(client);

        int effectiveRevisit = session.sessionConfig.minRevisitIntervalSec;

        CoreUtil.sendMsg(client, Text.translatable(KEY_SCAN_STARTED).formatted(Formatting.GREEN)
                .append(Text.literal(" | "))
                .append(Text.translatable("chunkscanner.label.id").formatted(Formatting.WHITE)
                        .append(Text.literal(": ")))
                .append(Text.literal("\"" + scanId + "\"").formatted(Formatting.GOLD))
                .append(Text.literal(" ").formatted(Formatting.WHITE))
                .append(Text.translatable("chunkscanner.label.analyzer").formatted(Formatting.WHITE)
                        .append(Text.literal(": ")))
                .append(Text.literal(analyzer.getId()).formatted(Formatting.YELLOW))
                .append(Text.literal(" ").formatted(Formatting.WHITE))
                .append(Text.translatable(KEY_STATUS_PENDING).formatted(Formatting.WHITE))
                .append(Text.literal(": ").formatted(Formatting.WHITE))
                .append(Text.literal(String.valueOf(session.pendingChunks.size())).formatted(Formatting.YELLOW))
                .append(Text.literal(" "))
                .append(Text.translatable("chunkscanner.label.revisit").formatted(Formatting.WHITE)
                        .append(Text.literal(": ")))
                .append(Text.literal(effectiveRevisit + "s").formatted(Formatting.YELLOW))
                .append(taskConfig != null && taskConfig.toDisplayString().length() > 0
                        ? Text.literal(" (" + taskConfig.toDisplayString() + ")").formatted(Formatting.GRAY)
                        : Text.literal("")));
    }

    public void stop(MinecraftClient client, String scanId) {
        ScanSession session = sessions.remove(scanId);
        if (session == null) {
            CoreUtil.sendMsg(client, Text.translatable(KEY_SCAN_NOT_FOUND, scanId).formatted(Formatting.YELLOW));
            return;
        }
        session.doStop();

        CoreUtil.sendMsg(client, Text.translatable(KEY_SCAN_STOPPED).formatted(Formatting.GREEN)
                .append(Text.literal(" | "))
                .append(Text.translatable("chunkscanner.label.id").formatted(Formatting.WHITE)
                        .append(Text.literal(": ")))
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
            CoreUtil.sendMsg(client, Text.translatable(KEY_SCAN_NOT_FOUND, scanId).formatted(Formatting.YELLOW));
            return;
        }
        if (session.paused) {
            CoreUtil.sendMsg(client, Text.translatable(KEY_SCAN_NOT_PAUSABLE, scanId).formatted(Formatting.YELLOW));
            return;
        }
        session.paused = true;
        session.enqueuedChunks.clear();
        ChunkScannerMod.LOGGER.debug("[scan:{}] Paused", scanId);
        CoreUtil.sendMsg(client, Text.translatable(KEY_SCAN_PAUSED).formatted(Formatting.YELLOW)
                .append(Text.literal(" | "))
                .append(Text.translatable("chunkscanner.label.id").formatted(Formatting.WHITE)
                        .append(Text.literal(": ")))
                .append(Text.literal("\"" + scanId + "\"").formatted(Formatting.GOLD)));
    }

    public void resume(MinecraftClient client, String scanId) {
        ScanSession session = sessions.get(scanId);
        if (session == null) {
            CoreUtil.sendMsg(client, Text.translatable(KEY_SCAN_NOT_FOUND, scanId).formatted(Formatting.YELLOW));
            return;
        }
        if (!session.paused) {
            CoreUtil.sendMsg(client, Text.translatable(KEY_SCAN_NOT_PAUSABLE, scanId).formatted(Formatting.YELLOW));
            return;
        }
        session.paused = false;
        ChunkScannerMod.LOGGER.debug("[scan:{}] Resumed", scanId);
        CoreUtil.sendMsg(client, Text.translatable(KEY_SCAN_RESUMED).formatted(Formatting.GREEN)
                .append(Text.literal(" | "))
                .append(Text.translatable("chunkscanner.label.id").formatted(Formatting.WHITE)
                        .append(Text.literal(": ")))
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
            CoreUtil.sendMsg(client, Text.translatable(KEY_NOT_IN_WORLD).formatted(Formatting.RED));
            return;
        }
        ChunkAnalyzer analyzer = analyzerRegistry.get(analyzerId);
        if (analyzer == null) {
            CoreUtil.sendMsg(client, Text.translatable(KEY_UNKNOWN_ANALYZER, analyzerId).formatted(Formatting.RED));
            return;
        }
        if (sessions.containsKey(scanId)) {
            CoreUtil.sendMsg(client, Text.translatable(KEY_SCAN_EXISTS, scanId, scanId).formatted(Formatting.YELLOW));
            return;
        }
        ScanSession session = new ScanSession(this, scanId, analyzer, taskConfig, existingDb);
        sessions.put(scanId, session);
        session.start(client);
        CoreUtil.sendMsg(client, Text.translatable(KEY_SCAN_STARTED).formatted(Formatting.GREEN)
                .append(Text.literal(" | "))
                .append(Text.translatable("chunkscanner.label.id").formatted(Formatting.WHITE)
                        .append(Text.literal(": ")))
                .append(Text.literal("\"" + scanId + "\"").formatted(Formatting.GOLD))
                .append(Text.literal(" "))
                .append(Text.translatable("chunkscanner.label.rebooted").formatted(Formatting.GRAY)));
    }

    public void stopAll(MinecraftClient client) {
        if (sessions.isEmpty()) {
            CoreUtil.sendMsg(client, Text.translatable(KEY_NO_ACTIVE_SCANS).formatted(Formatting.GRAY));
            return;
        }
        int count = sessions.size();
        for (ScanSession session : sessions.values()) {
            session.doStop();
        }
        sessions.clear();
        CoreUtil.sendMsg(client, Text.translatable(KEY_STOPPED_ALL, count).formatted(Formatting.GREEN));
    }

    public void reportStatus(MinecraftClient client) {
        if (sessions.isEmpty()) {
            CoreUtil.sendMsg(client, Text.translatable(KEY_IDLE).formatted(Formatting.GRAY)
                    .append(Text.literal(" "))
                    .append(Text.literal("/cs task begin <name> [id]").formatted(Formatting.GOLD)));
            return;
        }

        CoreUtil.sendMsg(client, Text.translatable(KEY_STATUS_TITLE)
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

            CoreUtil.sendMsg(client, Text.literal("")
                    .append(pauseResumeBtn)
                    .append(Text.literal(" "))
                    .append(stopBtn)
                    .append(Text.literal(" ").formatted(Formatting.WHITE))
                    .append(Text.literal("\"" + s.scanId + "\"").formatted(Formatting.GOLD))
                    .append(s.paused ? Text.literal(" ").append(Text.translatable("chunkscanner.label.paused").formatted(Formatting.YELLOW)) : Text.literal(""))
                    .append(Text.literal(" [").formatted(Formatting.GRAY))
                    .append(Text.literal(s.analyzer.getId()).formatted(Formatting.YELLOW))
                    .append(Text.literal("]").formatted(Formatting.GRAY)));

            CoreUtil.sendMsg(client, Text.literal("  ")
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
        CoreUtil.sendMsg(client, Text.translatable(KEY_LIST_TITLE)
                .formatted(Formatting.GOLD, Formatting.BOLD));

        for (ChunkAnalyzer a : analyzerRegistry.values()) {
            CoreUtil.sendMsg(client, Text.literal("  ")
                    .append(Text.literal(a.getId()).formatted(Formatting.YELLOW, Formatting.BOLD))
                    .append(Text.literal(" — ").formatted(Formatting.GRAY))
                    .append(a.getDescription()));
        }
    }

    public void showHelp(MinecraftClient client) {
        CoreUtil.sendMsg(client, Text.translatable(KEY_HELP_TITLE)
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
            CoreUtil.sendMsg(client, Text.translatable(entry[0]).formatted(fmt));
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
            s.sessionConfig.waypointName = defaults.waypointName;
            s.sessionConfig.waypointInitials = defaults.waypointInitials;
            s.sessionConfig.waypointGroup = defaults.waypointGroup;

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
        // 收集当前会话信息（保存 TaskConfig 以便恢复）
        record SessionInfo(String scanId, String analyzerId, TaskConfig taskConfig) {}
        List<SessionInfo> toRestart = new ArrayList<>();
        for (ScanSession s : sessions.values()) {
            toRestart.add(new SessionInfo(s.scanId, s.analyzer.getId(), s.getTaskConfig()));
        }

        if (toRestart.isEmpty()) return 0;

        // 静默停止（不发聊天消息）
        for (ScanSession s : sessions.values()) {
            s.doStop();
        }
        sessions.clear();

        // 用新配置重启（保留原有 TaskConfig）
        int restarted = 0;
        for (SessionInfo si : toRestart) {
            ChunkAnalyzer analyzer = analyzerRegistry.get(si.analyzerId);
            if (analyzer == null) continue;
            ScanSession session = new ScanSession(this, si.scanId, analyzer, si.taskConfig);
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

}

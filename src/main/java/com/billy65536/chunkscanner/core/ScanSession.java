package com.billy65536.chunkscanner.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.components.db.BinaryChunkDb;
import com.billy65536.chunkscanner.config.ChunkScannerConfig;
import com.billy65536.chunkscanner.config.TaskConfig;
import com.billy65536.chunkscanner.core.ChunkScanner.ChunkStatusBreakdown;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

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
    /**
     *
     */
    private final ChunkScanner chunkScanner;
    public final String scanId;
    public final ChunkAnalyzer analyzer;
    public final ChunkDb db;
    /** 此任务独立的配置副本（合并了全局默认值和任务级配置覆盖）。 */
    final ChunkScannerConfig sessionConfig;
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
    /** Debug 统计计数器：每 ~200 ticks 输出一次扫描速率统计。 */
    private int debugTickCounter = 0;
    private int debugSubmittedTotal = 0;

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
    ScanSession(ChunkScanner chunkScanner, String scanId, ChunkAnalyzer analyzer) {
        this(chunkScanner, scanId, analyzer, null, new BinaryChunkDb(scanId, analyzer.getId()));
    }

    /** 从已有数据库恢复会话。 */
    ScanSession(ChunkScanner chunkScanner, String scanId, ChunkAnalyzer analyzer, ChunkDb existingDb) {
        this(chunkScanner, scanId, analyzer, null, existingDb);
    }

    /** 新建会话并应用任务级配置。 */
    ScanSession(ChunkScanner chunkScanner, String scanId, ChunkAnalyzer analyzer, TaskConfig taskConfig) {
        this(chunkScanner, scanId, analyzer, taskConfig, new BinaryChunkDb(scanId, analyzer.getId()));
    }

    /**
     * 完整构造器。
     * @param scanId 扫描任务唯一标识
     * @param analyzer 使用的分析器实例
     * @param taskConfig 任务级配置覆盖（可为 null，使用全局默认）
     * @param existingDb 已有数据库实例（用于恢复扫描）
     */
    ScanSession(ChunkScanner chunkScanner, String scanId, ChunkAnalyzer analyzer, TaskConfig taskConfig, ChunkDb existingDb) {
        this.chunkScanner = chunkScanner;
        this.scanId = scanId;
        this.analyzer = analyzer;
        this.db = existingDb;
        // 合并任务配置与全局配置：非 null 字段覆盖，null 字段继承全局默认
        this.sessionConfig = (taskConfig != null ? taskConfig : new TaskConfig()).applyTo(this.chunkScanner.config);
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
        ChunkScannerMod.LOGGER.debug("[scan:{}] Session started (analyzer={}, threads={}, radius={})",
                scanId, analyzer.getId(), sessionConfig.workerThreads, sessionConfig.scanRadiusMultiplier);
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
        ChunkScannerMod.LOGGER.debug("[scan:{}] Session stopped (scanned={}, found={}, errors={})",
                scanId, totalScannedChunks.get(), totalFoundChunks.get(), totalErrors.get());
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

        long packed = CoreUtil.packChunkPos(cx, cz);
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

        // 每 ~200 ticks 输出一次调试统计
        debugTickCounter++;
        debugSubmittedTotal += submitted;
        if (debugTickCounter >= 200) {
            int queueSize = pendingChunks.size();
            ChunkScannerMod.LOGGER.debug(
                    "[scan:{}] Tick stats: submitted={}/200t rate={}/tick queue={} scanned={} found={} errors={}",
                    scanId, debugSubmittedTotal, tasksPerTick, queueSize,
                    totalScannedChunks.get(), totalFoundChunks.get(), totalErrors.get());
            debugTickCounter = 0;
            debugSubmittedTotal = 0;
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
            long packed = CoreUtil.packChunkPos(t.cx, t.cz);
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
                long packed = CoreUtil.packChunkPos(cx, cz);
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

        ChunkStatusBreakdown bd = new ChunkScanner.ChunkStatusBreakdown(
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

    // ==================== 内部类型 ====================

    /** 工作线程池完成的分析结果，通过 resultQueue 传回主线程。 */
    private record TaskResult(int foundCount, int errorCount, int cx, int cz, String dimId, long timestamp, String info) {}

    /** 待扫描区块条目。按 lastScanTime 排序实现优先队列（最旧优先）。 */
    private record ChunkEntry(int cx, int cz, long lastScanTime) implements Comparable<ChunkEntry> {
        @Override public int compareTo(ChunkEntry o) { return Long.compare(lastScanTime, o.lastScanTime); }
        /** 将区块坐标打包为一个 long，兼容 packChunkPos。 */
        long packedPos() { return CoreUtil.packChunkPos(cx, cz); }
    }

    // ==================== Getters ====================
    /** @return 当前 tick 的扫描速率。 */
    public int getTasksPerTick() { return tasksPerTick; }
}
package com.billy65536.chunkscanner;

/**
 * 配置文件数据模型。
 * 全局配置仅作为默认值；每个扫描任务持有自己的副本，可独立修改。
 */
public class ChunkScannerConfig {

    /** 最小重访间隔（秒）。默认 60。 */
    public int minRevisitIntervalSec = 60;

    /** 每 tick 最大 chunk 数。默认 16。 */
    public int maxTasksPerTick = 16;

    /** 初始每 tick chunk 数。默认 2。 */
    public int initialTasksPerTick = 2;

    /** 目标 tick 耗时（纳秒）。默认 5ms。 */
    public long targetTickNs = 5_000_000L;

    /** 批量刷写间隔（tick）。默认 100。 */
    public int flushIntervalTicks = 100;

    /** 工作线程数。默认 2。 */
    public int workerThreads = 2;

    /** 扫描视距倍率。默认 1.0。 */
    public double scanRadiusMultiplier = 1.0;

    /** 创建一份配置副本，供每个扫描任务独立持有。 */
    public ChunkScannerConfig copy() {
        ChunkScannerConfig c = new ChunkScannerConfig();
        c.minRevisitIntervalSec = this.minRevisitIntervalSec;
        c.maxTasksPerTick = this.maxTasksPerTick;
        c.initialTasksPerTick = this.initialTasksPerTick;
        c.targetTickNs = this.targetTickNs;
        c.flushIntervalTicks = this.flushIntervalTicks;
        c.workerThreads = this.workerThreads;
        c.scanRadiusMultiplier = this.scanRadiusMultiplier;
        return c;
    }
}

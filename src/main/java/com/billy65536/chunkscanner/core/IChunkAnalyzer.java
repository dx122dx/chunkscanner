package com.billy65536.chunkscanner.core;

import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import com.billy65536.chunkscanner.core.db.DbPackage;

/**
 * 区块分析器接口。
 *
 * 分析器接收一个已加载的 WorldChunk 和一个数据库包（{@link DbPackage}），
 * 自行遍历区块内的 BlockEntity 并决定保存什么数据。
 * 主库通过 {@link DbPackage#main()} 获取，附属库通过 {@link DbPackage#sub(String)} 按需创建。
 * 返回 AnalyzeResult 以精确表示处理状态。
 *
 * 每个分析器有三个属性：id（唯一不变）、name（本地化显示名）、description（本地化描述）。
 */
public interface IChunkAnalyzer {

    /**
     * 分析一个区块。
     *
     * @param chunk 已加载的 WorldChunk
     * @param cx    区块 X 坐标
     * @param cz    区块 Z 坐标
     * @param dimId 当前维度的完整标识符字符串（如 "minecraft:overworld"）
     * @param pkg   数据库包句柄（主库与附属库的唯一入口）
     * @param now   当前时间戳（毫秒）
     * @return 分析结果，包含 bitflag 状态和可选信息
     */
    AnalyzeResult analyze(WorldChunk chunk, int cx, int cz, String dimId, DbPackage pkg, long now);

    /**
     * 分析一个区块（带 World 引用，用于跨区块方块实体查询）。
     * 默认实现忽略 world 参数，保持向后兼容。
     *
     * @param world 可选的 World 引用，用于查询相邻区块的方块实体
     */
    default AnalyzeResult analyze(WorldChunk chunk, int cx, int cz, String dimId, DbPackage pkg, long now, World world) {
        return analyze(chunk, cx, cz, dimId, pkg, now);
    }

    /** 唯一标识符，不可变，用于注册和命令选择。 */
    Identifier getId();

    /** 显示名称，用于 GUI 展示。 */
    Text getName();

    /** 描述文本，用于悬停提示。 */
    Text getDescription();
}

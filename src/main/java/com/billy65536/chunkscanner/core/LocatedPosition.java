package com.billy65536.chunkscanner.core;

/**
 * 表示游戏世界中的一个具体位置（维度 + 方块坐标）。
 *
 * <p>用于数据库视图中的"位置"列，将原本分散的 Dimension、X、Y、Z 四列合并为一列显示，
 * 同时提供点击创建 Xaero 路径点的支持。</p>
 */
public record LocatedPosition(String dimensionId, int x, int y, int z) {

    /** 格式化为可读显示文本。例如："minecraft:overworld (123, 64, -456)" */
    @Override
    public String toString() {
        String shortDim = shortenDimension(dimensionId);
        return shortDim + " (" + x + ", " + y + ", " + z + ")";
    }

    /** 压缩维度名称（去掉 "minecraft:" 前缀）。 */
    public static String shortenDimension(String dimensionId) {
        if (dimensionId == null) return "?";
        if (dimensionId.startsWith("minecraft:")) return dimensionId.substring(10);
        return dimensionId;
    }
}

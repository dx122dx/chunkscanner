package com.billy65536.chunkscanner.core;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 数据库展示提供者接口。
 *
 * <p>将数据库文件的元信息读取与展示解耦，允许不同的数据库格式通过实现此接口来提供浏览能力。
 * DatabaseScreen 通过此接口访问数据库，不直接依赖具体实现。</p>
 *
 * <p>视图类型的注册通过 {@link DbViewProviderRegistry.Type} 和 {@link DbViewProviderRegistry} 完成。</p>
 *
 * <p>数据访问（文件路径、条目、元数据等）通过 {@link #getDb()} 直接访问底层 {@link ChunkDb} 实例，
 * 不再需要将 ChunkDb 操作转发到 {@link DbViewProvider} 接口。</p>
 */
public interface DbViewProvider {

    /** 获取底层数据库实例，用于直接执行数据访问操作。 */
    ChunkDb getDb();

    /**
     * 是否为特化视图（提供结构化展示而非原始键值对）。
     * 返回 true 时，DatabaseScreen 会按结构化方式渲染而非十六进制。
     */
    default boolean isSpecialized() { return false; }

    /**
     * 获取结构化行数据，用于特化视图的渲染。
     * 每行是一个字符串数组，表示该行的各列内容。
     * 仅在 {@link #isSpecialized()} 返回 true 时使用。
     */
    default List<String[]> getSpecializedRows() { return List.of(); }

    /**
     * 获取特化视图的列标题。
     * 数组长度必须与 {@link #getSpecializedRows()} 每行的列数一致。
     * 仅在 {@link #isSpecialized()} 返回 true 时使用。
     */
    default String[] getSpecializedHeaders() { return new String[0]; }

    // ==================== 位置 ====================

    /**
     * 返回指定行对应的世界位置，用于点击创建路径点。
     * 行索引对应 {@link #getSpecializedRows()} 返回列表中相同位置的行。
     * 返回 null 表示该行没有位置信息。
     */
    default LocatedPosition getPositionAt(int rowIndex) { return null; }

    /**
     * 返回指定行的结构化数据（各列值数组）。
     * 行索引对应 {@link #getSpecializedRows()} 返回列表中相同位置的行。
     * 返回 null 表示该行不存在。
     */
    default String[] getRowAt(int rowIndex) {
        if (!isSpecialized()) return null;
        List<String[]> rows = getSpecializedRows();
        if (rowIndex < 0 || rowIndex >= rows.size()) return null;
        return rows.get(rowIndex);
    }

    /**
     * 获取特化视图的单元格级 tooltip。
     *
     * <p>返回一个 Map&lt;行索引, Map&lt;列标题, tooltip文本列表&gt;&gt;。
     * 行索引对应 {@link #getSpecializedRows()} 的返回顺序。
     * 仅在 {@link #isSpecialized()} 返回 true 时使用。</p>
     *
     * @return 单元格 tooltip 映射，默认返回空 Map
     */
    default Map<Integer, Map<String, List<Text>>> getSpecializedCellTooltips() {
        return Collections.emptyMap();
    }

    /**
     * 获取特化视图的单元格级文字颜色。
     *
     * <p>返回一个 Map&lt;行索引, Map&lt;列标题, 颜色值(ARGB)&gt;&gt;。
     * 行索引对应 {@link #getSpecializedRows()} 的返回顺序。
     * 仅在 {@link #isSpecialized()} 返回 true 时使用。</p>
     *
     * @return 单元格颜色映射，默认返回空 Map
     */
    default Map<Integer, Map<String, Integer>> getSpecializedCellColors() {
        return Collections.emptyMap();
    }

    // ==================== 筛选 ====================

    /**
     * 是否支持筛选功能。
     * 返回 true 时，DatabaseScreen 会在视图选择器右侧显示"..."按钮。
     */
    default boolean supportsFilter() { return false; }

    /**
     * "..."按钮的颜色。默认灰色，筛选激活时返回高亮色。
     */
    default int getFilterButtonColor() { return 0xFF888888; }

    /**
     * 当前是否有筛选条件生效。
     */
    default boolean isFilterActive() { return false; }

    /**
     * 创建筛选界面悬浮窗。返回 null 表示无筛选 UI。
     */
    default Screen createFilterScreen(Screen parent) { return null; }
}

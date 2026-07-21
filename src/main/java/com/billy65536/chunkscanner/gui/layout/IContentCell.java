package com.billy65536.chunkscanner.gui.layout;


/**
 * 表格单元格内容的密封接口。
 *
 * <p>每个单元格可以是以下三种类型之一：
 * <ul>
 *   <li>{@link TextCell} — 带颜色和可选 tooltip 的文本</li>
 *   <li>{@link PositionCell} — 世界位置（渲染为青色文本，可点击创建路径点）</li>
 *   <li>{@link ItemCell} — 物品图标（16×16）</li>
 * </ul>
 *
 * <p>渲染时通过 {@code instanceof} 分派到对应渲染逻辑。</p>
 */
public sealed interface IContentCell permits TextCell, PositionCell, ItemCell {}
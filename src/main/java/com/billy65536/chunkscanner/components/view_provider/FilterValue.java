package com.billy65536.chunkscanner.components.view_provider;

import com.billy65536.infrastructure.core.reflect.FlatConfigs;

/**
 * 统一筛选值包装：持有一个筛选文本值与其匹配模式（type）。
 *
 * <p>实现 {@link FlatConfigs.Serializable}，使 {@code QShopFilterConfig} 可经 {@link FlatConfigs}
 * 反射驱动（parse/copy/merge/toString/isAllNull）。</p>
 *
 * <h2>序列化</h2>
 * <p>{@link #serialize()} 输出 {@code <qualifier><value>}（如 {@code +RES} / {@code -bob} /
 * {@code !123} / {@code @x}），前缀为 {@link FilterType} 的 qualifier。不含空白，
 * {@code =} 不在前置位置，满足 {@link FlatConfigs.Serializable} 的输出约束。</p>
 *
 * <h2>解析（值首符号）</h2>
 * <p>{@link #deserialize(String)} 按首字符 qualifier 解析：{@code +xxx}(含) / {@code !xxx}(全) /
 * {@code -xxx}(除) / {@code @xxx}(正则)，符号决定 type、其余为值；无 qualifier 的裸值视为默认含
 * （CONTAINS）。往返无损：{@code deserialize(serialize(x))} 与 {@code x} 等价。</p>
 *
 * <p>flags 维度不接受正则（{@code @}）：该约束由上游（GUI 的匹配模式按钮 / 未来命令层）保证，
 * 本通用包装对 {@code @} 一律按 REGEX 解读，不在此处区分维度。</p>
 */
public final class FilterValue implements FlatConfigs.Serializable {
    /** 匹配模式，复用 {@link QShopFilter} 的 PATTERN_* 常量。 */
    private int type;
    /** 筛选文本值。 */
    private String value;

    public FilterValue(String value, int type) {
        this.value = value;
        this.type = type;
    }

    /** 默认含模式（CONTAINS）的便捷构造。 */
    public FilterValue(String value) {
        this(value, QShopFilter.PATTERN_CONTAINS);
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    @Override
    public String serialize() {
        return FilterType.fromType(type).getQualifier() + (value == null ? "" : value);
    }

    /**
     * 从单行字符串还原（详见类文档）。按首字符 qualifier 解析匹配模式。
     *
     * @throws IllegalArgumentException 输入为 null 时
     */
    public static FilterValue deserialize(String raw) {
        if (raw == null) throw new IllegalArgumentException("null input");
        if (raw.isEmpty()) return new FilterValue("", QShopFilter.PATTERN_CONTAINS);
        FilterType ft = FilterType.fromQualifier(raw.substring(0, 1));
        if (ft != null) {
            // 首字符是 qualifier：符号决定 type，其余为值
            return new FilterValue(raw.substring(1), ft.ordinal());
        }
        // 无 qualifier 的裸值：默认含
        return new FilterValue(raw, QShopFilter.PATTERN_CONTAINS);
    }

    @Override
    public boolean isEmpty() {
        return value == null || value.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FilterValue f)) return false;
        return type == f.type && (value == null ? f.value == null : value.equals(f.value));
    }

    @Override
    public int hashCode() {
        return 31 * type + (value == null ? 0 : value.hashCode());
    }

    @Override
    public String toString() {
        return serialize();
    }
}

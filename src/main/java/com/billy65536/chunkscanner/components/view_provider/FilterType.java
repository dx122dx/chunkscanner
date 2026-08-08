package com.billy65536.chunkscanner.components.view_provider;

/**
 * 筛选匹配模式，及其在序列化字符串中的前缀符号（qualifier）。
 *
 * <p>枚举顺序与 {@link QShopFilter} 的 {@code PATTERN_*} 常量严格一致（CONTAINS=0 / EXCLUDE=1 /
 * EXACT=2 / REGEX=3），因此 {@link #ordinal()} 可直接当作 PATTERN 常量值使用。</p>
 *
 * <p>序列化时以 qualifier 作为前缀（如 {@code +RES} / {@code -bob} / {@code !123} / {@code @x}），
 * 与命令行 {@code xxx=+xxx} 的值首符号语义统一，保证 {@code deserialize(serialize(x))} 无损往返。</p>
 */
public enum FilterType {
    CONTAINS("+"),
    EXCLUDE("-"),
    EXACT("!"),
    REGEX("@");

    private final String qualifier;

    FilterType(String qualifier) {
        this.qualifier = qualifier;
    }

    /** 序列化前缀符号。 */
    public String getQualifier() {
        return qualifier;
    }

    /** 按 PATTERN 常量值（即 ordinal）取枚举。 */
    public static FilterType fromType(int type) {
        return values()[type];
    }

    /** 按 qualifier 首字符取枚举；非 qualifier 返回 null。 */
    public static FilterType fromQualifier(String q) {
        if (q == null || q.isEmpty()) return null;
        for (FilterType ft : values()) {
            if (ft.qualifier.equals(q)) return ft;
        }
        return null;
    }
}

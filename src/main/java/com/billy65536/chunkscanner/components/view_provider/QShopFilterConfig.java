package com.billy65536.chunkscanner.components.view_provider;

import com.billy65536.infrastructure.core.reflect.FlatConfigs;

import java.util.List;

/**
 * QShop 筛选状态的扁平配置子对象。
 *
 * <p>字段分两类：</p>
 * <ul>
 *   <li><b>文本筛选维度</b>（dim/owner/name/id/flags）：{@link FilterValue}，值 + 匹配模式，
 *       模式由值首符号限定符表达（{@link FilterType}）。</li>
 *   <li><b>数值维度</b>（mode/pmin/pmax/qmin/qmax/sort）：{@link Integer}，本身就是枚举序号或阈值，
 *       不存在"匹配模式"语义，直接用原生类型，由 {@link FlatConfigs} 内建转换处理。</li>
 * </ul>
 *
 * <p>parse/copy/merge/toString/keysOf 全部由 {@link FlatConfigs} 反射驱动，消除逐字段样板。
 * {@link #KNOWN_KEYS} 由 {@link FlatConfigs#keysOf} 反射生成，与解析器物理同源，杜绝键名漂移。</p>
 */
public class QShopFilterConfig {

    @FlatConfigs.Key("dim")
    public FilterValue dimFilter;

    @FlatConfigs.Key("owner")
    public FilterValue ownerFilter;

    @FlatConfigs.Key("name")
    public FilterValue itemFilter;

    @FlatConfigs.Key("id")
    public FilterValue itemIdFilter;

    @FlatConfigs.Key("flags")
    public FilterValue flagsFilter;

    /** 交易模式：0=全部, 1=出售, 2=收购。 */
    @FlatConfigs.Key("mode")
    public Integer modeFilter;

    @FlatConfigs.Key("pmin")
    public Integer priceMinFilter;

    @FlatConfigs.Key("pmax")
    public Integer priceMaxFilter;

    @FlatConfigs.Key("qmin")
    public Integer qtyMinFilter;

    @FlatConfigs.Key("qmax")
    public Integer qtyMaxFilter;

    /** 排序模式：见 {@link QShopFilter} 的 {@code SORT_*} 常量。 */
    @FlatConfigs.Key("sort")
    public Integer sortMode;

    public QShopFilterConfig() {}

    /** 从 key=value 字符串解析（文本维度的值首符号表达匹配模式）。 */
    public static QShopFilterConfig parse(String s) {
        return FlatConfigs.createFrom(s, QShopFilterConfig.class);
    }

    /** 返回独立副本。 */
    public QShopFilterConfig copy() {
        return FlatConfigs.copy(this);
    }

    /** 合并增量（非 null 字段覆盖）。 */
    public QShopFilterConfig merge(QShopFilterConfig delta) {
        return FlatConfigs.merge(this, delta);
    }

    /** 紧凑单行展示（按展示键，FilterValue 经 serialize 输出 {@code <限定符><值>}）。 */
    public String toDisplayString() {
        return FlatConfigs.toString(this);
    }

    /** 全部维度为空/未设置。 */
    public boolean isAllNull() {
        return FlatConfigs.isAllNull(this);
    }

    /** 命令补全用的可识别键（小写别名）。 */
    public static final List<String> KNOWN_KEYS = FlatConfigs.keysOf(QShopFilterConfig.class);
}

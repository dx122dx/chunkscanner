package com.billy65536.chunkscanner.components.analyzer;

/**
 * QShop 数据契约常量。
 */
public final class QShopContract {

    private QShopContract() {}

    // ==================== 标志位 (flags) ====================

    /** 物品注册名通过译名映射表恢复（R）。 */
    public static final int FLAG_ID_RECOVERED = 0x01;
    /** 此记录包含增强数据存储在子数据库 1（E）。 */
    public static final int FLAG_ENHANCED_DATA = 0x02;
    /** 潜影盒已展开（S），内容物作为商品。 */
    public static final int FLAG_SHULKER_EXPANDED = 0x04;
    /** 成书（B），商品名已替换为标题。 */
    public static final int FLAG_BOOK = 0x08;

    // ==================== 特殊值 ====================

    /** quantity 的最大值 (24-bit)，用作"无限"的哨兵值。 */
    public static final int INFINITE_QUANTITY = 0xFFFFFF;

    // ==================== 模式 (mode) ====================

    /** 出售模式。 */
    public static final byte MODE_SELL = 0;
    /** 收购模式。 */
    public static final byte MODE_BUY = 1;
}

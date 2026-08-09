package com.billy65536.chunkscanner.components.view_provider;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.components.analyzer.QShopContract;
import com.billy65536.chunkscanner.components.analyzer.QShopDbAdapter;
import com.billy65536.chunkscanner.core.IDbViewProvider;
import com.billy65536.chunkscanner.core.DbViewProviderRegistry;
import com.billy65536.chunkscanner.core.LocatedPosition;
import com.billy65536.chunkscanner.core.db.DbPackage;
import com.billy65536.chunkscanner.gui.layout.TableLayoutBuilder;
import com.billy65536.chunkscanner.gui.layout.ILayout;

/**
 * QShop 分析器特化的 DbViewProvider。
 *
 * <p>通过 {@link QShopDbAdapter} 解析 qshop 分析器生成的二进制 KV 数据，将原始字节转换为可读的商店信息。
 * 筛选状态与匹配逻辑由 {@link QShopFilter} 负责，展示辅助由 {@link QShopDisplayUtil} 负责，
 * 本类仅承担展示布局的构建与接口适配。</p>
 */
public class QShopDbViewProvider implements IDbViewProvider {

    private final DbPackage pkg;
    private final QShopDbAdapter ad;

    /** 缓存筛选并排序后的记录。仅渲染线程访问，无需同步。 */
    private List<QShopDbAdapter.Record> cachedFilteredSorted;
    private int cacheVersion = 0;

    /** 筛选状态与匹配逻辑。 */
    private final QShopFilter filter = new QShopFilter();

    public QShopDbViewProvider(DbPackage pkg) {
        this.pkg = pkg;
        this.ad = pkg.getAdaptor(QShopDbAdapter.class);
    }

    // ==================== 筛选接口 ====================

    @Override
    public boolean supportsFilter() { return true; }

    @Override
    public int getFilterButtonColor() {
        return isFilterActive() ? 0xFF55FF55 : 0xFF888888;
    }

    @Override
    public boolean isFilterActive() {
        return filter.isFilterActive();
    }

    @Override
    public Screen createFilterScreen(Screen parent) {
        return new QShopFilterScreen(parent, this.filter);
    }

    // ==================== ViewLayout ====================

    private static final String[] HEADERS = {"Pos", "Owner", "Type", "Qty", "Item", "Price", "ID", "Preview", "Flags", "Update Time"};

    @Override
    public ILayout getLayout(TextRenderer textRenderer) {
        List<QShopDbAdapter.Record> matched = getFilteredSortedRecords();
        int metaCount;
        try {
            metaCount = ad.getScannedChunkCount();
        } catch (Exception e) {
            metaCount = 0;
        }

        TableLayoutBuilder b = new TableLayoutBuilder(textRenderer, metaCount, HEADERS);
        for (QShopDbAdapter.Record r : matched) {
            boolean shulker = (r.flags() & QShopContract.FLAG_SHULKER_EXPANDED) != 0;
            Text modeText;
            Text quantityText;
            if (r.mode() == QShopContract.MODE_SELL) {
                modeText = Text.translatable("chunkscanner.filter.mode.sell");
                if (r.quantity() == QShopContract.INFINITE_QUANTITY) {
                    quantityText = Text.translatable("chunkscanner.qshop.infinite");
                } else if (r.quantity() == 0) {
                    quantityText = Text.translatable("chunkscanner.qshop.out_of_stock");
                } else {
                    quantityText = Text.literal(String.valueOf(
                            shulker ? filter.getEffectiveQty(r) : r.quantity()));
                }
            } else {
                modeText = Text.translatable("chunkscanner.filter.mode.buy");
                if (r.quantity() == QShopContract.INFINITE_QUANTITY) {
                    quantityText = Text.translatable("chunkscanner.qshop.infinite");
                } else if (r.quantity() == 0) {
                    quantityText = Text.translatable("chunkscanner.qshop.out_of_space");
                } else {
                    quantityText = Text.literal(String.valueOf(
                            shulker ? filter.getEffectiveQty(r) : r.quantity()));
                }
            }

            LocatedPosition pos = new LocatedPosition(r.dimId(), r.x(), r.y(), r.z());

            TableLayoutBuilder.RowBuilder row = b.addRow()
                    .position(pos)
                    .text(r.owner())
                    .text(modeText)
                    .text(quantityText)
                    .text(QShopDisplayUtil.getEffectiveItemName(r))
                    .text(QShopDisplayUtil.formatPrice(r.price()));

            if (shulker) {
                List<Text> unitPriceTip = QShopDisplayUtil.buildShulkerUnitPriceTooltip(r);
                if (unitPriceTip != null) {
                    row.withColor(0xFFFF55FF); // 紫色
                    row.withTooltip(unitPriceTip);
                }
            }

            row.text(r.itemId());

            // Detail 列物品图标和 tooltip
            ItemStack icon = QShopDisplayUtil.parseDetailItemStack(r);
            if (icon != null) {
                row.item(icon);
            } else {
                row.blank();
            }
            List<Text> detailTips = QShopDisplayUtil.buildDetailTooltip(r);
            if (detailTips != null) {
                row.withTooltip(detailTips);
            }

            // Flags 列
            String flagsStr = QShopDisplayUtil.formatFlagsShort(r.flags());
            List<Text> flagTips = QShopDisplayUtil.formatFlagsTooltip(r.flags());
            row.text(flagsStr);
            if (flagTips != null) {
                row.withTooltip(flagTips);
            }

            // Update Time 列
            String updateTime = QShopDisplayUtil.formatTimestamp(r.timestamp());
            row.text(updateTime);

            if (r.enhancementTimestamp() > 0) {
                row.withColor(0xFF55FFFF) // Aqua
                   .withTooltip(List.of(
                    Text.translatable("chunkscanner.qshop.enhancement_update_time", QShopDisplayUtil.formatTimestamp(r.enhancementTimestamp()))
                ));
            }

            row.done();
        }
        return b.build();
    }

    /** 获取筛选并排序后的记录列表。 */
    private List<QShopDbAdapter.Record> getFilteredSortedRecords() {
        if (cacheVersion == filter.getCacheVersion() && cachedFilteredSorted != null) {
            return cachedFilteredSorted;
        }
        List<QShopDbAdapter.Record> records = ad.getAllRecords();
        List<QShopDbAdapter.Record> matched = new ArrayList<>();
        for (QShopDbAdapter.Record r : records) {
            if (filter.matches(r)) {
                matched.add(r);
            }
        }
        if (filter.getSortMode() != QShopFilter.SORT_NONE && matched.size() > 1) {
            matched.sort(filter.getSortComparator());
        }
        cachedFilteredSorted = matched;
        cacheVersion = filter.getCacheVersion();
        return matched;
    }

    // ==================== 类型描述符 ====================

    /** QShop 视图类型描述符：解析 QShop 数据为结构化展示。对应 adaptorId {@code chunkscanner:qshop}。 */
    public static class Type implements DbViewProviderRegistry.ITypeDescriptor {
        @Override
        public Identifier getId() { return ChunkScannerMod.id("qshop_view"); }

        @Override
        public Text getName() {
            return Text.translatable("chunkscanner.dbview.qshop.name");
        }

        @Override
        public Text getDescription() {
            return Text.translatable("chunkscanner.dbview.qshop.desc");
        }

        @Override
        public Set<Identifier> applicableAdaptors() {
            return Set.of(ChunkScannerMod.id("qshop"));
        }

        @Override
        public IDbViewProvider create(DbPackage pkg) {
            return new QShopDbViewProvider(pkg);
        }
    }
}

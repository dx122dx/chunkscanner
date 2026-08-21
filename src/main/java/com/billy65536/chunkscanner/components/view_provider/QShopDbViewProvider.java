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
import com.billy65536.chunkscanner.config.ChunkScannerConfig;
import com.billy65536.chunkscanner.config.TaskConfig;
import com.billy65536.chunkscanner.core.CoreUtil;
import com.billy65536.chunkscanner.core.IDbViewProvider;
import com.billy65536.chunkscanner.core.DbViewProviderRegistry;
import com.billy65536.chunkscanner.core.LocatedPosition;
import com.billy65536.chunkscanner.core.db.DbPackage;
import com.billy65536.chunkscanner.core.navigation.ChunkScannerNavigation;
import com.billy65536.chunkscanner.integration.XaeroWaypointHelper;
import com.billy65536.infrastructure.core.gui.layout.ILayout;
import com.billy65536.infrastructure.core.gui.layout.TableLayout;
import com.billy65536.infrastructure.core.gui.layout.TableLayoutBuilder;
import com.billy65536.infrastructure.core.gui.layout.TextCell;

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
    private final TaskConfig taskConfig;

    /** 缓存筛选并排序后的记录。仅渲染线程访问，无需同步。 */
    private List<QShopDbAdapter.Record> cachedFilteredSorted;
    private int cacheVersion = 0;

    /** 筛选状态与匹配逻辑。 */
    private final QShopFilter filter = new QShopFilter();

    public QShopDbViewProvider(DbPackage pkg) {
        this.pkg = pkg;
        this.ad = pkg.getAdaptor(QShopDbAdapter.class);
        this.taskConfig = pkg.getTaskConfig();
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

        TableLayout.ColumnSpec[] specs = {
                TableLayout.ColumnSpec.ofWeight(1, TableLayout.ColumnSpec.Align.LEFT).floorWidth(90),
                TableLayout.ColumnSpec.ofWeight(1, TableLayout.ColumnSpec.Align.LEFT).floorWidth(60),
                TableLayout.ColumnSpec.ofWeight(1, TableLayout.ColumnSpec.Align.LEFT).floorWidth(50),
                TableLayout.ColumnSpec.ofWeight(1, TableLayout.ColumnSpec.Align.LEFT).floorWidth(40),
                TableLayout.ColumnSpec.ofWeight(2, TableLayout.ColumnSpec.Align.LEFT).floorWidth(80),
                TableLayout.ColumnSpec.ofWeight(1, TableLayout.ColumnSpec.Align.LEFT).floorWidth(50),
                TableLayout.ColumnSpec.ofWeight(1, TableLayout.ColumnSpec.Align.LEFT).floorWidth(50),
                TableLayout.ColumnSpec.ofFixed(24, TableLayout.ColumnSpec.Align.LEFT),
                TableLayout.ColumnSpec.ofWeight(1, TableLayout.ColumnSpec.Align.LEFT).floorWidth(50),
                TableLayout.ColumnSpec.ofWeight(1, TableLayout.ColumnSpec.Align.LEFT).floorWidth(80),
        };
        TableLayoutBuilder b = new TableLayoutBuilder(textRenderer, HEADERS, specs);
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

            String priceStr = QShopDisplayUtil.formatPrice(r.price());
            String flagsStr = QShopDisplayUtil.formatFlagsShort(r.flags());
            String updateTime = QShopDisplayUtil.formatTimestamp(r.timestamp());
            ItemStack icon = QShopDisplayUtil.parseDetailItemStack(r);
            List<Text> detailTips = QShopDisplayUtil.buildDetailTooltip(r);

            LocatedPosition pos = new LocatedPosition(r.dimId(), r.x(), r.y(), r.z());
            String[] rowText = {
                    pos.toString(),
                    r.owner(),
                    modeText.getString(),
                    quantityText.getString(),
                    QShopDisplayUtil.getEffectiveItemName(r).getString(),
                    priceStr,
                    r.itemId(),
                    icon != null ? icon.getName().getString() : "",
                    flagsStr,
                    updateTime,
            };

            TableLayoutBuilder.RowBuilder row = b.addRow()
                    .position(pos.toString(),
                            () -> createWaypoint(pos, rowText),
                            () -> enqueueNavigation(pos))
                    .text(r.owner())
                    .text(modeText)
                    .text(quantityText)
                    .text(QShopDisplayUtil.getEffectiveItemName(r));

            // Price 列：潜影盒条目显示紫色并附带单价 tooltip
            if (shulker) {
                List<Text> unitPriceTip = QShopDisplayUtil.buildShulkerUnitPriceTooltip(r);
                if (unitPriceTip != null) {
                    row.cell(TextCell.of(priceStr).withColor(0xFFFF55FF)
                            .withTooltip(unitPriceTip.toArray(new Text[0])));
                } else {
                    row.text(priceStr);
                }
            } else {
                row.text(priceStr);
            }

            row.text(r.itemId());

            // Preview 列：物品图标（悬停显示原版物品 tooltip），无图标时挂 detail tooltip
            if (icon != null) {
                row.item(icon);
            } else if (detailTips != null) {
                row.cell(TextCell.of("").withTooltip(detailTips.toArray(new Text[0])));
            } else {
                row.blank();
            }

            // Flags 列
            List<Text> flagTips = QShopDisplayUtil.formatFlagsTooltip(r.flags());
            row.cell(TextCell.of(flagsStr)
                    .withTooltip(flagTips != null ? flagTips.toArray(new Text[0]) : null));

            // Update Time 列：增强更新时间显示青色并附带 tooltip
            if (r.enhancementTimestamp() > 0) {
                row.cell(TextCell.of(updateTime).withColor(0xFF55FFFF)
                        .withTooltip(new Text[]{Text.translatable("chunkscanner.qshop.enhancement_update_time",
                                QShopDisplayUtil.formatTimestamp(r.enhancementTimestamp()))}));
            } else {
                row.text(updateTime);
            }

            row.done();
        }
        return b.build();
    }

    /** 位置列左键：合并任务配置后以占位符替换生成 Xaero 路径点。 */
    private void createWaypoint(LocatedPosition pos, String[] rowText) {
        ChunkScannerConfig cfg = taskConfig != null
                ? taskConfig.applyTo(ChunkScannerMod.getConfig())
                : ChunkScannerMod.getConfig();
        String wpName = CoreUtil.replacePlaceholders(cfg.integration.xaero.name, HEADERS, rowText);
        String wpInit = CoreUtil.replacePlaceholders(cfg.integration.xaero.initials, HEADERS, rowText);
        String wpGroup = CoreUtil.replacePlaceholders(cfg.integration.xaero.group, HEADERS, rowText);
        XaeroWaypointHelper.tryCreateWaypoint(pos, wpName, wpInit, wpGroup);
        ChunkScannerMod.LOGGER.info("Waypoint created: name template='{}' -> '{}', initials='{}', group='{}'",
                cfg.integration.xaero.name, wpName, wpInit, wpGroup);
    }

    /** 位置列右键：将坐标加入全局导航队列。 */
    private void enqueueNavigation(LocatedPosition pos) {
        ChunkScannerNavigation nav = ChunkScannerNavigation.get();
        nav.enqueue(pos.x(), pos.y(), pos.z(), pos.dimensionId());
        ChunkScannerMod.LOGGER.info("Nav enqueue: ({}, {}, {}) dim={} queue size={}",
                pos.x(), pos.y(), pos.z(), pos.dimensionId(), nav.size());
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

package com.billy65536.chunkscanner.components.view_provider;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Set;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.components.analyzer.SignDbAdaptor;
import com.billy65536.chunkscanner.config.ChunkScannerConfig;
import com.billy65536.chunkscanner.config.TaskConfig;
import com.billy65536.chunkscanner.core.CoreUtil;
import com.billy65536.chunkscanner.core.IDbViewProvider;
import com.billy65536.chunkscanner.core.DbViewProviderRegistry;
import com.billy65536.chunkscanner.core.db.DbPackage;
import com.billy65536.chunkscanner.core.LocatedPosition;
import com.billy65536.chunkscanner.core.navigation.ChunkScannerNavigation;
import com.billy65536.chunkscanner.integration.XaeroWaypointHelper;
import com.billy65536.infrastructure.core.gui.layout.ILayout;
import com.billy65536.infrastructure.core.gui.layout.TableLayout;
import com.billy65536.infrastructure.core.gui.layout.TableLayoutBuilder;

/**
 * Sign 分析器特化的 DbViewProvider。
 *
 * <p>通过 {@link SignDbAdaptor} 解析 sign 分析器生成的二进制 KV 数据，将原始字节转换为可读的告示牌信息。
 * 所有字节级解析集中在适配器中，本类仅负责展示布局。</p>
 */
public class SignDbViewProvider implements IDbViewProvider {

    private static final String[] HEADERS = {"位置", "Side", "Line 1", "Line 2", "Line 3", "Line 4"};

    private final SignDbAdaptor ad;
    private final TaskConfig taskConfig;

    /** 缓存解析后的告示牌记录，避免每帧重复解析。 */
    private List<SignDbAdaptor.SignRecord> cachedRecords;
    private volatile boolean cacheValid = false;

    public SignDbViewProvider(DbPackage pkg) {
        this.ad = pkg.getAdaptor(SignDbAdaptor.class);
        this.taskConfig = pkg.getTaskConfig();
    }

    @Override
    public ILayout getLayout(TextRenderer textRenderer) {
        List<SignDbAdaptor.SignRecord> records = getSignRecords();

        TableLayout.ColumnSpec[] specs = {
                TableLayout.ColumnSpec.ofWeight(1, TableLayout.ColumnSpec.Align.LEFT).floorWidth(110),
                TableLayout.ColumnSpec.ofWeight(1, TableLayout.ColumnSpec.Align.LEFT).floorWidth(50),
                TableLayout.ColumnSpec.ofWeight(2, TableLayout.ColumnSpec.Align.LEFT).floorWidth(60),
                TableLayout.ColumnSpec.ofWeight(2, TableLayout.ColumnSpec.Align.LEFT).floorWidth(60),
                TableLayout.ColumnSpec.ofWeight(2, TableLayout.ColumnSpec.Align.LEFT).floorWidth(60),
                TableLayout.ColumnSpec.ofWeight(2, TableLayout.ColumnSpec.Align.LEFT).floorWidth(60),
        };
        TableLayoutBuilder b = new TableLayoutBuilder(textRenderer, HEADERS, specs);
        for (SignDbAdaptor.SignRecord sr : records) {
            LocatedPosition pos = new LocatedPosition(sr.dimId(), sr.x(), sr.y(), sr.z());
            String[] rowText = {pos.toString(), sr.side(), sr.line1(), sr.line2(), sr.line3(), sr.line4()};
            b.addRow()
                    .position(pos.toString(),
                            () -> createWaypoint(pos, rowText),
                            () -> enqueueNavigation(pos))
                    .text(sr.side())
                    .text(sr.line1())
                    .text(sr.line2())
                    .text(sr.line3())
                    .text(sr.line4())
                    .done();
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

    // ==================== Sign 特化展示 ====================

    /**
     * 获取解析后的告示牌记录（委托适配器）。
     * 结果会被缓存，数据不变时不会重复解析。
     */
    public List<SignDbAdaptor.SignRecord> getSignRecords() {
        if (cacheValid && cachedRecords != null) {
            return cachedRecords;
        }
        cachedRecords = ad.getAllRecords();
        cacheValid = true;
        return cachedRecords;
    }

    // ==================== 类型描述符 ====================

    /** Sign 视图类型描述符：解析告示牌数据为可读文本。对应 adaptorId {@code chunkscanner:sign}。 */
    public static class Type implements DbViewProviderRegistry.ITypeDescriptor {
        @Override
        public Identifier getId() { return ChunkScannerMod.id("sign_view"); }

        @Override
        public Text getName() {
            return Text.translatable("chunkscanner.dbview.sign.name");
        }

        @Override
        public Text getDescription() {
            return Text.translatable("chunkscanner.dbview.sign.desc");
        }

        @Override
        public Set<Identifier> applicableAdaptors() {
            return Set.of(ChunkScannerMod.id("sign"));
        }

        @Override
        public IDbViewProvider create(DbPackage pkg) {
            return new SignDbViewProvider(pkg);
        }
    }
}

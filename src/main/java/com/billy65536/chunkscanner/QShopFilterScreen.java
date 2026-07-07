package com.billy65536.chunkscanner;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * QShop 商店数据筛选悬浮窗。
 *
 * 筛选条件：
 *   - 模式：全部 / 出售 / 收购
 *   - 排序：无 / 价格↑↓ / 数量↑↓
 *   - 维度、所有者、商品名：子串匹配（留空 = 不筛选）
 *   - 价格范围：最小/最大（整数，留空 = 不限制）
 *   - 数量范围：最小/最大（整数，留空 = 不限制）
 *
 * 点击"应用"后筛选立即生效，返回上一界面。
 */
public class QShopFilterScreen extends Screen {

    private final Screen parent;
    private final QShopDbViewProvider provider;

    // ==================== 布局常量 ====================

    private static final int DIALOG_W = 240;
    private static final int DIALOG_H = 208;
    private static final int FIELD_W = 124;
    private static final int FIELD_H = 16;
    private static final int RANGE_FIELD_W = 46;
    private static final int ROW_SPACING = 22;
    private static final int LEFT_MARGIN = 8;

    // ==================== 控件 ====================

    private ButtonWidget modeButton;
    private ButtonWidget sortButton;
    private TextFieldWidget dimField;
    private TextFieldWidget ownerField;
    private TextFieldWidget itemField;
    private TextFieldWidget priceMinField;
    private TextFieldWidget priceMaxField;
    private TextFieldWidget qtyMinField;
    private TextFieldWidget qtyMaxField;

    // ==================== 状态 ====================

    private int modeFilter; // 0=全部, 1=出售, 2=收购
    private int sortMode;
    private String dimFilter;
    private String ownerFilter;
    private String itemFilter;
    private String priceMinStr;
    private String priceMaxStr;
    private String qtyMinStr;
    private String qtyMaxStr;

    public QShopFilterScreen(Screen parent, QShopDbViewProvider provider) {
        super(Text.translatable("chunkscanner.filter.qshop.title"));
        this.parent = parent;
        this.provider = provider;

        // 从 provider 加载当前筛选状态
        this.modeFilter = provider.getModeFilter();
        this.sortMode = provider.getSortMode();
        this.dimFilter = provider.getDimFilter() != null ? provider.getDimFilter() : "";
        this.ownerFilter = provider.getOwnerFilter() != null ? provider.getOwnerFilter() : "";
        this.itemFilter = provider.getItemFilter() != null ? provider.getItemFilter() : "";
        this.priceMinStr = provider.getPriceMinFilter() != null
                ? String.valueOf(provider.getPriceMinFilter()) : "";
        this.priceMaxStr = provider.getPriceMaxFilter() != null
                ? String.valueOf(provider.getPriceMaxFilter()) : "";
        this.qtyMinStr = provider.getQtyMinFilter() != null
                ? String.valueOf(provider.getQtyMinFilter()) : "";
        this.qtyMaxStr = provider.getQtyMaxFilter() != null
                ? String.valueOf(provider.getQtyMaxFilter()) : "";
    }

    // ==================== 初始化 ====================

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int dialogLeft = centerX - DIALOG_W / 2;
        int topY = (this.height - DIALOG_H) / 2;
        int fieldX = dialogLeft + DIALOG_W - LEFT_MARGIN - FIELD_W;

        // === 模式切换按钮（左） + 排序切换按钮（右） ===
        modeButton = ButtonWidget.builder(getModeText(), btn -> {
            modeFilter = (modeFilter + 1) % 3;
            btn.setMessage(getModeText());
        }).dimensions(dialogLeft + LEFT_MARGIN, topY + 24, 72, FIELD_H).build();
        addDrawableChild(modeButton);

        sortButton = ButtonWidget.builder(getSortText(), btn -> {
            sortMode = (sortMode + 1) % 5;
            btn.setMessage(getSortText());
        }).dimensions(dialogLeft + LEFT_MARGIN + 80, topY + 24, 82, FIELD_H).build();
        addDrawableChild(sortButton);

        // === 文本筛选字段 ===
        int fy = topY + 48;
        dimField = createTextField(fieldX, fy, dimFilter, "维度...");
        ownerField = createTextField(fieldX, fy + ROW_SPACING, ownerFilter, "所有者...");
        itemField = createTextField(fieldX, fy + ROW_SPACING * 2, itemFilter, "商品...");

        addDrawableChild(dimField);
        addDrawableChild(ownerField);
        addDrawableChild(itemField);

        // === 价格范围（两个小字段，用 "~" 分隔） ===
        int rangeY = fy + ROW_SPACING * 3;
        int rangeFieldRight = dialogLeft + DIALOG_W - LEFT_MARGIN;
        priceMinField = createNumberField(rangeFieldRight - RANGE_FIELD_W * 2 - 16, rangeY,
                RANGE_FIELD_W, priceMinStr);
        priceMaxField = createNumberField(rangeFieldRight - RANGE_FIELD_W, rangeY,
                RANGE_FIELD_W, priceMaxStr);
        addDrawableChild(priceMinField);
        addDrawableChild(priceMaxField);

        // === 数量范围 ===
        int qtyY = rangeY + ROW_SPACING;
        qtyMinField = createDigitField(rangeFieldRight - RANGE_FIELD_W * 2 - 16, qtyY,
                RANGE_FIELD_W, qtyMinStr);
        qtyMaxField = createDigitField(rangeFieldRight - RANGE_FIELD_W, qtyY,
                RANGE_FIELD_W, qtyMaxStr);
        addDrawableChild(qtyMinField);
        addDrawableChild(qtyMaxField);

        // === 底部按钮 ===
        int btnY = topY + DIALOG_H - 24;
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("chunkscanner.filter.apply"),
                btn -> apply())
                .dimensions(dialogLeft + 10, btnY, 56, FIELD_H).build());

        addDrawableChild(ButtonWidget.builder(
                Text.translatable("chunkscanner.filter.reset"),
                btn -> reset())
                .dimensions(dialogLeft + 72, btnY, 56, FIELD_H).build());

        addDrawableChild(ButtonWidget.builder(
                Text.translatable("gui.cancel"),
                btn -> close())
                .dimensions(dialogLeft + 134, btnY, 56, FIELD_H).build());
    }

    /**
     * 创建通用文本输入框。
     * 修复：只用 setText 设置初始值，当文本为空时才使用 setSuggestion 作为占位提示。
     * 避免了 setSuggestion 在已有文本时仍然渲染导致"输入后被附加"的问题。
     */
    private TextFieldWidget createTextField(int x, int y, String initial, String hint) {
        TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, FIELD_W, FIELD_H, Text.literal(""));
        f.setMaxLength(64);
        if (initial != null && !initial.isEmpty()) {
            f.setText(initial);
        } else {
            f.setSuggestion(hint);
        }
        return f;
    }

    /** 创建数字输入框（支持小数，用于价格）。 */
    private TextFieldWidget createNumberField(int x, int y, int w, String initial) {
        TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, w, FIELD_H, Text.literal(""));
        f.setMaxLength(12);
        f.setTextPredicate(t -> t.matches("[0-9.]*"));
        if (initial != null && !initial.isEmpty()) {
            f.setText(initial);
        }
        return f;
    }

    /** 创建纯数字输入框（仅整数，用于数量）。 */
    private TextFieldWidget createDigitField(int x, int y, int w, String initial) {
        TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, w, FIELD_H, Text.literal(""));
        f.setMaxLength(12);
        f.setTextPredicate(t -> t.matches("[0-9]*"));
        if (initial != null && !initial.isEmpty()) {
            f.setText(initial);
        }
        return f;
    }

    private Text getModeText() {
        return switch (modeFilter) {
            case 1 -> Text.translatable("chunkscanner.filter.mode.sell").formatted(Formatting.GREEN);
            case 2 -> Text.translatable("chunkscanner.filter.mode.buy").formatted(Formatting.RED);
            default -> Text.translatable("chunkscanner.filter.mode.all").formatted(Formatting.GRAY);
        };
    }

    private Text getSortText() {
        return switch (sortMode) {
            case QShopDbViewProvider.SORT_PRICE_ASC ->
                    Text.translatable("chunkscanner.filter.sort.price_asc").formatted(Formatting.GREEN);
            case QShopDbViewProvider.SORT_PRICE_DESC ->
                    Text.translatable("chunkscanner.filter.sort.price_desc").formatted(Formatting.GREEN);
            case QShopDbViewProvider.SORT_QTY_ASC ->
                    Text.translatable("chunkscanner.filter.sort.qty_asc").formatted(Formatting.GREEN);
            case QShopDbViewProvider.SORT_QTY_DESC ->
                    Text.translatable("chunkscanner.filter.sort.qty_desc").formatted(Formatting.GREEN);
            default -> Text.translatable("chunkscanner.filter.sort.none").formatted(Formatting.GRAY);
        };
    }

    // ==================== 渲染 ====================

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);

        int centerX = this.width / 2;
        int dialogLeft = centerX - DIALOG_W / 2;
        int topY = (this.height - DIALOG_H) / 2;

        // 对话框背景
        context.fill(dialogLeft, topY, dialogLeft + DIALOG_W, topY + DIALOG_H, 0xCC000000);
        context.drawHorizontalLine(dialogLeft, dialogLeft + DIALOG_W, topY, 0xFF555555);
        context.drawHorizontalLine(dialogLeft, dialogLeft + DIALOG_W, topY + DIALOG_H, 0xFF555555);
        context.drawVerticalLine(dialogLeft, topY, topY + DIALOG_H, 0xFF555555);
        context.drawVerticalLine(dialogLeft + DIALOG_W, topY, topY + DIALOG_H, 0xFF555555);

        // 标题
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("chunkscanner.filter.qshop.title").formatted(Formatting.GOLD),
                centerX, topY + 6, 0xFFFFFF);

        // 行标签
        int labelX = dialogLeft + LEFT_MARGIN;
        int labelY = topY + 52;
        drawLabel(context, "chunkscanner.filter.field.dimension", labelX, labelY);
        drawLabel(context, "chunkscanner.filter.field.owner", labelX, labelY + ROW_SPACING);
        drawLabel(context, "chunkscanner.filter.field.item", labelX, labelY + ROW_SPACING * 2);
        drawLabel(context, "chunkscanner.filter.field.price_range", labelX, labelY + ROW_SPACING * 3);
        drawLabel(context, "chunkscanner.filter.field.qty_range", labelX, labelY + ROW_SPACING * 4);

        // 范围的 "~" 分隔符
        int rangeFieldRight = dialogLeft + DIALOG_W - LEFT_MARGIN;
        int tildeX = rangeFieldRight - RANGE_FIELD_W - 14;
        context.drawTextWithShadow(textRenderer, Text.literal("~"),
                tildeX, labelY + ROW_SPACING * 3, 0xFFAAAAAA);
        context.drawTextWithShadow(textRenderer, Text.literal("~"),
                tildeX, labelY + ROW_SPACING * 4, 0xFFAAAAAA);

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawLabel(DrawContext context, String key, int x, int y) {
        context.drawTextWithShadow(textRenderer,
                Text.translatable(key).formatted(Formatting.WHITE), x, y, 0xFFFFFF);
    }

    // ==================== 操作 ====================

    private void apply() {
        provider.setModeFilter(modeFilter);
        provider.setSortMode(sortMode);
        provider.setDimFilter(trimToNull(dimField.getText()));
        provider.setOwnerFilter(trimToNull(ownerField.getText()));
        provider.setItemFilter(trimToNull(itemField.getText()));

        // 价格范围：解析浮点数并乘以 100 存储（内部以货币最小单位表示）
        provider.setPriceMinFilter(parsePriceInt(priceMinField.getText()));
        provider.setPriceMaxFilter(parsePriceInt(priceMaxField.getText()));

        // 数量范围
        provider.setQtyMinFilter(parseIntOrNull(qtyMinField.getText()));
        provider.setQtyMaxFilter(parseIntOrNull(qtyMaxField.getText()));

        provider.invalidateCache();
        close();
    }

    private void reset() {
        modeFilter = 0;
        sortMode = QShopDbViewProvider.SORT_NONE;
        dimField.setText("");
        ownerField.setText("");
        itemField.setText("");
        priceMinField.setText("");
        priceMaxField.setText("");
        qtyMinField.setText("");
        qtyMaxField.setText("");
        if (modeButton != null) modeButton.setMessage(getModeText());
        if (sortButton != null) sortButton.setMessage(getSortText());
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // ESC
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ==================== 工具方法 ====================

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 解析整数或返回 null。 */
    private static Integer parseIntOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解析价格字符串为内部表示（乘以 100），或返回 null。 */
    private static Integer parsePriceInt(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try {
            return (int) (Double.parseDouble(t) * 100.0);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

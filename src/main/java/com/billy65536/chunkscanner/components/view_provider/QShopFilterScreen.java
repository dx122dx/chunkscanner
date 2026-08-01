package com.billy65536.chunkscanner.components.view_provider;

import com.billy65536.chunkscanner.gui.PlaceholderTextField;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * QShop 商店数据筛选悬浮窗。
 *
 * 筛选条件：
 *   - 模式：全部 / 出售 / 收购
 *   - 排序：无 / 价格↑↓ / 数量↑↓
 *   - 维度、所有者、商品名、物品ID：子串匹配（留空 = 不筛选）
 *   - 条目标志：R/E/S/B 字符匹配（留空 = 不筛选）
 *   - 价格范围：最小/最大（整数，留空 = 不限制）
 *   - 数量范围：最小/最大（整数，留空 = 不限制）
 *
 * 点击"应用"后筛选立即生效，返回上一界面。
 */
public class QShopFilterScreen extends Screen {

    private final Screen parent;
    private final QShopFilter filter;

    // ==================== 布局常量 ====================

    private static final int DIALOG_W = 268;
    private static final int DIALOG_H = 240;
    private static final int FIELD_W = 124;
    private static final int FIELD_H = 16;
    private static final int MODE_BTN_W = 18;
    private static final int RANGE_FIELD_W = 46;
    private static final int ROW_SPACING = 22;
    private static final int LEFT_MARGIN = 8;

    // ==================== 控件 ====================

    private ButtonWidget modeButton;
    private ButtonWidget sortButton;
    private PlaceholderTextField dimField;
    private PlaceholderTextField ownerField;
    private PlaceholderTextField itemField;
    private PlaceholderTextField itemIdField;
    private PlaceholderTextField flagsField;
    private PlaceholderTextField priceMinField;
    private PlaceholderTextField priceMaxField;
    private PlaceholderTextField qtyMinField;
    private PlaceholderTextField qtyMaxField;

    private ButtonWidget dimModeButton;
    private ButtonWidget ownerModeButton;
    private ButtonWidget itemModeButton;
    private ButtonWidget itemIdModeButton;
    private ButtonWidget flagsModeButton;

    // ==================== 状态 ====================

    private int modeFilter; // 0=全部, 1=出售, 2=收购
    private int sortMode;
    private String dimFilter;
    private String ownerFilter;
    private String itemFilter;
    private String itemIdFilter;
    private String flagsFilter;
    private int dimFilterMode;
    private int ownerFilterMode;
    private int itemFilterMode;
    private int itemIdFilterMode;
    private int flagsFilterMode;
    private String priceMinStr;
    private String priceMaxStr;
    private String qtyMinStr;
    private String qtyMaxStr;

    public QShopFilterScreen(Screen parent, QShopFilter filter) {
        super(Text.translatable("chunkscanner.filter.qshop.title"));
        this.parent = parent;
        this.filter = filter;

        // 从 filter 加载当前筛选状态
        this.modeFilter = filter.getModeFilter();
        this.sortMode = filter.getSortMode();
        this.dimFilter = filter.getDimFilter() != null ? filter.getDimFilter() : "";
        this.ownerFilter = filter.getOwnerFilter() != null ? filter.getOwnerFilter() : "";
        this.itemFilter = filter.getItemFilter() != null ? filter.getItemFilter() : "";
        this.itemIdFilter = filter.getItemIdFilter() != null ? filter.getItemIdFilter() : "";
        this.flagsFilter = filter.getFlagsFilter() != null ? filter.getFlagsFilter() : "";
        this.dimFilterMode = filter.getDimFilterMode();
        this.ownerFilterMode = filter.getOwnerFilterMode();
        this.itemFilterMode = filter.getItemFilterMode();
        this.itemIdFilterMode = filter.getItemIdFilterMode();
        this.flagsFilterMode = filter.getFlagsFilterMode();
        this.priceMinStr = priceToDisplayString(filter.getPriceMinFilter());
        this.priceMaxStr = priceToDisplayString(filter.getPriceMaxFilter());
        this.qtyMinStr = filter.getQtyMinFilter() != null
                ? String.valueOf(filter.getQtyMinFilter()) : "";
        this.qtyMaxStr = filter.getQtyMaxFilter() != null
                ? String.valueOf(filter.getQtyMaxFilter()) : "";
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
        int modeBtnX = fieldX - MODE_BTN_W - 2;

        // === 模式切换按钮（位于文本字段左侧） ===
        dimModeButton = ButtonWidget.builder(
                getPatternModeText(dimFilterMode),
                btn -> {
                    dimFilterMode = (dimFilterMode + 1) % 4;
                    btn.setMessage(getPatternModeText(dimFilterMode));
                }).dimensions(modeBtnX, fy, MODE_BTN_W, FIELD_H).build();
        addDrawableChild(dimModeButton);

        ownerModeButton = ButtonWidget.builder(
                getPatternModeText(ownerFilterMode),
                btn -> {
                    ownerFilterMode = (ownerFilterMode + 1) % 4;
                    btn.setMessage(getPatternModeText(ownerFilterMode));
                }).dimensions(modeBtnX, fy + ROW_SPACING, MODE_BTN_W, FIELD_H).build();
        addDrawableChild(ownerModeButton);

        itemModeButton = ButtonWidget.builder(
                getPatternModeText(itemFilterMode),
                btn -> {
                    itemFilterMode = (itemFilterMode + 1) % 4;
                    btn.setMessage(getPatternModeText(itemFilterMode));
                }).dimensions(modeBtnX, fy + ROW_SPACING * 2, MODE_BTN_W, FIELD_H).build();
        addDrawableChild(itemModeButton);

        itemIdModeButton = ButtonWidget.builder(
                getPatternModeText(itemIdFilterMode),
                btn -> {
                    itemIdFilterMode = (itemIdFilterMode + 1) % 4;
                    btn.setMessage(getPatternModeText(itemIdFilterMode));
                }).dimensions(modeBtnX, fy + ROW_SPACING * 3, MODE_BTN_W, FIELD_H).build();
        addDrawableChild(itemIdModeButton);

        flagsModeButton = ButtonWidget.builder(
                getFlagsModeText(flagsFilterMode),
                btn -> {
                    flagsFilterMode = (flagsFilterMode + 1) % 3;
                    btn.setMessage(getFlagsModeText(flagsFilterMode));
                }).dimensions(modeBtnX, fy + ROW_SPACING * 4, MODE_BTN_W, FIELD_H).build();
        addDrawableChild(flagsModeButton);

        // 文本输入字段
        dimField = createTextField(fieldX, fy, dimFilter,
                Text.translatable("chunkscanner.filter.placeholder.dimension").getString());
        ownerField = createTextField(fieldX, fy + ROW_SPACING, ownerFilter,
                Text.translatable("chunkscanner.filter.placeholder.owner").getString());
        itemField = createTextField(fieldX, fy + ROW_SPACING * 2, itemFilter,
                Text.translatable("chunkscanner.filter.placeholder.item").getString());
        itemIdField = createTextField(fieldX, fy + ROW_SPACING * 3, itemIdFilter,
                Text.translatable("chunkscanner.filter.placeholder.item_id").getString());
        flagsField = createTextField(fieldX, fy + ROW_SPACING * 4, flagsFilter,
                Text.translatable("chunkscanner.filter.placeholder.flags").getString());
        flagsField.setMaxLength(8);
        flagsField.setTextPredicate(t -> t.matches("[RESBresb]*"));

        addDrawableChild(dimField);
        addDrawableChild(ownerField);
        addDrawableChild(itemField);
        addDrawableChild(itemIdField);
        addDrawableChild(flagsField);

        // === 价格范围（两个小字段，用 "~" 分隔） ===
        int rangeY = fy + ROW_SPACING * 5;
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

    /** 创建带 placeholder 的通用文本输入框。 */
    private PlaceholderTextField createTextField(int x, int y, String initial, String hint) {
        PlaceholderTextField f = new PlaceholderTextField(textRenderer, x, y, FIELD_W, FIELD_H, hint);
        f.setMaxLength(64);
        if (initial != null && !initial.isEmpty()) {
            f.setText(initial);
        }
        return f;
    }

    /** 创建数字输入框（支持小数，用于价格）。 */
    private PlaceholderTextField createNumberField(int x, int y, int w, String initial) {
        PlaceholderTextField f = new PlaceholderTextField(textRenderer, x, y, w, FIELD_H, null);
        f.setMaxLength(12);
        f.setTextPredicate(t -> t.matches("[0-9.]*"));
        if (initial != null && !initial.isEmpty()) {
            f.setText(initial);
        }
        return f;
    }

    /** 创建纯数字输入框（仅整数，用于数量）。 */
    private PlaceholderTextField createDigitField(int x, int y, int w, String initial) {
        PlaceholderTextField f = new PlaceholderTextField(textRenderer, x, y, w, FIELD_H, null);
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
            case QShopFilter.SORT_PRICE_ASC ->
                    Text.translatable("chunkscanner.filter.sort.price_asc").formatted(Formatting.GREEN);
            case QShopFilter.SORT_PRICE_DESC ->
                    Text.translatable("chunkscanner.filter.sort.price_desc").formatted(Formatting.GREEN);
            case QShopFilter.SORT_QTY_ASC ->
                    Text.translatable("chunkscanner.filter.sort.qty_asc").formatted(Formatting.GREEN);
            case QShopFilter.SORT_QTY_DESC ->
                    Text.translatable("chunkscanner.filter.sort.qty_desc").formatted(Formatting.GREEN);
            default -> Text.translatable("chunkscanner.filter.sort.none").formatted(Formatting.GRAY);
        };
    }

    private Text getPatternModeText(int mode) {
        return switch (mode) {
            case QShopFilter.PATTERN_CONTAINS ->
                    Text.literal("含").formatted(Formatting.WHITE);
            case QShopFilter.PATTERN_EXCLUDE ->
                    Text.literal("除").formatted(Formatting.RED);
            case QShopFilter.PATTERN_EXACT ->
                    Text.literal("全").formatted(Formatting.YELLOW);
            case QShopFilter.PATTERN_REGEX ->
                    Text.literal("正").formatted(Formatting.AQUA);
            default -> Text.literal("?").formatted(Formatting.GRAY);
        };
    }

    /** flags 筛选模式（3 种：含/除/全，无正则）。 */
    private Text getFlagsModeText(int mode) {
        return switch (mode) {
            case QShopFilter.PATTERN_CONTAINS ->
                    Text.literal("含").formatted(Formatting.WHITE);
            case QShopFilter.PATTERN_EXCLUDE ->
                    Text.literal("除").formatted(Formatting.RED);
            case QShopFilter.PATTERN_EXACT ->
                    Text.literal("全").formatted(Formatting.YELLOW);
            default -> Text.literal("?").formatted(Formatting.GRAY);
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
        drawLabel(context, "chunkscanner.filter.field.item_id", labelX, labelY + ROW_SPACING * 3);
        drawLabel(context, "chunkscanner.filter.field.flags", labelX, labelY + ROW_SPACING * 4);
        drawLabel(context, "chunkscanner.filter.field.price_range", labelX, labelY + ROW_SPACING * 5);
        drawLabel(context, "chunkscanner.filter.field.qty_range", labelX, labelY + ROW_SPACING * 6);

        // 范围的 "~" 分隔符
        int rangeFieldRight = dialogLeft + DIALOG_W - LEFT_MARGIN;
        int tildeX = rangeFieldRight - RANGE_FIELD_W - 14;
        context.drawTextWithShadow(textRenderer, Text.literal("~"),
                tildeX, labelY + ROW_SPACING * 5, 0xFFAAAAAA);
        context.drawTextWithShadow(textRenderer, Text.literal("~"),
                tildeX, labelY + ROW_SPACING * 6, 0xFFAAAAAA);

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawLabel(DrawContext context, String key, int x, int y) {
        context.drawTextWithShadow(textRenderer,
                Text.translatable(key).formatted(Formatting.WHITE), x, y, 0xFFFFFF);
    }

    // ==================== 操作 ====================

    private void apply() {
        filter.setModeFilter(modeFilter);
        filter.setSortMode(sortMode);
        filter.setDimFilter(trimToNull(dimField.getText()));
        filter.setDimFilterMode(dimFilterMode);
        filter.setOwnerFilter(trimToNull(ownerField.getText()));
        filter.setOwnerFilterMode(ownerFilterMode);
        filter.setItemFilter(trimToNull(itemField.getText()));
        filter.setItemFilterMode(itemFilterMode);
        filter.setItemIdFilter(trimToNull(itemIdField.getText()));
        filter.setItemIdFilterMode(itemIdFilterMode);
        filter.setFlagsFilter(trimToNull(flagsField.getText()));
        filter.setFlagsFilterMode(flagsFilterMode);

        // 价格范围：解析浮点数并乘以 100 存储（内部以货币最小单位表示）
        filter.setPriceMinFilter(parsePriceInt(priceMinField.getText()));
        filter.setPriceMaxFilter(parsePriceInt(priceMaxField.getText()));

        // 数量范围
        filter.setQtyMinFilter(parseIntOrNull(qtyMinField.getText()));
        filter.setQtyMaxFilter(parseIntOrNull(qtyMaxField.getText()));

        filter.invalidateCache();
        close();
    }

    private void reset() {
        modeFilter = 0;
        sortMode = QShopFilter.SORT_NONE;
        dimField.setText("");
        dimFilterMode = QShopFilter.PATTERN_CONTAINS;
        ownerField.setText("");
        ownerFilterMode = QShopFilter.PATTERN_CONTAINS;
        itemField.setText("");
        itemFilterMode = QShopFilter.PATTERN_CONTAINS;
        itemIdField.setText("");
        itemIdFilterMode = QShopFilter.PATTERN_CONTAINS;
        flagsField.setText("");
        flagsFilterMode = QShopFilter.PATTERN_CONTAINS;
        priceMinField.setText("");
        priceMaxField.setText("");
        qtyMinField.setText("");
        qtyMaxField.setText("");
        if (modeButton != null) modeButton.setMessage(getModeText());
        if (sortButton != null) sortButton.setMessage(getSortText());
        if (dimModeButton != null) dimModeButton.setMessage(getPatternModeText(dimFilterMode));
        if (ownerModeButton != null) ownerModeButton.setMessage(getPatternModeText(ownerFilterMode));
        if (itemModeButton != null) itemModeButton.setMessage(getPatternModeText(itemFilterMode));
        if (itemIdModeButton != null) itemIdModeButton.setMessage(getPatternModeText(itemIdFilterMode));
        if (flagsModeButton != null) flagsModeButton.setMessage(getFlagsModeText(flagsFilterMode));
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

    /** 将内部价格表示（乘以 100 的整数）转换为用户可读的显示字符串。 */
    private static String priceToDisplayString(Integer priceInt) {
        if (priceInt == null) return "";
        int cents = priceInt;
        if (cents % 100 == 0) return String.valueOf(cents / 100);
        return String.format("%d.%02d", cents / 100, cents % 100);
    }

    /** 解析价格字符串为内部表示（乘以 100），或返回 null。 */
    private static Integer parsePriceInt(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try {
            return (int) Math.round(Double.parseDouble(t) * 100.0);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

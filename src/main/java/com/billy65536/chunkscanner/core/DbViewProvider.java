package com.billy65536.chunkscanner.core;

import com.billy65536.chunkscanner.gui.ViewLayout;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.Screen;

/**
 * 数据库展示提供者接口。
 *
 * <p>将数据库文件的元信息读取与展示解耦，允许不同的数据库格式通过实现此接口来提供浏览能力。
 * DatabaseScreen 通过此接口访问数据库，不直接依赖具体实现。</p>
 *
 * <p>视图类型的注册通过 {@link DbViewProviderRegistry.Type} 和 {@link DbViewProviderRegistry} 完成。</p>
 *
 * <p>数据访问（文件路径、条目、元数据等）通过 {@link #getDb()} 直接访问底层 {@link ChunkDb} 实例，
 * 不再需要将 ChunkDb 操作转发到 {@link DbViewProvider} 接口。</p>
 *
 * <p>渲染协议通过 {@link #getLayout(TextRenderer)} 返回的 {@link ViewLayout} 统一处理，
 * DatabaseScreen 不再需要关心视图是否为特化、如何渲染等细节。</p>
 */
public interface DbViewProvider {

    /** 获取底层数据库实例，用于直接执行数据访问操作。 */
    ChunkDb getDb();

    /**
     * 获取此视图的渲染布局。
     *
     * <p>原始视图返回列表模式 {@link com.billy65536.chunkscanner.gui.TableLayout}，
     * Sign/QShop 等特化视图返回表格模式。DatabaseScreen 只需调用此方法即可获得完整的
     * 渲染协议，无需再通过 instanceof 或 isSpecialized() 分支处理。</p>
     *
     * @param textRenderer 字体渲染器，由 {@code DatabaseScreen} 提供
     * @return 用于渲染的布局对象，不可为 null
     */
    ViewLayout getLayout(TextRenderer textRenderer);

    // ==================== 筛选 ====================

    /**
     * 是否支持筛选功能。
     * 返回 true 时，DatabaseScreen 会在视图选择器右侧显示"..."按钮。
     */
    default boolean supportsFilter() { return false; }

    /**
     * "..."按钮的颜色。默认灰色，筛选激活时返回高亮色。
     */
    default int getFilterButtonColor() { return 0xFF888888; }

    /**
     * 当前是否有筛选条件生效。
     */
    default boolean isFilterActive() { return false; }

    /**
     * 创建筛选界面悬浮窗。返回 null 表示无筛选 UI。
     */
    default Screen createFilterScreen(Screen parent) { return null; }
}

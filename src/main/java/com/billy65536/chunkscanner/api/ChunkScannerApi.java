package com.billy65536.chunkscanner.api;

import net.minecraft.util.Identifier;

import com.billy65536.chunkscanner.ChunkScannerMod;

/**
 * ChunkScanner 公共 API 总入口。
 *
 * <p>本类是外部模组接入 ChunkScanner 的推荐起点，按领域分发到三个子 API：</p>
 * <ul>
 *   <li>{@link DatabaseApi} —— 数据库查询、加载、导出与 GUI</li>
 *   <li>{@link NavigationApi} —— 导航入队、独立导航实例与到达条件注册</li>
 *   <li>{@link RegistryApi} —— 分析器、数据库视图、存储引擎的注册</li>
 * </ul>
 *
 * <h3>稳定性约定</h3>
 * <p>{@code com.billy65536.chunkscanner.api} 包内的所有公开签名视为稳定契约，
 * 在同一主版本内保持向后兼容：不删除方法、不改变已有参数语义，
 * 废弃的方法会先标注 {@link Deprecated} 并保留至少一个次版本。</p>
 *
 * <p>包外的类（{@code core}、{@code screen}、{@code components} 等）<b>不属于</b>
 * 公共契约，可能随时调整。外部模组应尽量只依赖本包，
 * 仅在 API 返回类型不可避免时（如 {@code IChunkDb}、{@code FileMeta}）才引用 core 类型。</p>
 *
 * <h3>调用时机</h3>
 * <ul>
 *   <li><b>注册类</b>（{@link RegistryApi}）必须在客户端初始化阶段完成。</li>
 *   <li><b>数据库与导航</b>可在运行期调用，但需注意各自的线程约束。</li>
 * </ul>
 *
 * <h3>接入示例</h3>
 * <pre>{@code
 * public class MyMod implements ClientModInitializer {
 *     @Override
 *     public void onInitializeClient() {
 *         // 1. 注册扩展点
 *         RegistryApi.registerAnalyzer(new MyAnalyzer());
 *
 *         // 2. 运行期使用数据库
 *         var databases = DatabaseApi.listDatabases();
 *
 *         // 3. 创建独立导航
 *         var nav = NavigationApi.createNavigation("mymod");
 *     }
 * }
 * }</pre>
 */
public final class ChunkScannerApi {

    /** 本模组 id（{@code chunkscanner}）。 */
    public static final String MOD_ID = ChunkScannerMod.MOD_ID;

    /**
     * 公共 API 版本号。
     *
     * <p>与模组版本独立：仅在公共 API 发生变更时递增。
     * 递增 1 表示新增了向后兼容的能力；外部模组可据此做能力探测。</p>
     */
    public static final int API_VERSION = 1;

    private ChunkScannerApi() {}

    // ==================== 通用工具 ====================

    /**
     * 本模组是否已完成初始化。
     *
     * <p>为 {@code false} 时调用其他 API 可能得到空结果（如数据库目录尚未就绪）。
     * 外部模组在 {@code onInitializeClient} 中注册扩展点无需检查此项，
     * Fabric 的入口点顺序已保证注册表可用。</p>
     */
    public static boolean isReady() {
        return ChunkScannerMod.getScanner() != null;
    }

    /**
     * 构造 {@code chunkscanner} 命名空间下的标识符。
     *
     * <p>用于引用本模组内置的分析器、视图、条件等 id。
     * 外部模组注册自己的扩展点时应使用<b>自己的</b>命名空间，
     * 即 {@code new Identifier("yourmod", "path")}。</p>
     *
     * @param path 路径部分，只能包含 {@code [a-z0-9/._-]}
     */
    public static Identifier id(String path) {
        return ChunkScannerMod.id(path);
    }

    /**
     * "未知/未定义" 标识符哨兵（{@code undefined:undefined}）。
     *
     * <p>解析旧版数据库文件时，缺失或非法的 analyzerId 会归一为此值。
     * 判断"分析器未知"应与本常量比较，而非检查空字符串。</p>
     */
    public static Identifier unknownId() {
        return ChunkScannerMod.ID_UNKNOWN;
    }
}

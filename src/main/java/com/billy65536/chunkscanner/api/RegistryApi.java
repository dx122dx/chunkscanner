package com.billy65536.chunkscanner.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import net.minecraft.util.Identifier;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.AnalyzerRegistry;
import com.billy65536.chunkscanner.core.DbViewProviderRegistry;
import com.billy65536.chunkscanner.core.IChunkAnalyzer;
import com.billy65536.chunkscanner.core.IChunkDb;
import com.billy65536.chunkscanner.core.IDbAdaptor;

/**
 * 注册公共 API。
 *
 * <p>统一收敛四类扩展点的注册与查询：</p>
 * <ul>
 *   <li>{@link IChunkAnalyzer 分析器} —— 决定扫描时从区块中提取什么数据</li>
 *   <li>{@link IDbAdaptor 数据库适配器} —— 决定数据以什么格式落库、以什么强类型读出</li>
 *   <li>{@link DbViewProviderRegistry.ITypeDescriptor 数据库视图} —— 决定数据库 GUI 如何展示数据</li>
 *   <li>{@link IChunkDb.IFactory 数据库工厂} —— 决定底层存储引擎</li>
 * </ul>
 *
 * <p><b>三者如何串起来</b>：分析器通过
 * {@link IChunkAnalyzer#getAdaptorId()} 声明自己写入数据所用的适配器，该 id 会写入
 * 数据库包的 metadata；视图则通过
 * {@link DbViewProviderRegistry.ITypeDescriptor#applicableAdaptors()} 声明自己能读懂
 * 哪些适配器。GUI 只展示声明了当前包适配器的视图。三者独立注册，互不耦合。</p>
 *
 * <p><b>调用时机</b>：所有注册必须在客户端初始化阶段完成（Fabric 的
 * {@code onInitializeClient}）。运行期注册虽不会报错，但已打开的 GUI 与
 * 已启动的扫描任务不会感知到新条目。</p>
 *
 * <p><b>id 约定</b>：所有 id 使用 {@link Identifier}，外部模组应使用自己的命名空间，
 * 例如 {@code new Identifier("qab", "shop_view")}。避免使用 {@code chunkscanner}
 * 命名空间，以免与本模组内置条目冲突。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 注册适配器（分析器通过 getAdaptorId() 指向它）
 * RegistryApi.registerAdaptor(new MyAdaptor.Factory());
 *
 * // 注册分析器
 * RegistryApi.registerAnalyzer(new MyAnalyzer());
 *
 * // 注册视图类型（其 applicableAdaptors() 需含 MyAdaptor 的 id）
 * RegistryApi.registerViewProvider(new MyViewType());
 *
 * // 注册自定义存储引擎
 * RegistryApi.registerDbFactory(new MyDbFactory());
 * }</pre>
 *
 * @see NavigationApi
 * @see DatabaseApi
 */
public final class RegistryApi {

    private RegistryApi() {}

    // ==================== 分析器 ====================

    /**
     * 注册一个分析器。
     *
     * <p>分析器所用的适配器由其 {@link IChunkAnalyzer#getAdaptorId()} 自行声明，
     * 默认为 {@code chunkscanner:raw}。适配器需另行
     * {@linkplain #registerAdaptor 注册}。</p>
     *
     * <p>重复注册同 id 会覆盖旧值。</p>
     *
     * @param analyzer 分析器实例，{@code null} 或 id 为 {@code null} 时忽略
     */
    public static void registerAnalyzer(IChunkAnalyzer analyzer) {
        AnalyzerRegistry.register(analyzer);
    }

    /** 通过 id 获取分析器，未注册返回 {@code null}。 */
    public static IChunkAnalyzer getAnalyzer(Identifier id) {
        return AnalyzerRegistry.get(id);
    }

    /** 指定分析器是否已注册。 */
    public static boolean hasAnalyzer(Identifier id) {
        return AnalyzerRegistry.get(id) != null;
    }

    /** 所有已注册的分析器（只读，保持注册顺序）。 */
    public static Collection<IChunkAnalyzer> analyzers() {
        return AnalyzerRegistry.getAll();
    }

    /** 所有已注册分析器的 id（只读，保持注册顺序）。 */
    public static List<Identifier> analyzerIds() {
        List<Identifier> ids = new ArrayList<>();
        for (IChunkAnalyzer a : AnalyzerRegistry.getAll()) {
            ids.add(a.getId());
        }
        return Collections.unmodifiableList(ids);
    }

    /**
     * 获取分析器声明的适配器 id。
     *
     * @return 适配器 id；分析器未注册时返回 {@code chunkscanner:raw}
     */
    public static Identifier getAdaptorId(Identifier analyzerId) {
        return AnalyzerRegistry.getAdaptorId(analyzerId);
    }

    // ==================== 数据库适配器 ====================

    /**
     * 注册一个数据库适配器工厂。
     *
     * <p>适配器是分析器写入、消费端读取数据的唯一正规渠道：它把包内的裸键值负载
     * 包装成业务语义明确的强类型方法。数据库包会在 metadata 中记录自己的适配器 id，
     * 消费端通过 {@code pkg.getAdaptor(MyAdaptor.class)} 取用。</p>
     *
     * <p>适配器 id 未注册时，包会回退到内置的 {@code chunkscanner:raw} 适配器。
     * 重复注册同 id 会覆盖旧值。</p>
     *
     * @param factory 适配器工厂，{@code null} 或 id 为 {@code null} 时忽略
     * @return {@code true} 表示注册成功
     */
    public static boolean registerAdaptor(IDbAdaptor.IFactory factory) {
        if (factory == null || factory.getId() == null) {
            ChunkScannerMod.LOGGER.warn("Attempted to register null db adaptor factory, ignored");
            return false;
        }
        IDbAdaptor.FactoryRegistry.register(factory);
        return true;
    }

    /** 通过 id 获取适配器工厂，未注册返回 {@code null}。 */
    public static IDbAdaptor.IFactory getAdaptorFactory(Identifier id) {
        return IDbAdaptor.FactoryRegistry.get(id);
    }

    /** 指定适配器是否已注册。 */
    public static boolean hasAdaptor(Identifier id) {
        return IDbAdaptor.FactoryRegistry.get(id) != null;
    }

    /** 所有已注册的适配器工厂（只读，保持注册顺序）。 */
    public static Collection<IDbAdaptor.IFactory> adaptorFactories() {
        return IDbAdaptor.FactoryRegistry.getAll();
    }

    // ==================== 数据库视图 ====================

    /**
     * 注册一个数据库视图类型。
     *
     * <p>视图类型决定数据库 GUI 中"视图选择器"里的可选项。
     * 通过 {@link DbViewProviderRegistry.ITypeDescriptor#applicableAdaptors()}
     * 声明自己能读懂哪些适配器，GUI 只展示声明了当前包适配器的视图。</p>
     *
     * <p>重复注册同 id 会覆盖旧值。</p>
     *
     * @param type 视图类型描述符，{@code null} 时忽略
     * @return {@code true} 表示注册成功
     */
    public static boolean registerViewProvider(DbViewProviderRegistry.ITypeDescriptor type) {
        if (type == null || type.getId() == null) {
            ChunkScannerMod.LOGGER.warn("Attempted to register null view provider type, ignored");
            return false;
        }
        DbViewProviderRegistry.register(type);
        return true;
    }

    /** 通过 id 获取视图类型，未注册返回 {@code null}。 */
    public static DbViewProviderRegistry.ITypeDescriptor getViewProvider(Identifier id) {
        return DbViewProviderRegistry.get(id);
    }

    /** 指定视图类型是否已注册。 */
    public static boolean hasViewProvider(Identifier id) {
        return DbViewProviderRegistry.get(id) != null;
    }

    /** 所有已注册的视图类型（只读，保持注册顺序）。 */
    public static Collection<DbViewProviderRegistry.ITypeDescriptor> viewProviders() {
        return DbViewProviderRegistry.getAll();
    }

    /**
     * 列出能读懂指定适配器的所有视图类型。
     *
     * <p>只返回在 {@link DbViewProviderRegistry.ITypeDescriptor#applicableAdaptors()}
     * 中显式声明了该适配器的视图；一个都没有时回退到内置的 {@code chunkscanner:raw} 视图。</p>
     *
     * @param adaptorId 适配器 id
     * @return 适用的视图类型列表（只读）
     */
    public static List<DbViewProviderRegistry.ITypeDescriptor> viewProvidersFor(Identifier adaptorId) {
        return DbViewProviderRegistry.forAdaptor(adaptorId);
    }

    // ==================== 数据库工厂 ====================

    /**
     * 注册一个数据库工厂（底层存储引擎）。
     *
     * <p>第一个注册的工厂会成为{@linkplain #defaultDbFactory() 默认工厂}，
     * 新建扫描任务默认使用它。本模组内置的二进制存储引擎在初始化早期注册，
     * 因此外部模组注册的工厂不会自动成为默认。</p>
     *
     * <p>重复注册同 id 会覆盖旧值。</p>
     *
     * @param factory 工厂实例，{@code null} 或 id 为 {@code null} 时忽略
     * @return {@code true} 表示注册成功
     */
    public static boolean registerDbFactory(IChunkDb.IFactory factory) {
        if (factory == null || factory.getId() == null) {
            ChunkScannerMod.LOGGER.warn("Attempted to register null db factory, ignored");
            return false;
        }
        IChunkDb.FactoryRegistry.register(factory);
        ChunkScannerMod.LOGGER.info("Registered db factory: {}", factory.getId());
        return true;
    }

    /** 通过 id 获取数据库工厂，未注册返回 {@code null}。 */
    public static IChunkDb.IFactory getDbFactory(Identifier id) {
        return IChunkDb.FactoryRegistry.get(id);
    }

    /** 指定数据库工厂是否已注册。 */
    public static boolean hasDbFactory(Identifier id) {
        return IChunkDb.FactoryRegistry.get(id) != null;
    }

    /**
     * 获取默认数据库工厂（注册表中的第一个）。
     *
     * <p>数据库实例不在此创建：文件与元信息由
     * {@link com.billy65536.chunkscanner.core.db.DbPackage} 统一管理，
     * 请改用 {@link DatabaseApi#createDatabase} / {@link DatabaseApi#openPackage(String)}。</p>
     *
     * @return 默认工厂；注册表为空时返回 {@code null}
     */
    public static IChunkDb.IFactory defaultDbFactory() {
        return IChunkDb.FactoryRegistry.getDefault();
    }
}

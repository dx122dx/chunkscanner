package com.billy65536.chunkscanner.api;

import java.nio.file.Path;
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

/**
 * 注册公共 API。
 *
 * <p>统一收敛三类扩展点的注册与查询：</p>
 * <ul>
 *   <li>{@link IChunkAnalyzer 分析器} —— 决定扫描时从区块中提取什么数据</li>
 *   <li>{@link DbViewProviderRegistry.ITypeDescriptor 数据库视图} —— 决定数据库 GUI 如何展示数据</li>
 *   <li>{@link IChunkDb.IFactory 数据库工厂} —— 决定底层存储引擎</li>
 * </ul>
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
 * // 注册分析器，并指定其数据库默认使用的视图
 * RegistryApi.registerAnalyzer(new MyAnalyzer(), new Identifier("qab", "shop_view"));
 *
 * // 注册视图类型
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
     * 注册一个分析器，默认视图为 {@code chunkscanner:raw}（原始 KV 十六进制视图）。
     *
     * <p>重复注册同 id 会覆盖旧的分析器及其默认视图关联。</p>
     *
     * @param analyzer 分析器实例，{@code null} 或 id 为 {@code null} 时忽略
     */
    public static void registerAnalyzer(IChunkAnalyzer analyzer) {
        AnalyzerRegistry.register(analyzer);
    }

    /**
     * 注册一个分析器并指定其数据库默认使用的视图。
     *
     * <p>视图 id 无需在此之前注册，但在数据库 GUI 打开前必须已注册，
     * 否则会回退到 {@code chunkscanner:raw}。</p>
     *
     * @param analyzer            分析器实例，{@code null} 或 id 为 {@code null} 时忽略
     * @param defaultViewProvider 默认视图 id，{@code null} 视为 {@code chunkscanner:raw}
     */
    public static void registerAnalyzer(IChunkAnalyzer analyzer, Identifier defaultViewProvider) {
        AnalyzerRegistry.register(analyzer, defaultViewProvider);
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
     * 获取分析器关联的默认视图 id。
     *
     * @return 关联的视图 id；未注册时返回 {@code chunkscanner:raw}
     */
    public static Identifier getDefaultViewProvider(Identifier analyzerId) {
        return AnalyzerRegistry.getDefaultViewProvider(analyzerId);
    }

    /** 默认视图 id（{@code chunkscanner:raw}）。 */
    public static Identifier defaultViewProviderId() {
        return AnalyzerRegistry.DEFAULT_VIEW;
    }

    // ==================== 数据库视图 ====================

    /**
     * 注册一个数据库视图类型。
     *
     * <p>视图类型决定数据库 GUI 中"视图选择器"里的可选项。
     * 通过 {@link DbViewProviderRegistry.ITypeDescriptor#applicableAnalyzers()}
     * 限定适用的分析器，返回空集表示适用于所有数据库。</p>
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
     * 列出适用于指定分析器的所有视图类型。
     *
     * <p>包含显式声明适用该分析器的视图，以及适用范围为空集（通用）的视图。</p>
     *
     * @param analyzerId 分析器 id
     * @return 适用的视图类型列表（只读）
     */
    public static List<DbViewProviderRegistry.ITypeDescriptor> viewProvidersFor(Identifier analyzerId) {
        List<DbViewProviderRegistry.ITypeDescriptor> result = new ArrayList<>();
        for (DbViewProviderRegistry.ITypeDescriptor type : DbViewProviderRegistry.getAll()) {
            java.util.Set<Identifier> applicable = type.applicableAnalyzers();
            if (applicable == null || applicable.isEmpty() || applicable.contains(analyzerId)) {
                result.add(type);
            }
        }
        return Collections.unmodifiableList(result);
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
     * @return 默认工厂；注册表为空时返回 {@code null}
     */
    public static IChunkDb.IFactory defaultDbFactory() {
        return IChunkDb.FactoryRegistry.getDefault();
    }

    /**
     * 使用默认工厂创建数据库实例（完整模式，构造时立即加载数据）。
     *
     * <p>推荐所有外部调用方通过本方法创建数据库，而非直接 new 具体实现，
     * 以便存储引擎替换时无需改动调用代码。</p>
     *
     * @param scanId     扫描任务 id
     * @param analyzerId 分析器 id
     * @param dbDir      数据库文件目录
     * @return 数据库实例；无可用工厂时返回 {@code null}
     */
    public static IChunkDb createDb(String scanId, Identifier analyzerId, Path dbDir) {
        IChunkDb.IFactory factory = IChunkDb.FactoryRegistry.getDefault();
        if (factory == null) {
            ChunkScannerMod.LOGGER.warn("No db factory registered, cannot create db for scanId={}", scanId);
            return null;
        }
        return factory.create(scanId, analyzerId, dbDir);
    }

    /**
     * 使用指定工厂创建数据库实例（完整模式）。
     *
     * @param factoryId 工厂 id
     * @return 数据库实例；工厂未注册时返回 {@code null}
     */
    public static IChunkDb createDb(Identifier factoryId, String scanId, Identifier analyzerId, Path dbDir) {
        IChunkDb.IFactory factory = IChunkDb.FactoryRegistry.get(factoryId);
        if (factory == null) {
            ChunkScannerMod.LOGGER.warn("Db factory '{}' not registered, cannot create db", factoryId);
            return null;
        }
        return factory.create(scanId, analyzerId, dbDir);
    }

    /**
     * 使用默认工厂创建数据库实例（元数据模式，延迟加载）。
     *
     * <p>返回的实例未加载 KV 数据，需调用 {@link IChunkDb#open()} 后才能读取内容。
     * 适合只需读取文件大小、修改时间等元信息的场景。</p>
     *
     * @return 数据库实例；无可用工厂时返回 {@code null}
     */
    public static IChunkDb createDbMetadataOnly(String scanId, Identifier analyzerId, Path dbDir) {
        IChunkDb.IFactory factory = IChunkDb.FactoryRegistry.getDefault();
        if (factory == null) {
            ChunkScannerMod.LOGGER.warn("No db factory registered, cannot create db for scanId={}", scanId);
            return null;
        }
        return factory.createMetadataOnly(scanId, analyzerId, dbDir);
    }
}

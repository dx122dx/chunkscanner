package com.billy65536.chunkscanner.core;

import java.util.Map;
import java.util.Set;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.db.DbPackage;

import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.Collections;

/** 全局视图类型注册表。 */
public final class DbViewProviderRegistry {
    private static final Map<Identifier, ITypeDescriptor> types = new LinkedHashMap<>();

    /** 注册一个视图类型。 */
    public static void register(ITypeDescriptor type) {
        types.put(type.getId(), type);
        ChunkScannerMod.LOGGER.info("Registered DbViewProvider: {}", type.getId());
    }

    /** 获取所有已注册的类型（只读）。 */
    public static java.util.Collection<ITypeDescriptor> getAll() {
        return Collections.unmodifiableCollection(types.values());
    }

    /** 通过 id 获取类型。 */
    public static ITypeDescriptor get(Identifier id) {
        return types.get(id);
    }

    /**
     * 列出能渲染指定适配器的视图类型（保持注册顺序）。
     *
     * <p>只返回在 {@link ITypeDescriptor#applicableAdaptors()} 中显式声明了
     * {@code adaptorId} 的视图；一个都没有时回退到内置的 {@code chunkscanner:raw} 视图。</p>
     *
     * @return 只读列表；连 raw 视图都未注册时返回空列表
     */
    public static java.util.List<ITypeDescriptor> forAdaptor(Identifier adaptorId) {
        java.util.List<ITypeDescriptor> out = new java.util.ArrayList<>();
        for (ITypeDescriptor type : types.values()) {
            Set<Identifier> applicable = type.applicableAdaptors();
            if (applicable != null && applicable.contains(adaptorId)) {
                out.add(type);
            }
        }
        if (out.isEmpty()) {
            ITypeDescriptor raw = types.get(ChunkScannerMod.id("raw"));
            if (raw != null) out.add(raw);
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 数据库视图类型描述符。
     *
     * <p>
     * 注册到 {@link DbViewProviderRegistry} 中，用于在 DB 界面选择不同的视图提供者。
     * 每个类型具有 id、name、description（可本地化），以及适用的分析器 id 集合。
     * 实际的数据访问实例由类型描述符根据底层数据库包创建。
     * </p>
     */
    public interface ITypeDescriptor {
        /** 唯一标识符，不可变。 */
        Identifier getId();

        /** 显示名称（本地化）。 */
        Text getName();

        /** 描述文本（本地化），用于悬停提示。 */
        Text getDescription();

        /**
         * 本视图能渲染的适配器 ID 集合。GUI 只展示声明了当前包
         * {@link com.billy65536.chunkscanner.core.db.DbPackage#getAdaptorId() adaptorId} 的视图；
         * 若包的 adaptorId 未注册任何视图，则回退到 {@code chunkscanner:raw} 视图。
         */
        Set<Identifier> applicableAdaptors();

        /**
         * 根据底层数据库包创建此类型的 DbViewProvider 实例。
         *
         * <p>实现方不得关闭 {@code pkg}，其生命周期由调用方持有。</p>
         */
        IDbViewProvider create(DbPackage pkg);
    }

}
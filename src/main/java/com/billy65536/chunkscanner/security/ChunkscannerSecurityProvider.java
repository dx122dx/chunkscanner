package com.billy65536.chunkscanner.security;

import com.billy65536.infrastructure.security.api.SecurityPolicyProvider;

/**
 * chunkscanner 向 infrastructure 安全框架注入安全策略的入口。
 *
 * <p>经 {@code fabric.mod.json} 的 {@code infrastructure:security} entrypoint 被
 * infrastructure 发现并调用。</p>
 *
 * <p>本类只登记「包」，不触碰任何具体实现：注册入口以方法引用形式传入，
 * 由 infrastructure 在确认目标模组已加载后才实际调用，从而保持惰性加载。</p>
 *
 * <p>这里声明的目标模组就是 chunkscanner 自身。本 provider 随 chunkscanner 一同分发，
 * 该判定必然成立，属于沿用框架统一形态；真正依赖此机制做隔离的是那些
 * 「为第三方模组贡献策略」的 provider。</p>
 */
public final class ChunkscannerSecurityProvider implements SecurityPolicyProvider {

    /** 供 Fabric entrypoint 反射实例化。 */
    public ChunkscannerSecurityProvider() {}

    /**
     * {@inheritDoc}
     *
     * <p>登记单个策略包，其注册入口为
     * {@link ChunkscannerSecurityContribution#register()}——刻意用独立入口类的方法引用
     * 而非 lambda，以免 chunkscanner 的类型进入本类常量池而破坏惰性加载。</p>
     */
    @Override
    public void contribute(Contributor contributor) {
        contributor.add("chunkscanner", "Chunk Scanner Security",
                ChunkscannerSecurityContribution::register);
    }
}

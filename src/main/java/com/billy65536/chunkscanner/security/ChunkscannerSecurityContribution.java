package com.billy65536.chunkscanner.security;

import java.util.Map;

import com.billy65536.infrastructure.security.SecurityPolicies;

/**
 * chunkscanner 安全策略的实际注册落点。
 *
 * <p>作为独立入口类存在，只有在 infrastructure 确认 chunkscanner 已加载后才被类加载，
 * 保证惰性加载语义。</p>
 */
public final class ChunkscannerSecurityContribution {

    /** 本模块 id，同时用作默认锁的命名空间。 */
    private static final String MODULE_ID = "chunkscanner";

    /**
     * 默认受保护配置项（进入多人服务器时默认锁定的强制值）。
     *
     * <p>key 为纯字段点分路径，段名 {@code config} 与本模块配置描述符的段名一致，
     * 展开后的完整路径形如
     * {@code chunkscanner:config/components.qshop.highlightEnabled}。</p>
     */
    private static final Map<String, String> DEFAULT_LOCKS = Map.of(
            "components.qshop.highlightEnabled", "false"
    );

    private ChunkscannerSecurityContribution() {}

    /**
     * 向内置 {@code security:server-optin} 策略贡献 chunkscanner 的默认锁。
     *
     * <p>由 {@link ChunkscannerSecurityProvider} 以方法引用的形式登记。</p>
     */
    public static void register() {
        SecurityPolicies.contributeDefaultLocks(MODULE_ID, "config", DEFAULT_LOCKS);
    }
}

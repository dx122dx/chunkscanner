package com.billy65536.chunkscanner.security;

import com.billy65536.infrastructure.security.SecurityPortal;
import com.billy65536.infrastructure.security.builtin.ConfigLocker;
import com.billy65536.infrastructure.security.builtin.ConfigLockerPolicyConfig;
import com.billy65536.infrastructure.security.builtin.ServerOptinPolicy;

/**
 * chunkscanner 安全策略的实际注册落点。
 *
 * <p>作为独立入口类存在，只有在 infrastructure 确认 chunkscanner 已加载后才被类加载，
 * 保证惰性加载语义。</p>
 *
 * <p>默认锁不再由本类直调执行器登记，而是经 {@link SecurityPortal} 注入内置
 * {@code security:server-optin} 策略的静态配置片段。</p>
 */
public final class ChunkscannerSecurityContribution {

    /** 本模块 id，同时用作默认锁的命名空间。 */
    private static final String MODULE_ID = "chunkscanner";

    private ChunkscannerSecurityContribution() {}

    /**
     * 经门户向内置 {@code security:server-optin} 策略贡献 chunkscanner 的默认锁。
     *
     * <p>由 {@link ChunkscannerSecurityProvider} 以方法引用的形式登记。</p>
     */
    public static void register() {
        SecurityPortal.injectConfig(inj -> inj.inject(ServerOptinPolicy.ID,
                ConfigLockerPolicyConfig.builder(ConfigLocker.EXECUTOR_ID)
                        .lock(MODULE_ID, "config", "components.qshop.highlightEnabled", "false")
                        .build()));
    }
}

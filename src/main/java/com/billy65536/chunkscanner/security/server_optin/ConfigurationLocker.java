package com.billy65536.chunkscanner.security.server_optin;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 纯客户端「服务端 opt-in」状态机。
 *
 * <p>纯客户端模组本身不定义服务端授权信号：进入多人服务器时默认<em>锁定</em>
 * （即「等待授权」状态），直到收到服务器授权信号后才解锁。授权信号的接收与
 * 解析由模组其它部分（如网络包处理器）负责，并通过 {@link #setHighlightAuthorized}
 * 通知本类。本地世界（单机）不触发锁定，玩家可自由配置。
 */
public final class ConfigurationLocker {

    private static final Logger LOGGER = LoggerFactory.getLogger("chunkscanner.security.optin.highlight");

    /**
     * 配置锁定状态：{@code true} 表示当前锁定（身处多人服务器且未收到授权）。
     */
    private static final Map<String, Boolean> lockStatus = new HashMap<>(Map.of(
        "components.qshop.highlightEnabled", false // 默认锁定配置
    ));

    private ConfigurationLocker() {}

    /**
     * 判断某配置路径是否属于配置锁定保护范围。
     *
     * @param path 形如 {@code components.qshop.highlightEnabled} 的点分路径
     */
    public static boolean isLocked(String path) {
        return lockStatus.getOrDefault(path, false);
    }

    /**
     * 进入多人服务器：锁定配置（等待服务器授权信号）。
     *
     * <p>由连接事件调用，不在此处定义任何授权信号。
     */
    public static void enterServerLock() {
        lockStatus.replaceAll((k, v) -> true);
        LOGGER.info("Entered multiplayer server: locked, awaiting server authorization.");
    }

    /**
     * 退出服务器：释放配置锁定，恢复玩家自由配置。
     */
    public static void leaveServerLock() {
        lockStatus.replaceAll((k, v) -> false);
        LOGGER.info("Left server: lock released.");
    }

    /**
     * 由服务端授权信号处理逻辑调用，设置高亮授权状态。
     *
     * <p>授权信号的接收与解析不在此类定义。
     * 收到授权时调用 {@code setAuthorized(..., true)} 解锁；
     * 撤销授权时调用 {@code setAuthorized(..., false)} 锁定。
     * 可以锁定未预先定义在此类中的配置。
     *
     * @param path 要改变授权状态的配置路径
     * @param authorized {@code true} 解锁高亮相关配置，{@code false} 重新锁定
     */
    public static void setAuthorized(String[] paths, boolean authorized) {
        for(String path: paths) {
            lockStatus.put(path, !authorized);
        }

        if (authorized) {
            LOGGER.info("Server authorized: {}", Arrays.asList(paths));
        } else {
            LOGGER.info("Server revoked authorization: {}.", Arrays.asList(paths));
        }
    }

}

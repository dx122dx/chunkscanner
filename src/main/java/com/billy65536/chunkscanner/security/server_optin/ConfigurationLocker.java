package com.billy65536.chunkscanner.security.server_optin;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.config.ChunkScannerConfig;
import com.billy65536.chunkscanner.config.ConfigLoader;
import com.billy65536.chunkscanner.config.ConfigReflectionAccessor;
import com.billy65536.chunkscanner.config.ConfigReflectionAccessor.ConfigAccessException;

/**
 * 纯客户端「服务端 opt-in」配置锁定状态机。
 *
 * <p><b>实验性能力（自 1.1.0 起）</b>：锁定的<em>执行</em>链路已完整可用
 * —— 锁定登记、强制值重放、以及针对命令 / Cloth Config GUI / 手动编辑磁盘文件
 * 三条修改通道的防绕过（经 {@code ChunkScannerConfig.validatePostLoad()} → {@link #applyAll}）
 * 均已闭环。但<b>服务端授权信号的网络接收层尚未实现</b>：本模组目前不监听任何自定义
 * 网络包，服务器无法主动下发锁定策略。当前 {@link #setAuthorized} / {@link #setLocked}
 * 只能由外部模组或调试工具（如 chunkscanner-debugger）主动调用。
 * 网络协议（自定义 payload 格式、版本协商、服务端插件对接规范）将在后续版本设计，
 * 届时本类的方法签名可能调整 —— 本类位于 {@code security.server_optin} 包，
 * <b>不属于 {@code com.billy65536.chunkscanner.api} 公共契约</b>，不受向后兼容承诺保护。</p>
 *
 * <p>纯客户端模组本身不定义服务端授权信号：进入多人服务器时默认<em>锁定</em>
 * （即「等待授权」状态），直到收到服务器授权信号后才解锁。授权信号的接收与
 * 解析由模组其它部分（如网络包处理器）负责，并通过 {@link #setAuthorized} /
 * {@link #setLocked} 通知本类。本地世界（单机）不触发锁定，玩家可自由配置。
 *
 * <p>锁定状态统一存放在 {@link #lockStatus} Map 中，value 的语义为：
 * <ul>
 *   <li>{@code key 不存在} = 未锁定（玩家可自由修改）；</li>
 *   <li>{@code key 存在} = 锁定，且强制为该 key 对应的 value。每次配置重载后由
 *       {@link ConfigReflectionAccessor#resetValue} 重放覆盖，防止玩家通过
 *       手动编辑磁盘配置文件绕过。</li>
 * </ul>
 */
public final class ConfigurationLocker {

    private static final Logger LOGGER = LoggerFactory.getLogger("chunkscanner.security.optin.highlight");

    /**
     * 进入多人服务器时默认锁定的配置项 → 强制值。
     * key 为配置路径，value 为强制写入的字符串值（任意字符串，含空串）。
     */
    private static final Map<String, String> DEFAULT_LOCKS = Map.of(
        "components.qshop.highlightEnabled", "false"
    );

    /**
     * 配置锁定状态表：path → 强制值。
     * key 不存在=未锁定；key 存在=锁定且强制为该值，特别的，如果值为 null，则仅禁止玩家修改。
     *
     * <p>本表由网络/连接事件线程（{@link #enterServerLock} / {@link #leaveServerLock}）与
     * 配置线程（{@link #applyAll}）并发访问，必须同步。不能用 {@code ConcurrentHashMap}
     * —— value 允许为 {@code null}（「仅锁定无强制值」语义），CHM 禁止 null value。</p>
     */
    private static final Map<String, String> lockStatus =
            java.util.Collections.synchronizedMap(new HashMap<>());

    private ConfigurationLocker() {}

    /**
     * 判断某配置路径是否属于配置锁定保护范围。
     *
     * @param path 形如 {@code components.qshop.highlightEnabled} 的点分路径
     */
    public static boolean isLocked(String path) {
        return lockStatus.containsKey(path);
    }

    /**
     * 获取某配置路径被服务器强制的值。
     *
     * <p>与 {@link #isLocked} 不同，本方法返回具体强制值（可能为空串）。
     * 未登记（未锁定）时返回 {@code null}。
     *
     * @param path 形如 {@code components.qshop.highlightEnabled} 的点分路径
     * @return 强制值（含空串）；未锁定或不存在则返回 {@code null}
     */
    public static String getValueLocked(String path) {
        return lockStatus.get(path);
    }

    /**
     * 进入多人服务器：按 {@link #DEFAULT_LOCKS} 锁定默认受保护配置（等待服务器授权信号）。
     */
    public static void enterServerLock() {
        setLocked(DEFAULT_LOCKS);
        LOGGER.info("Entered multiplayer server: locked, awaiting server authorization.");
    }

    /**
     * 退出服务器：清空全部锁定状态，恢复玩家自由配置。
     *
     * <p>由断连事件调用。仅释放内存中的锁定登记，不修改磁盘配置文件。
     */
    public static void leaveServerLock() {
        lockStatus.clear();
        LOGGER.info("Left server: lock released.");
    }

    /**
     * 由服务端授权信号处理逻辑调用，解锁指定配置路径。
     *
     * <p>授权信号的接收与解析不在此类定义。输入即解锁（移除锁定登记），
     * 可以解锁任何路径，包括未预先定义在此类中的配置。
     *
     * <p><b>限制</b>：网络接收层尚未实现，本方法目前只能由外部模组或调试工具主动调用。
     * 详见类级文档的实验性说明。
     *
     * @param paths 要解锁的配置路径数组
     */
    public static void setAuthorized(String[] paths) {
        for(String path: paths) {
            lockStatus.remove(path);
        }

        LOGGER.info("Server authorized config editing: {}.", Arrays.asList(paths));
    }

    /**
     * 由服务端信号处理逻辑调用，锁定并可选地强制指定配置路径。
     *
     * <p>传入的 Map 中：value 为非空串表示锁定且强制为该值；value 为空串或
     * {@code null} 表示仅锁定无强制值（仅禁止玩家修改）。锁定后会立即把活动配置
     * 重置为该强制值（或默认值），使玩家在服务器内的配置符合服务器策略。
     * 仅锁定无强制值的项不会触发重置（{@code resetValue} 会拒绝并返回）。
     *
     * @param locks 配置路径 → 强制值（空串/ null 表示仅锁定）
     */
    public static void setLocked(Map<String, String> locks) {
        // value 为 null 表示「仅锁定无强制值」（空串 "" 是合法强制值，需保留）。
        // 是否锁定以 key 是否存在（isLocked）为准，与 value 是否为 null 无关。
        lockStatus.putAll(locks);
        if (!ConfigLoader.isRegistered()) {
            // 配置子系统尚未注册（单元测试 / 外部模组在游戏早期调用）：
            // 先登记锁定，强制值重放推迟到 ConfigLoader.load() 或下次 applyAll。
            LOGGER.debug("Config not registered yet; lock recorded without immediate apply.");
            return;
        }
        applyAll(ChunkScannerMod.getConfig());
    }

    /** 立即强制重置该配置中所有锁定值。 */
    public static void applyAll(ChunkScannerConfig config) {
        // synchronizedMap 的迭代需调用方自行加锁；先取快照再遍历，
        // 避免在反射写配置的耗时操作期间长期持锁。
        Map<String, String> snapshot;
        synchronized (lockStatus) {
            snapshot = new HashMap<>(lockStatus);
        }
        for (Entry<String, String> entry : snapshot.entrySet()) {
            try {
                ConfigReflectionAccessor.applyLockedValue(config, entry.getKey());
            } catch (ConfigAccessException e) {
                LOGGER.warn("Failed to apply value to locked config item: {}", e.toString());
            }
        }
    }

}

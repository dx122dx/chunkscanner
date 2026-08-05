package com.billy65536.chunkscanner.security.server_optin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import com.billy65536.chunkscanner.config.ChunkScannerConfig;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConfigurationLocker 单元测试。
 *
 * <p>锁定的核心语义是 <b>key 存在即锁定</b>，与 value 是否为 {@code null} 无关：</p>
 * <ul>
 *   <li>key 不存在 —— 未锁定，玩家可自由修改</li>
 *   <li>key 存在 + value 非 null —— 锁定且强制为该值（空串是合法强制值）</li>
 *   <li>key 存在 + value 为 null —— 仅禁止修改，不强制任何值</li>
 * </ul>
 *
 * <p><b>静态状态隔离</b>：{@code lockStatus} 是静态 Map，用例间会互相污染，
 * 每个用例前后都通过 {@link ConfigurationLocker#leaveServerLock()} 显式清空。</p>
 */
@DisplayName("ConfigurationLocker")
class ConfigurationLockerTest {

    /** DEFAULT_LOCKS 中唯一的默认锁定项。 */
    private static final String QSHOP_HIGHLIGHT = "components.qshop.highlightEnabled";

    @BeforeEach
    void reset() {
        ConfigurationLocker.leaveServerLock();
        assertFalse(ConfigurationLocker.isLocked(QSHOP_HIGHLIGHT), "静态锁定表未清理干净");
    }

    @AfterEach
    void cleanUp() {
        ConfigurationLocker.leaveServerLock();
    }

    /** 构造一个可变的锁定映射（{@code Map.of} 不允许 null value）。 */
    private static Map<String, String> locks(String key, String value) {
        Map<String, String> m = new HashMap<>();
        m.put(key, value);
        return m;
    }

    // ==================== isLocked / getValueLocked 语义 ====================

    @Nested
    @DisplayName("锁定语义")
    class LockSemantics {

        @Test
        @DisplayName("未登记的路径未锁定")
        void unregisteredPath_shouldNotBeLocked() {
            assertFalse(ConfigurationLocker.isLocked("scanner.maxTasksPerTick"));
            assertNull(ConfigurationLocker.getValueLocked("scanner.maxTasksPerTick"));
        }

        @Test
        @DisplayName("key 存在 + 非 null value = 锁定且有强制值")
        void keyWithValue_shouldLockAndForce() {
            ConfigurationLocker.setLocked(locks(QSHOP_HIGHLIGHT, "false"));

            assertTrue(ConfigurationLocker.isLocked(QSHOP_HIGHLIGHT));
            assertEquals("false", ConfigurationLocker.getValueLocked(QSHOP_HIGHLIGHT));
        }

        @Test
        @DisplayName("key 存在 + null value = 仅锁定无强制值")
        void keyWithNullValue_shouldLockWithoutForcing() {
            ConfigurationLocker.setLocked(locks(QSHOP_HIGHLIGHT, null));

            assertTrue(ConfigurationLocker.isLocked(QSHOP_HIGHLIGHT),
                    "value 为 null 时仍应视为锁定，判定依据是 key 是否存在");
            assertNull(ConfigurationLocker.getValueLocked(QSHOP_HIGHLIGHT));
        }

        @Test
        @DisplayName("空串是合法强制值，不等同于 null")
        void emptyStringValue_shouldBeValidForcedValue() {
            ConfigurationLocker.setLocked(locks("components.qshop.sellKeyword", ""));

            assertTrue(ConfigurationLocker.isLocked("components.qshop.sellKeyword"));
            assertEquals("", ConfigurationLocker.getValueLocked("components.qshop.sellKeyword"),
                    "空串必须与 null 区分，前者是强制为空值，后者是不强制");
        }

        @Test
        @DisplayName("未预定义的任意路径也可被锁定")
        void arbitraryPath_shouldBeLockable() {
            ConfigurationLocker.setLocked(locks("some.future.path", "x"));
            assertTrue(ConfigurationLocker.isLocked("some.future.path"));
        }

        @Test
        @DisplayName("重复 setLocked 同一 key 覆盖强制值")
        void repeatedSetLocked_shouldOverrideValue() {
            ConfigurationLocker.setLocked(locks(QSHOP_HIGHLIGHT, "false"));
            ConfigurationLocker.setLocked(locks(QSHOP_HIGHLIGHT, "true"));

            assertEquals("true", ConfigurationLocker.getValueLocked(QSHOP_HIGHLIGHT));
        }

        @Test
        @DisplayName("setLocked 多个路径同时生效")
        void setLocked_multiplePaths_shouldAllApply() {
            Map<String, String> m = new HashMap<>();
            m.put(QSHOP_HIGHLIGHT, "false");
            m.put("scanner.maxTasksPerTick", "8");
            ConfigurationLocker.setLocked(m);

            assertTrue(ConfigurationLocker.isLocked(QSHOP_HIGHLIGHT));
            assertTrue(ConfigurationLocker.isLocked("scanner.maxTasksPerTick"));
        }

        @Test
        @DisplayName("setLocked 空 Map 不改变现有状态")
        void setLocked_emptyMap_shouldBeNoOp() {
            ConfigurationLocker.setLocked(locks(QSHOP_HIGHLIGHT, "false"));
            ConfigurationLocker.setLocked(new HashMap<>());

            assertTrue(ConfigurationLocker.isLocked(QSHOP_HIGHLIGHT));
            assertEquals("false", ConfigurationLocker.getValueLocked(QSHOP_HIGHLIGHT));
        }
    }

    // ==================== 进入 / 退出服务器 ====================

    @Nested
    @DisplayName("enterServerLock / leaveServerLock")
    class ServerLifecycle {

        @Test
        @DisplayName("进入服务器锁定 QShop 高亮（DEFAULT_LOCKS）")
        void enterServerLock_shouldApplyDefaultLocks() {
            ConfigurationLocker.enterServerLock();

            assertTrue(ConfigurationLocker.isLocked(QSHOP_HIGHLIGHT),
                    "进入多人服务器必须默认锁定 QShop 高亮，等待服务端授权");
            assertEquals("false", ConfigurationLocker.getValueLocked(QSHOP_HIGHLIGHT),
                    "默认强制值应为 false");
        }

        @Test
        @DisplayName("退出服务器清空所有锁定")
        void leaveServerLock_shouldClearAll() {
            ConfigurationLocker.enterServerLock();
            ConfigurationLocker.setLocked(locks("scanner.maxTasksPerTick", "4"));

            ConfigurationLocker.leaveServerLock();

            assertFalse(ConfigurationLocker.isLocked(QSHOP_HIGHLIGHT));
            assertFalse(ConfigurationLocker.isLocked("scanner.maxTasksPerTick"));
        }

        @Test
        @DisplayName("重复 leaveServerLock 是幂等的")
        void leaveServerLock_shouldBeIdempotent() {
            ConfigurationLocker.enterServerLock();
            ConfigurationLocker.leaveServerLock();
            assertDoesNotThrow(ConfigurationLocker::leaveServerLock);
            assertFalse(ConfigurationLocker.isLocked(QSHOP_HIGHLIGHT));
        }

        @Test
        @DisplayName("重复 enterServerLock 是幂等的")
        void enterServerLock_shouldBeIdempotent() {
            ConfigurationLocker.enterServerLock();
            ConfigurationLocker.enterServerLock();

            assertTrue(ConfigurationLocker.isLocked(QSHOP_HIGHLIGHT));
            assertEquals("false", ConfigurationLocker.getValueLocked(QSHOP_HIGHLIGHT));
        }
    }

    // ==================== 授权解锁 ====================

    @Nested
    @DisplayName("setAuthorized")
    class Authorization {

        @Test
        @DisplayName("授权后对应路径解锁")
        void setAuthorized_shouldUnlockPath() {
            ConfigurationLocker.enterServerLock();
            assertTrue(ConfigurationLocker.isLocked(QSHOP_HIGHLIGHT));

            ConfigurationLocker.setAuthorized(new String[]{QSHOP_HIGHLIGHT});

            assertFalse(ConfigurationLocker.isLocked(QSHOP_HIGHLIGHT));
            assertNull(ConfigurationLocker.getValueLocked(QSHOP_HIGHLIGHT));
        }

        @Test
        @DisplayName("授权只影响列出的路径")
        void setAuthorized_shouldOnlyAffectListedPaths() {
            Map<String, String> m = new HashMap<>();
            m.put(QSHOP_HIGHLIGHT, "false");
            m.put("scanner.maxTasksPerTick", "8");
            ConfigurationLocker.setLocked(m);

            ConfigurationLocker.setAuthorized(new String[]{QSHOP_HIGHLIGHT});

            assertFalse(ConfigurationLocker.isLocked(QSHOP_HIGHLIGHT));
            assertTrue(ConfigurationLocker.isLocked("scanner.maxTasksPerTick"),
                    "未被授权的路径应保持锁定");
        }

        @Test
        @DisplayName("授权未锁定的路径不抛异常")
        void setAuthorized_unlockedPath_shouldNotThrow() {
            assertDoesNotThrow(() ->
                    ConfigurationLocker.setAuthorized(new String[]{"never.locked.path"}));
        }

        @Test
        @DisplayName("授权空数组不改变状态")
        void setAuthorized_emptyArray_shouldBeNoOp() {
            ConfigurationLocker.enterServerLock();
            ConfigurationLocker.setAuthorized(new String[0]);

            assertTrue(ConfigurationLocker.isLocked(QSHOP_HIGHLIGHT));
        }

        @Test
        @DisplayName("可授权任意路径，包括未预定义在 DEFAULT_LOCKS 中的")
        void setAuthorized_arbitraryPath_shouldWork() {
            ConfigurationLocker.setLocked(locks("custom.future.option", "v"));
            ConfigurationLocker.setAuthorized(new String[]{"custom.future.option"});

            assertFalse(ConfigurationLocker.isLocked("custom.future.option"));
        }

        @Test
        @DisplayName("授权后再次进入服务器会重新锁定默认项")
        void reenterAfterAuthorized_shouldRelock() {
            ConfigurationLocker.enterServerLock();
            ConfigurationLocker.setAuthorized(new String[]{QSHOP_HIGHLIGHT});
            assertFalse(ConfigurationLocker.isLocked(QSHOP_HIGHLIGHT));

            ConfigurationLocker.leaveServerLock();
            ConfigurationLocker.enterServerLock();

            assertTrue(ConfigurationLocker.isLocked(QSHOP_HIGHLIGHT),
                    "换服务器后授权必须失效，重新回到等待授权状态");
        }
    }

    // ==================== applyAll 强制值重放 ====================

    @Nested
    @DisplayName("applyAll")
    class ApplyAll {

        @Test
        @DisplayName("把锁定的强制值重放到配置对象（防手改磁盘文件绕过）")
        void applyAll_shouldOverwriteConfigValue() {
            ChunkScannerConfig config = new ChunkScannerConfig();
            config.components.qshop.highlightEnabled = true; // 模拟玩家手改配置文件

            ConfigurationLocker.setLocked(locks(QSHOP_HIGHLIGHT, "false"));
            ConfigurationLocker.applyAll(config);

            assertFalse(config.components.qshop.highlightEnabled,
                    "锁定的强制值必须在每次配置重载后被重放，否则玩家可手改磁盘文件绕过");
        }

        @Test
        @DisplayName("仅锁定无强制值时不修改配置")
        void applyAll_nullValue_shouldNotModifyConfig() {
            ChunkScannerConfig config = new ChunkScannerConfig();
            config.components.qshop.highlightEnabled = true;

            ConfigurationLocker.setLocked(locks(QSHOP_HIGHLIGHT, null));
            ConfigurationLocker.applyAll(config);

            assertTrue(config.components.qshop.highlightEnabled,
                    "value 为 null 表示仅禁止修改，不应强制覆盖当前值");
        }

        @Test
        @DisplayName("无锁定时 applyAll 不改变配置")
        void applyAll_noLocks_shouldBeNoOp() {
            ChunkScannerConfig config = new ChunkScannerConfig();
            config.components.qshop.highlightEnabled = true;

            ConfigurationLocker.applyAll(config);

            assertTrue(config.components.qshop.highlightEnabled);
        }

        @Test
        @DisplayName("未知路径被静默跳过，不影响其他锁定项")
        void applyAll_unknownPath_shouldNotBreakOthers() {
            ChunkScannerConfig config = new ChunkScannerConfig();
            config.components.qshop.highlightEnabled = true;

            Map<String, String> m = new HashMap<>();
            m.put("totally.bogus.path", "1");
            m.put(QSHOP_HIGHLIGHT, "false");
            ConfigurationLocker.setLocked(m);

            assertDoesNotThrow(() -> ConfigurationLocker.applyAll(config));
            assertFalse(config.components.qshop.highlightEnabled,
                    "单个非法路径不应阻断其余锁定项的重放");
        }

        @Test
        @DisplayName("applyAll(null) 不抛异常")
        void applyAll_nullConfig_shouldNotThrow() {
            ConfigurationLocker.setLocked(locks(QSHOP_HIGHLIGHT, "false"));
            assertDoesNotThrow(() -> ConfigurationLocker.applyAll(null));
        }

        @Test
        @DisplayName("多次 applyAll 结果一致（幂等）")
        void applyAll_shouldBeIdempotent() {
            ChunkScannerConfig config = new ChunkScannerConfig();
            ConfigurationLocker.setLocked(locks(QSHOP_HIGHLIGHT, "false"));

            ConfigurationLocker.applyAll(config);
            config.components.qshop.highlightEnabled = true;
            ConfigurationLocker.applyAll(config);

            assertFalse(config.components.qshop.highlightEnabled);
        }
    }
}

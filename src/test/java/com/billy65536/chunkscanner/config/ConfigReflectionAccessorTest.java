package com.billy65536.chunkscanner.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfigReflectionAccessor} 单元测试。
 *
 * <p>覆盖：路径索引完整性与顺序、各类型 get/set 往返、reset 回到默认、
 * 非法路径与非法值的异常、bool/enum 补全候选。
 */
class ConfigReflectionAccessorTest {

    /** 配置项总数：scanner 7 + xaero 3 + baritone 4 + qshop 13 = 27。 */
    private static final int EXPECTED_PATH_COUNT = 27;

    @Test
    void listPathsCoversAllLeafFields() {
        List<String> paths = List.copyOf(ConfigReflectionAccessor.listPaths());
        assertEquals(EXPECTED_PATH_COUNT, paths.size(),
                "路径总数应等于全部叶子字段数，实际路径：" + paths);
    }

    @Test
    void pathsUseDottedObjectHierarchy() {
        assertTrue(ConfigReflectionAccessor.hasPath("scanner.maxTasksPerTick"));
        assertTrue(ConfigReflectionAccessor.hasPath("integration.xaero.name"));
        assertTrue(ConfigReflectionAccessor.hasPath("integration.baritone.riskWarning"));
        assertTrue(ConfigReflectionAccessor.hasPath("components.qshop.sellKeyword"));
        // 中间节点本身不是叶子，不应出现在索引中
        assertFalse(ConfigReflectionAccessor.hasPath("scanner"));
        assertFalse(ConfigReflectionAccessor.hasPath("integration.xaero"));
    }

    @Test
    void pathOrderIsStableAcrossCalls() {
        assertEquals(List.copyOf(ConfigReflectionAccessor.listPaths()),
                List.copyOf(ConfigReflectionAccessor.listPaths()));
    }

    @Test
    void defaultValuesComeFromFieldInitializers() {
        assertEquals(60, ConfigReflectionAccessor.getDefaultValue("scanner.minRevisitIntervalSec"));
        assertEquals(1.0, ConfigReflectionAccessor.getDefaultValue("scanner.scanRadiusMultiplier"));
        assertEquals("目标", ConfigReflectionAccessor.getDefaultValue("integration.xaero.initials"));
        assertEquals(ChunkScannerConfig.BaritoneRiskWarning.SHOWN,
                ConfigReflectionAccessor.getDefaultValue("integration.baritone.riskWarning"));
    }

    @Test
    void setAndGetRoundTripForEachType() throws Exception {
        ChunkScannerConfig cfg = new ChunkScannerConfig();

        ConfigReflectionAccessor.setValue(cfg, "scanner.maxTasksPerTick", "24");
        assertEquals(24, cfg.scanner.maxTasksPerTick);
        assertEquals(24, ConfigReflectionAccessor.getValue(cfg, "scanner.maxTasksPerTick"));

        ConfigReflectionAccessor.setValue(cfg, "scanner.targetTickNs", "8000000");
        assertEquals(8_000_000L, cfg.scanner.targetTickNs);

        ConfigReflectionAccessor.setValue(cfg, "scanner.scanRadiusMultiplier", "2.5");
        assertEquals(2.5, cfg.scanner.scanRadiusMultiplier);

        ConfigReflectionAccessor.setValue(cfg, "components.qshop.highlightEnabled", "true");
        assertTrue(cfg.components.qshop.highlightEnabled);

        ConfigReflectionAccessor.setValue(cfg, "integration.xaero.name", "我的商店");
        assertEquals("我的商店", cfg.integration.xaero.name);

        ConfigReflectionAccessor.setValue(cfg, "integration.baritone.riskWarning", "HIDDEN");
        assertEquals(ChunkScannerConfig.BaritoneRiskWarning.HIDDEN,
                cfg.integration.baritone.riskWarning);
    }

    @Test
    void enumParsingIsCaseInsensitive() throws Exception {
        ChunkScannerConfig cfg = new ChunkScannerConfig();
        ConfigReflectionAccessor.setValue(cfg, "components.qshop.enhanceMatchMode", "weakautomatic");
        assertEquals(ChunkScannerConfig.EnhanceMatchMode.WeakAutomatic,
                cfg.components.qshop.enhanceMatchMode);
    }

    @Test
    void stringValuePreservesSpacesAndRegex() throws Exception {
        ChunkScannerConfig cfg = new ChunkScannerConfig();
        String regex = "^\\s*(sell|buy)\\s+(\\d+)";
        ConfigReflectionAccessor.setValue(cfg, "components.qshop.sellBuyPattern", regex);
        assertEquals(regex, cfg.components.qshop.sellBuyPattern);
    }

    @Test
    void resetRestoresDefaultValue() throws Exception {
        ChunkScannerConfig cfg = new ChunkScannerConfig();
        ConfigReflectionAccessor.setValue(cfg, "scanner.workerThreads", "8");
        assertNotEquals(2, cfg.scanner.workerThreads);

        ConfigReflectionAccessor.resetValue(cfg, "scanner.workerThreads");
        assertEquals(2, cfg.scanner.workerThreads);
    }

    @Test
    void resetDoesNotAffectPristineSnapshot() throws Exception {
        ChunkScannerConfig cfg = new ChunkScannerConfig();
        ConfigReflectionAccessor.setValue(cfg, "integration.xaero.group", "changed");
        assertEquals("chunkscanner", ConfigReflectionAccessor.getDefaultValue("integration.xaero.group"));
        ConfigReflectionAccessor.resetValue(cfg, "integration.xaero.group");
        assertEquals("chunkscanner", cfg.integration.xaero.group);
    }

    @Test
    void unknownPathThrows() {
        ChunkScannerConfig cfg = new ChunkScannerConfig();
        assertFalse(ConfigReflectionAccessor.hasPath("scanner.nonexistent"));
        assertThrows(ConfigReflectionAccessor.ConfigAccessException.class,
                () -> ConfigReflectionAccessor.setValue(cfg, "scanner.nonexistent", "1"));
        assertThrows(ConfigReflectionAccessor.ConfigAccessException.class,
                () -> ConfigReflectionAccessor.resetValue(cfg, "scanner.nonexistent"));
    }

    @Test
    void invalidNumberThrowsWithTypeHint() {
        ChunkScannerConfig cfg = new ChunkScannerConfig();
        var e = assertThrows(ConfigReflectionAccessor.ConfigAccessException.class,
                () -> ConfigReflectionAccessor.setValue(cfg, "scanner.maxTasksPerTick", "abc"));
        assertTrue(e.getMessage().contains("int"), "错误消息应包含期望类型：" + e.getMessage());
    }

    @Test
    void invalidBooleanThrowsInsteadOfSilentlyFalse() {
        ChunkScannerConfig cfg = new ChunkScannerConfig();
        assertThrows(ConfigReflectionAccessor.ConfigAccessException.class,
                () -> ConfigReflectionAccessor.setValue(cfg, "components.qshop.highlightEnabled", "yes"));
        // 校验失败时原值保持不变
        assertFalse(cfg.components.qshop.highlightEnabled);
    }

    @Test
    void invalidEnumListsLegalConstants() {
        ChunkScannerConfig cfg = new ChunkScannerConfig();
        var e = assertThrows(ConfigReflectionAccessor.ConfigAccessException.class,
                () -> ConfigReflectionAccessor.setValue(cfg, "integration.baritone.riskWarning", "NOPE"));
        assertTrue(e.getMessage().contains("SHOWN"), "错误消息应列出合法枚举值：" + e.getMessage());
        assertTrue(e.getMessage().contains("BARITONE_DISABLED"));
    }

    @Test
    void suggestValuesForBoolean() {
        assertEquals(List.of("true", "false"),
                ConfigReflectionAccessor.suggestValues(new ChunkScannerConfig(),
                        "components.qshop.highlightEnabled"));
    }

    @Test
    void suggestValuesForEnumListsAllConstants() {
        List<String> suggestions = ConfigReflectionAccessor.suggestValues(
                new ChunkScannerConfig(), "components.qshop.enhanceMatchMode");
        assertEquals(ChunkScannerConfig.EnhanceMatchMode.values().length, suggestions.size());
        assertTrue(suggestions.contains("StrictAutomatic"));
        assertTrue(suggestions.contains("Disabled"));
    }

    @Test
    void suggestValuesForOtherTypesReturnsCurrentValue() {
        ChunkScannerConfig cfg = new ChunkScannerConfig();
        cfg.scanner.maxTasksPerTick = 30;
        assertEquals(List.of("30"),
                ConfigReflectionAccessor.suggestValues(cfg, "scanner.maxTasksPerTick"));
    }

    @Test
    void suggestValuesDegradesSafelyForUnknownPath() {
        assertTrue(ConfigReflectionAccessor.suggestValues(new ChunkScannerConfig(), "bogus").isEmpty());
    }

    @Test
    void getTypeNameReportsLeafType() {
        assertEquals("int", ConfigReflectionAccessor.getTypeName("scanner.maxTasksPerTick"));
        assertEquals("String", ConfigReflectionAccessor.getTypeName("integration.xaero.name"));
        assertEquals("BaritoneRiskWarning",
                ConfigReflectionAccessor.getTypeName("integration.baritone.riskWarning"));
        assertNotNull(ConfigReflectionAccessor.getTypeName("components.qshop.highlightGradientMs"));
    }
}

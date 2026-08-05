package com.billy65536.chunkscanner.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import net.minecraft.util.Identifier;

import com.billy65536.chunkscanner.ChunkScannerMod;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChunkScannerApi 公共契约单元测试。
 *
 * <p>本类锁定的是<b>对外承诺</b>，任何断言失败都意味着破坏了下游模组
 * （qab / chunkscanner-debugger）的接入假设。</p>
 */
@DisplayName("ChunkScannerApi")
class ChunkScannerApiTest {

    // ==================== 常量契约 ====================

    @Nested
    @DisplayName("常量契约")
    class Constants {

        @Test
        @DisplayName("API_VERSION 为 1（下游据此做能力探测，变更需同步公告）")
        void apiVersion_shouldBeOne() {
            assertEquals(1, ChunkScannerApi.API_VERSION);
        }

        @Test
        @DisplayName("MOD_ID 与 ChunkScannerMod.MOD_ID 一致")
        void modId_shouldMatchModConstant() {
            assertEquals(ChunkScannerMod.MOD_ID, ChunkScannerApi.MOD_ID);
            assertEquals("chunkscanner", ChunkScannerApi.MOD_ID);
        }
    }

    // ==================== id() ====================

    @Nested
    @DisplayName("id(path)")
    class IdFactory {

        @Test
        @DisplayName("返回 chunkscanner 命名空间下的标识符")
        void id_shouldUseChunkscannerNamespace() {
            Identifier id = ChunkScannerApi.id("qshop");
            assertEquals("chunkscanner", id.getNamespace());
            assertEquals("qshop", id.getPath());
        }

        @Test
        @DisplayName("等价于 new Identifier(MOD_ID, path)")
        void id_shouldEqualDirectConstruction() {
            assertEquals(new Identifier("chunkscanner", "sign"), ChunkScannerApi.id("sign"));
        }

        @Test
        @DisplayName("与 ChunkScannerMod.id 行为一致")
        void id_shouldDelegateToModId() {
            assertEquals(ChunkScannerMod.id("raw"), ChunkScannerApi.id("raw"));
        }

        @Test
        @DisplayName("允许 [a-z0-9/._-] 字符组合")
        void id_shouldAcceptLegalPathCharacters() {
            assertDoesNotThrow(() -> ChunkScannerApi.id("a-b_c.d/e0"));
        }

        @Test
        @DisplayName("大写字母为非法 path，应抛出 InvalidIdentifierException")
        void id_shouldRejectUpperCasePath() {
            assertThrows(net.minecraft.util.InvalidIdentifierException.class,
                    () -> ChunkScannerApi.id("QShop"));
        }
    }

    // ==================== unknownId() ====================

    @Nested
    @DisplayName("unknownId()")
    class UnknownSentinel {

        @Test
        @DisplayName("返回 undefined:undefined 哨兵")
        void unknownId_shouldBeUndefinedSentinel() {
            Identifier unknown = ChunkScannerApi.unknownId();
            assertEquals("undefined", unknown.getNamespace());
            assertEquals("undefined", unknown.getPath());
        }

        @Test
        @DisplayName("与 ChunkScannerMod.ID_UNKNOWN 为同一值")
        void unknownId_shouldMatchModConstant() {
            assertEquals(ChunkScannerMod.ID_UNKNOWN, ChunkScannerApi.unknownId());
        }

        @Test
        @DisplayName("哨兵的 path 非空（故不能用 isEmpty 判空，必须用 equals 比较）")
        void unknownId_pathShouldNotBeEmpty() {
            assertFalse(ChunkScannerApi.unknownId().getPath().isEmpty());
        }

        @Test
        @DisplayName("多次调用返回相等值")
        void unknownId_shouldBeStable() {
            assertEquals(ChunkScannerApi.unknownId(), ChunkScannerApi.unknownId());
        }
    }

    // ==================== isReady() ====================

    @Nested
    @DisplayName("isReady()")
    class Readiness {

        @Test
        @DisplayName("未初始化环境下返回 false 且不抛异常")
        void isReady_shouldNotThrowWhenUninitialized() {
            assertDoesNotThrow(ChunkScannerApi::isReady);
        }
    }
}

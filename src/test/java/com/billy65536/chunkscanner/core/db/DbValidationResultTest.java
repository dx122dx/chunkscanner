package com.billy65536.chunkscanner.core.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DbValidationResult 单元测试（纯逻辑，零外部依赖）。
 */
@DisplayName("DbValidationResult")
class DbValidationResultTest {

    @Test
    @DisplayName("无错误无警告时表示校验通过")
    void cleanResult_shouldBeValid() {
        DbValidationResult r = new DbValidationResult(true, List.of(), List.of());

        assertTrue(r.valid());
        assertTrue(r.errors().isEmpty());
        assertTrue(r.warnings().isEmpty());
    }

    @Test
    @DisplayName("有错误时表示校验失败并保留错误信息")
    void withErrors_shouldBeInvalid() {
        DbValidationResult r = new DbValidationResult(false,
                List.of("SHA-256 mismatch", "mainFile missing"), List.of());

        assertFalse(r.valid());
        assertEquals(2, r.errors().size());
        assertTrue(r.errors().contains("SHA-256 mismatch"));
    }

    @Test
    @DisplayName("仅有警告不影响校验通过")
    void warningsOnly_shouldStayValid() {
        DbValidationResult r = new DbValidationResult(true, List.of(),
                List.of("databaseType is missing; will fall back to default factory"));

        assertTrue(r.valid(), "警告不是致命问题，不应导致校验失败");
        assertEquals(1, r.warnings().size());
    }

    @Test
    @DisplayName("错误与警告可同时携带")
    void errorsAndWarnings_shouldCoexist() {
        DbValidationResult r = new DbValidationResult(false,
                List.of("err"), List.of("warn"));

        assertFalse(r.valid());
        assertEquals(List.of("err"), r.errors());
        assertEquals(List.of("warn"), r.warnings());
    }

    @Test
    @DisplayName("record 语义：相同内容的实例相等")
    void records_withSameContent_shouldBeEqual() {
        DbValidationResult a = new DbValidationResult(false, List.of("e"), List.of("w"));
        DbValidationResult b = new DbValidationResult(false, List.of("e"), List.of("w"));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("record 语义：valid 不同则不相等")
    void records_withDifferentValid_shouldNotBeEqual() {
        assertNotEquals(new DbValidationResult(true, List.of(), List.of()),
                new DbValidationResult(false, List.of(), List.of()));
    }

    @Test
    @DisplayName("toString 包含关键字段便于日志排查")
    void toString_shouldContainFields() {
        String s = new DbValidationResult(false, List.of("boom"), List.of()).toString();

        assertTrue(s.contains("false"));
        assertTrue(s.contains("boom"));
    }
}

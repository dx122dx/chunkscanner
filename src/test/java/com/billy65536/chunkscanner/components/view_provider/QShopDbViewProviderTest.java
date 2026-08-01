package com.billy65536.chunkscanner.components.view_provider;

import com.billy65536.chunkscanner.components.analyzer.QShopAnalyzer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QShopDbViewProvider 单元测试。
 * 主要测试 matchesFlags 的 bitflag 筛选逻辑（含/除/全三种模式）。
 */
@DisplayName("QShopDbViewProvider")
class QShopDbViewProviderTest {

    // ==================== matchesFlags() ====================

    @Test
    @DisplayName("空/null 筛选 → 直接通过")
    void emptyFilter_shouldPass() {
        assertTrue(QShopFilter.matchesFlags(QShopAnalyzer.FLAG_ID_RECOVERED, null,
                QShopFilter.PATTERN_CONTAINS));
        assertTrue(QShopFilter.matchesFlags(QShopAnalyzer.FLAG_BOOK, "",
                QShopFilter.PATTERN_CONTAINS));
    }

    @Test
    @DisplayName("无效字符 → mask 为 0 → 直接通过")
    void invalidChars_shouldPass() {
        assertTrue(QShopFilter.matchesFlags(0, "XYZ",
                QShopFilter.PATTERN_CONTAINS));
    }

    @Test
    @DisplayName("单标志: 含 → 记录中有该位则通过")
    void contains_singleFlag_matchesAny() {
        int flags = QShopAnalyzer.FLAG_ID_RECOVERED | QShopAnalyzer.FLAG_BOOK;
        assertTrue(QShopFilter.matchesFlags(flags, "R",
                QShopFilter.PATTERN_CONTAINS));
        assertTrue(QShopFilter.matchesFlags(flags, "B",
                QShopFilter.PATTERN_CONTAINS));
        assertFalse(QShopFilter.matchesFlags(flags, "E",
                QShopFilter.PATTERN_CONTAINS));
    }

    @Test
    @DisplayName("多标志: 含 → 任一匹配即通过")
    void contains_multiFlag_matchesAny() {
        int flags = QShopAnalyzer.FLAG_ID_RECOVERED;
        assertTrue(QShopFilter.matchesFlags(flags, "RE",
                QShopFilter.PATTERN_CONTAINS));  // R 命中
    }

    @Test
    @DisplayName("单标志: 除 → 记录中没有该位则通过")
    void exclude_singleFlag_excludes() {
        int flags = QShopAnalyzer.FLAG_ID_RECOVERED | QShopAnalyzer.FLAG_BOOK;
        assertFalse(QShopFilter.matchesFlags(flags, "R",
                QShopFilter.PATTERN_EXCLUDE));
        assertTrue(QShopFilter.matchesFlags(flags, "E",
                QShopFilter.PATTERN_EXCLUDE));
    }

    @Test
    @DisplayName("多标志: 除 → 全部不匹配才通过")
    void exclude_multiFlag_excludesAll() {
        int flags = QShopAnalyzer.FLAG_ID_RECOVERED;
        assertFalse(QShopFilter.matchesFlags(flags, "RE",
                QShopFilter.PATTERN_EXCLUDE));  // R 命中，不通过
        assertTrue(QShopFilter.matchesFlags(flags, "ES",
                QShopFilter.PATTERN_EXCLUDE));   // E 和 S 都不命中，通过
    }

    @Test
    @DisplayName("单标志: 全 → 必须全部匹配")
    void exact_singleFlag_matchesAll() {
        int flags = QShopAnalyzer.FLAG_ID_RECOVERED | QShopAnalyzer.FLAG_BOOK;
        assertTrue(QShopFilter.matchesFlags(flags, "R",
                QShopFilter.PATTERN_EXACT));
        assertFalse(QShopFilter.matchesFlags(flags, "E",
                QShopFilter.PATTERN_EXACT));
    }

    @Test
    @DisplayName("多标志: 全 → 必须全部匹配")
    void exact_multiFlag_matchesAll() {
        int flags = QShopAnalyzer.FLAG_ID_RECOVERED | QShopAnalyzer.FLAG_BOOK;
        assertTrue(QShopFilter.matchesFlags(flags, "RB",
                QShopFilter.PATTERN_EXACT));   // R 和 B 都命中
        assertFalse(QShopFilter.matchesFlags(flags, "RE",
                QShopFilter.PATTERN_EXACT));   // E 不命中
    }

    @Test
    @DisplayName("大小写不敏感")
    void caseInsensitive() {
        int flags = QShopAnalyzer.FLAG_ID_RECOVERED | QShopAnalyzer.FLAG_ENHANCED_DATA;
        assertTrue(QShopFilter.matchesFlags(flags, "re",
                QShopFilter.PATTERN_EXACT));
        assertTrue(QShopFilter.matchesFlags(flags, "r",
                QShopFilter.PATTERN_CONTAINS));
    }

    @Test
    @DisplayName("记录无任何 flag → 含/全都失败，除通过")
    void noFlags_atAll() {
        assertFalse(QShopFilter.matchesFlags(0, "R",
                QShopFilter.PATTERN_CONTAINS));
        assertFalse(QShopFilter.matchesFlags(0, "R",
                QShopFilter.PATTERN_EXACT));
        assertTrue(QShopFilter.matchesFlags(0, "R",
                QShopFilter.PATTERN_EXCLUDE));
    }

    @Test
    @DisplayName("所有 flag 组合 (R/E/S/B) 各自正确")
    void allFlags_individuallyCorrect() {
        assertTrue(QShopFilter.matchesFlags(QShopAnalyzer.FLAG_ID_RECOVERED, "R",
                QShopFilter.PATTERN_CONTAINS));
        assertTrue(QShopFilter.matchesFlags(QShopAnalyzer.FLAG_ENHANCED_DATA, "E",
                QShopFilter.PATTERN_CONTAINS));
        assertTrue(QShopFilter.matchesFlags(QShopAnalyzer.FLAG_SHULKER_EXPANDED, "S",
                QShopFilter.PATTERN_CONTAINS));
        assertTrue(QShopFilter.matchesFlags(QShopAnalyzer.FLAG_BOOK, "B",
                QShopFilter.PATTERN_CONTAINS));
    }

    @Test
    @DisplayName("混合大小写: rEsB 匹配 R/E/S/B 全集合")
    void mixedCase() {
        int flags = QShopAnalyzer.FLAG_ID_RECOVERED
                | QShopAnalyzer.FLAG_ENHANCED_DATA
                | QShopAnalyzer.FLAG_SHULKER_EXPANDED
                | QShopAnalyzer.FLAG_BOOK;
        assertTrue(QShopFilter.matchesFlags(flags, "rEsB",
                QShopFilter.PATTERN_EXACT));
    }
}

package com.billy65536.chunkscanner.core.db;

import java.util.List;

/**
 * 导出数据库包（ZIP）的校验结果。
 *
 * <p>同时携带错误（致命、导致无法加载）与警告（不影响加载但值得注意），
 * 调用方应根据 {@link #valid()} 决定是否继续加载。</p>
 */
public record DbValidationResult(boolean valid, List<String> errors, List<String> warnings) { }

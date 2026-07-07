package com.billy65536.chunkscanner.core;

/**
 * 分析器返回结果。
 *
 * status 为 bitflag 组合，低 8 位定义如下：
 *   bit 0 (0x01): SUCCESS    — 分析过程正常完成
 *   bit 1 (0x02): FOUND      — 找到了目标数据
 *   bit 2 (0x04): ERROR      — 发生了错误
 *   bit 3 (0x08): SKIPPED    — 跳过（无数据或不符合条件）
 *
 * 常用组合：
 *   SUCCESS | FOUND  (0x03) — 正常完成且有数据
 *   SUCCESS | SKIPPED (0x09) — 正常完成但无数据
 *   ERROR              (0x04) — 发生错误
 *   SUCCESS | ERROR    (0x05) — 部分成功但有一些错误
 *
 * info 为可选附加信息（错误描述、统计等）。
 */
public final class AnalyzeResult {

    // status bitflag 常量
    public static final int SUCCESS = 0x01;
    public static final int FOUND   = 0x02;
    public static final int ERROR   = 0x04;
    public static final int SKIPPED = 0x08;

    private final int status;
    private final String info;

    private AnalyzeResult(int status, String info) {
        this.status = status;
        this.info = info;
    }

    // ==================== 工厂方法 ====================

    /** 成功且有数据。 */
    public static AnalyzeResult found(String info) {
        return new AnalyzeResult(SUCCESS | FOUND, info);
    }

    /** 成功且有数据，无附加信息。 */
    public static AnalyzeResult found() {
        return new AnalyzeResult(SUCCESS | FOUND, null);
    }

    /** 成功但无数据（跳过）。 */
    public static AnalyzeResult skipped() {
        return new AnalyzeResult(SUCCESS | SKIPPED, null);
    }

    /** 成功但无数据，带说明。 */
    public static AnalyzeResult skipped(String info) {
        return new AnalyzeResult(SUCCESS | SKIPPED, info);
    }

    /** 发生错误。 */
    public static AnalyzeResult error(String info) {
        return new AnalyzeResult(ERROR, info);
    }

    /** 部分成功但有错误。 */
    public static AnalyzeResult partial(String info) {
        return new AnalyzeResult(SUCCESS | FOUND | ERROR, info);
    }

    // ==================== 查询方法 ====================

    public boolean isSuccess()  { return (status & SUCCESS) != 0; }
    public boolean isFound()    { return (status & FOUND)   != 0; }
    public boolean isError()    { return (status & ERROR)   != 0; }
    public boolean isSkipped()  { return (status & SKIPPED) != 0; }

    public int getStatus()      { return status; }
    public String getInfo()     { return info; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("AnalyzeResult{status=0x");
        sb.append(Integer.toHexString(status));
        if (info != null) sb.append(", info='").append(info).append("'");
        return sb.append('}').toString();
    }
}

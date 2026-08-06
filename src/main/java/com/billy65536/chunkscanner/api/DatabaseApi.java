package com.billy65536.chunkscanner.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.Identifier;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.IChunkDb;
import com.billy65536.chunkscanner.core.db.DbExportUtil;
import com.billy65536.chunkscanner.core.db.DbFileUtil;
import com.billy65536.chunkscanner.core.db.DbPackage;
import com.billy65536.chunkscanner.core.db.DbValidationResult;
import com.billy65536.chunkscanner.screen.DatabaseScreen;

/**
 * 数据库公共 API。
 *
 * <p>覆盖四类能力：</p>
 * <ol>
 *   <li><b>查询与列表</b> —— 列出已有数据库、读取轻量元数据、定位文件路径</li>
 *   <li><b>加载</b> —— 通过工厂打开数据库实例，或从导出包还原</li>
 *   <li><b>GUI</b> —— 打开数据库浏览器界面</li>
 *   <li><b>导出</b> —— 导出为 ZIP 归档或 TSV 文本</li>
 * </ol>
 *
 * <p><b>路径约定</b>：数据库文件按游戏上下文分目录存放。
 * {@link #dbRoot()} 是不区分上下文的总根目录（列表查询使用），
 * {@link #dbDir()} 是当前所在服务器/存档对应的目录（新建数据库使用）。</p>
 *
 * <p><b>线程约束</b>：{@link #openGui} 系列方法会自动切换到客户端主线程，
 * 可在任意线程调用。其余方法为纯文件/IO 操作，建议避开主线程以免卡顿。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 列出所有数据库
 * for (DbFileUtil.FileMeta meta : DatabaseApi.listDatabases()) {
 *     System.out.println(meta.scanId() + " -> " + meta.analyzerId());
 * }
 *
 * // 打开某个数据库并导出（记得关闭）
 * IChunkDb db = DatabaseApi.openDatabase("my-scan");
 * if (db != null) {
 *     try {
 *         Path zip = DatabaseApi.exportZip(db, null);
 *     } finally {
 *         db.close();
 *     }
 * }
 *
 * // 或直接按 id 导出，内部自行管理生命周期
 * Path zip = DatabaseApi.exportZip("my-scan", null);
 *
 * // 打开 GUI
 * DatabaseApi.openGui("my-scan");
 * }</pre>
 *
 * @see RegistryApi
 * @see NavigationApi
 */
public final class DatabaseApi {

    private DatabaseApi() {}

    // ==================== 目录 ====================

    /**
     * 所有数据库文件的总根目录（{@code .minecraft/chunkscanner/}）。
     *
     * <p>不区分服务器/存档上下文，列表查询以此为起点递归搜索。</p>
     */
    public static Path dbRoot() {
        return ChunkScannerMod.getDbRoot();
    }

    /**
     * 当前游戏上下文对应的数据库目录。
     *
     * <p>路径形如 {@code .minecraft/chunkscanner/{contextType}/{contextName}/}，
     * 新建数据库应写入此目录。未连接任何世界时回退到根目录下的默认位置。</p>
     */
    public static Path dbDir() {
        return ChunkScannerMod.getDbDir();
    }

    /** 导出文件的默认存放目录（{@code .minecraft/chunkscanner/export/}）。 */
    public static Path exportDir() {
        return DbExportUtil.getExportDir();
    }

    // ==================== 查询与列表 ====================

    /**
     * 列出所有数据库文件的轻量元数据，按最后修改时间倒序。
     *
     * <p>跨所有服务器/存档上下文递归搜索，子数据库文件不会单独列出。
     * 仅读取文件头部，不加载 KV 数据，可安全用于高频调用。</p>
     *
     * @return 元数据列表；无数据库时返回空列表，不为 {@code null}
     */
    public static List<DbFileUtil.FileMeta> listDatabases() {
        return DbFileUtil.listAllDbFiles();
    }

    /**
     * 列出所有数据库的 scanId，按最后修改时间倒序。
     *
     * @return scanId 列表；无数据库时返回空列表，不为 {@code null}
     */
    public static List<String> listScanIds() {
        return DbFileUtil.listAllScanIds();
    }

    /**
     * 列出由指定分析器创建的数据库。
     *
     * @param analyzerId 分析器 id，{@code null} 时返回空列表
     * @return 匹配的元数据列表（只读）
     */
    public static List<DbFileUtil.FileMeta> listDatabasesByAnalyzer(Identifier analyzerId) {
        if (analyzerId == null) return Collections.emptyList();
        List<DbFileUtil.FileMeta> result = new ArrayList<>();
        for (DbFileUtil.FileMeta meta : DbFileUtil.listAllDbFiles()) {
            if (analyzerId.equals(meta.analyzerId())) {
                result.add(meta);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 按 scanId 读取数据库的轻量元数据。
     *
     * @param scanId 扫描任务 id
     * @return 元数据；不存在时返回 {@code null}
     */
    public static DbFileUtil.FileMeta getMeta(String scanId) {
        if (scanId == null) return null;
        for (DbFileUtil.FileMeta meta : DbFileUtil.listAllDbFiles()) {
            if (scanId.equals(meta.scanId())) {
                return meta;
            }
        }
        return null;
    }

    /**
     * 直接从文件读取轻量元数据。
     *
     * @param file 数据库文件路径
     * @return 元数据；文件非法或不可读时返回
     *         {@link DbFileUtil.FileMeta#EMPTY}（{@code isEmpty()} 为 true）
     */
    public static DbFileUtil.FileMeta readMeta(Path file) {
        return DbFileUtil.readFileMeta(file);
    }

    /** 指定 scanId 的数据库是否存在。 */
    public static boolean exists(String scanId) {
        return getMeta(scanId) != null;
    }

    /**
     * 解析 scanId 对应的数据库文件路径。
     *
     * <p>先在已有文件中查找；找不到时按命名约定在当前上下文目录中推导，
     * 因此返回的路径<b>不保证存在</b>。需要判断存在性请用 {@link #exists(String)}。</p>
     */
    public static Path resolveFilePath(String scanId) {
        return DbFileUtil.resolveFilePath(scanId);
    }

    // ==================== 加载 ====================

    /**
     * 按 scanId 打开数据库（完整模式，立即加载数据）。
     *
     * <p>使用文件元数据中记录的分析器 id 与默认工厂创建实例。
     * 调用方负责在使用完毕后调用 {@link IChunkDb#close()}。</p>
     *
     * @param scanId 扫描任务 id
     * @return 数据库实例；数据库不存在或无可用工厂时返回 {@code null}
     */
    public static IChunkDb openDatabase(String scanId) {
        DbFileUtil.FileMeta meta = getMeta(scanId);
        if (meta == null) {
            ChunkScannerMod.LOGGER.warn("Database '{}' not found", scanId);
            return null;
        }
        Path file = meta.filePath();
        Path dir = (file != null && file.getParent() != null) ? file.getParent() : dbDir();
        return RegistryApi.createDb(meta.scanId(), meta.analyzerId(), dir);
    }

    /**
     * 按 scanId 打开数据库（元数据模式，延迟加载）。
     *
     * <p>返回的实例未加载 KV 数据，需调用 {@link IChunkDb#open()} 后才能读取内容。</p>
     *
     * @return 数据库实例；数据库不存在或无可用工厂时返回 {@code null}
     */
    public static IChunkDb openDatabaseMetadataOnly(String scanId) {
        DbFileUtil.FileMeta meta = getMeta(scanId);
        if (meta == null) {
            ChunkScannerMod.LOGGER.warn("Database '{}' not found", scanId);
            return null;
        }
        Path file = meta.filePath();
        Path dir = (file != null && file.getParent() != null) ? file.getParent() : dbDir();
        return RegistryApi.createDbMetadataOnly(meta.scanId(), meta.analyzerId(), dir);
    }

    /**
     * 删除数据库文件及其所有子数据库文件。
     *
     * @return {@code true} 表示至少删除了一个文件
     * @throws IOException 如果删除失败
     */
    public static boolean deleteDatabase(String scanId) throws IOException {
        return DbFileUtil.deleteDbFile(scanId);
    }

    // ==================== 导出包（ZIP）读取 ====================

    /**
     * 打开一个导出 ZIP 包并解析其元数据（不校验、不解压）。
     *
     * @param zipPath 导出包路径
     * @return 导出包句柄
     * @throws IOException 如果文件不存在、无法读取或 metadata 缺失
     */
    public static DbPackage openPackage(Path zipPath) throws IOException {
        return DbPackage.open(zipPath);
    }

    /**
     * 校验导出包的合法性与完整性。
     *
     * <p>检查 analyzerId / databaseType 是否已注册、主文件是否存在、
     * 各文件 SHA256 是否匹配。</p>
     *
     * @return 校验结果；{@link DbValidationResult#valid()} 为 true 表示可安全加载
     * @throws IOException 如果包无法打开
     */
    public static DbValidationResult validatePackage(Path zipPath) throws IOException {
        return DbPackage.open(zipPath).validate();
    }

    /**
     * 将导出包解压并加载为数据库实例（加载前先校验）。
     *
     * @param zipPath   导出包路径
     * @param targetDir 解压目标目录，文件会写入此处
     * @return 加载出的数据库实例
     * @throws IOException 如果校验失败、解压失败或工厂缺失
     */
    public static IChunkDb loadPackage(Path zipPath, Path targetDir) throws IOException {
        return DbPackage.open(zipPath).load(targetDir);
    }

    /**
     * 将导出包解压并加载为数据库实例，可选跳过校验。
     *
     * @param validateFirst {@code false} 时跳过 SHA256 校验，加载更快但不保证完整性
     * @throws IOException 如果校验失败、解压失败或工厂缺失
     */
    public static IChunkDb loadPackage(Path zipPath, Path targetDir, boolean validateFirst) throws IOException {
        return DbPackage.open(zipPath).load(targetDir, validateFirst);
    }

    // ==================== 导出 ====================

    /**
     * 将数据库导出为 ZIP 归档（含 metadata.json 与所有子数据库文件）。
     *
     * <p>metadata.json 记录 scanId、analyzerId、databaseType 与各文件 SHA256，
     * 使导入方可用 {@link #loadPackage} 完整还原。</p>
     *
     * @param db      已打开的数据库实例
     * @param outFile 输出路径；{@code null} 时自动生成到 {@link #exportDir()}
     * @return 实际写入的文件路径
     * @throws IOException 如果数据库文件缺失或写入失败
     */
    public static Path exportZip(IChunkDb db, Path outFile) throws IOException {
        return DbExportUtil.exportRawZip(db, outFile);
    }

    /**
     * 按 scanId 导出数据库为 ZIP 归档。
     *
     * <p>内部会自行打开并关闭数据库实例。</p>
     *
     * @param outFile 输出路径；{@code null} 时自动生成到 {@link #exportDir()}
     * @return 实际写入的文件路径
     * @throws IOException 如果数据库不存在或写入失败
     */
    public static Path exportZip(String scanId, Path outFile) throws IOException {
        IChunkDb db = openDatabase(scanId);
        if (db == null) {
            throw new IOException("Database not found or no factory available: " + scanId);
        }
        try {
            return DbExportUtil.exportRawZip(db, outFile);
        } finally {
            db.close();
        }
    }

    /**
     * 将数据库全部 KV 条目导出为 TSV 文本（每行：十六进制 key + tab + 十六进制 value）。
     *
     * @param db      已打开的数据库实例
     * @param outFile 输出路径；{@code null} 时自动生成到 {@link #exportDir()}
     * @return 实际写入的文件路径
     * @throws IOException 如果写入失败
     */
    public static Path exportTsv(IChunkDb db, Path outFile) throws IOException {
        return DbExportUtil.exportTsv(db, outFile);
    }

    /**
     * 按 scanId 导出数据库为 TSV 文本。
     *
     * <p>内部会自行打开并关闭数据库实例。</p>
     *
     * @return 实际写入的文件路径
     * @throws IOException 如果数据库不存在或写入失败
     */
    public static Path exportTsv(String scanId, Path outFile) throws IOException {
        IChunkDb db = openDatabase(scanId);
        if (db == null) {
            throw new IOException("Database not found or no factory available: " + scanId);
        }
        try {
            return DbExportUtil.exportTsv(db, outFile);
        } finally {
            db.close();
        }
    }

    /**
     * 生成默认导出文件名：{@code chunkscanner-{analyzerId}-{scanId}-{yyMMddHHmmss}.{ext}}。
     *
     * @param ext 扩展名，不含点号
     */
    public static String buildExportFileName(Identifier analyzerId, String scanId, String ext) {
        return DbExportUtil.buildDefaultFileName(analyzerId, scanId, ext);
    }

    // ==================== GUI ====================

    /**
     * 打开数据库浏览器 GUI，停留在文件列表页。
     *
     * <p>自动切换到客户端主线程，可在任意线程调用。</p>
     */
    public static void openGui() {
        openGui((String) null);
    }

    /**
     * 打开数据库浏览器 GUI 并直接进入指定数据库的内容页。
     *
     * <p>自动切换到客户端主线程，可在任意线程调用。
     * scanId 为 {@code null} 或对应数据库不存在时，停留在文件列表页。</p>
     *
     * @param scanId 要直接打开的数据库 id，{@code null} 表示只打开列表页
     */
    public static void openGui(String scanId) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        client.send(() -> client.setScreen(new DatabaseScreen(scanId)));
    }

    /**
     * 构造数据库浏览器界面但不立即显示。
     *
     * <p>适用于需要自行控制界面栈（如设置父界面、嵌入自定义流程）的场景。
     * 返回的界面须在客户端主线程通过 {@code client.setScreen} 显示。</p>
     *
     * @param scanId 要直接打开的数据库 id，{@code null} 表示打开列表页
     * @return 数据库浏览器界面实例
     */
    public static Screen createGui(String scanId) {
        return new DatabaseScreen(scanId);
    }
}

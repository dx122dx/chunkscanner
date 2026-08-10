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
import com.billy65536.chunkscanner.core.IDbAdaptor;
import com.billy65536.chunkscanner.core.db.DbExportUtil;
import com.billy65536.chunkscanner.core.db.DbImage;
import com.billy65536.chunkscanner.core.db.DbManager;
import com.billy65536.chunkscanner.core.db.DbPackage;
import com.billy65536.infrastructure.util.archive.ValidationResult;
import com.billy65536.chunkscanner.screen.DatabaseScreen;

/**
 * 数据库公共 API。
 *
 * <p>覆盖五类能力：</p>
 * <ol>
 *   <li><b>查询与列表</b> —— 列出已有数据库包、读取轻量摘要、定位包目录</li>
 *   <li><b>加载</b> —— 打开数据库包，或从导出镜像还原</li>
 *   <li><b>GUI</b> —— 打开数据库浏览器界面</li>
 *   <li><b>导出</b> —— 导出为 ZIP 镜像或 TSV 文本</li>
 *   <li><b>复制与删除</b> —— 数据库包的复制、删除操作</li>
 * </ol>
 *
 * <p><b>存储模型</b>：一个数据库是磁盘上的一个 {@link DbPackage} 目录
 * （{@code chunkscanner_<hash>/}），内含 {@code metadata.json}、主库负载与若干子库负载。
 * 包内数据一律通过包声明的适配器（{@link IDbAdaptor}）访问，调用方不接触底层键值存储。</p>
 *
 * <p><b>路径约定</b>：包按游戏上下文分目录存放。
 * {@link #dbRoot()} 是不区分上下文的总根目录（列表查询使用），
 * {@link #dbDir()} 是当前所在服务器/存档对应的目录（新建数据库使用）。</p>
 *
 * <p><b>线程约束</b>：{@link #openGui} 系列方法会自动切换到客户端主线程，
 * 可在任意线程调用。其余方法为纯文件/IO 操作，建议避开主线程以免卡顿。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 列出所有数据库
 * for (DbPackage.Info info : DatabaseApi.listDatabases()) {
 *     System.out.println(info.scanId() + " -> " + info.analyzerId());
 * }
 *
 * // 打开某个数据库包，经适配器读取数据（记得关闭）
 * try (DbPackage pkg = DatabaseApi.openPackage("my-scan")) {
 *     MyAdaptor adaptor = pkg.getAdaptor(MyAdaptor.class);
 *     Path zip = DatabaseApi.exportZip(pkg, null);
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
     * 所有数据库包的总根目录（{@code .minecraft/chunkscanner/}）。
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
     * 新建数据库包应写入此目录。未连接任何世界时回退到根目录下的默认位置。</p>
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
     * 列出所有数据库包的轻量摘要，按最后修改时间倒序。
     *
     * <p>跨所有服务器/存档上下文递归搜索，子数据库不会单独列出。
     * 仅读取各包的 {@code metadata.json}，不加载 KV 数据。</p>
     *
     * <p>首次调用会顺带把遗留的 1.x 扁平文件迁移成包结构。</p>
     *
     * @return 摘要列表；无数据库时返回空列表，不为 {@code null}
     */
    public static List<DbPackage.Info> listDatabases() {
        return DbManager.listAll();
    }

    /**
     * 列出所有数据库的 scanId，按最后修改时间倒序。
     *
     * @return scanId 列表；无数据库时返回空列表，不为 {@code null}
     */
    public static List<String> listScanIds() {
        return DbManager.listAllScanIds();
    }

    /**
     * 列出由指定分析器创建的数据库。
     *
     * @param analyzerId 分析器 id，{@code null} 时返回空列表
     * @return 匹配的摘要列表（只读）
     */
    public static List<DbPackage.Info> listDatabasesByAnalyzer(Identifier analyzerId) {
        if (analyzerId == null) return Collections.emptyList();
        List<DbPackage.Info> result = new ArrayList<>();
        for (DbPackage.Info info : DbManager.listAll()) {
            if (analyzerId.equals(info.analyzerId())) {
                result.add(info);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 按 scanId 读取数据库包的轻量摘要。
     *
     * @param scanId 扫描任务 id
     * @return 摘要；不存在时返回 {@code null}
     */
    public static DbPackage.Info getMeta(String scanId) {
        if (scanId == null) return null;
        for (DbPackage.Info info : DbManager.listAll()) {
            if (scanId.equals(info.scanId())) {
                return info;
            }
        }
        return null;
    }

    /** 指定 scanId 的数据库包是否存在。 */
    public static boolean exists(String scanId) {
        return DbManager.findDir(scanId) != null;
    }

    /**
     * 定位 scanId 对应的数据库包目录。
     *
     * @return 包目录；不存在时返回 {@code null}
     */
    public static Path resolveDir(String scanId) {
        return DbManager.findDir(scanId);
    }

    // ==================== 加载 ====================

    /**
     * 在当前游戏上下文下打开或新建数据库包。
     *
     * <p>包已存在时直接打开（此时 {@code analyzerId} 被忽略，以包内 metadata 为准）。
     * 调用方负责 {@link DbPackage#close()}。</p>
     *
     * <p>新建时的适配器 id 由分析器声明推导（见
     * {@link com.billy65536.chunkscanner.core.IChunkAnalyzer#getAdaptorId()}）。</p>
     *
     * @param scanId     扫描任务 id
     * @param analyzerId 新建时写入 metadata 的分析器 id
     * @throws IOException 如果目录创建或 metadata 写入失败
     */
    public static DbPackage createDatabase(String scanId, Identifier analyzerId) throws IOException {
        return DbManager.openOrCreate(scanId, analyzerId);
    }

    /**
     * 按 scanId 打开已存在的数据库包。
     *
     * <p>数据读写请通过 {@link DbPackage#getAdaptor(Class)} 取得适配器完成。
     * 调用方负责 {@link DbPackage#close()}（推荐 try-with-resources）。</p>
     *
     * @return 数据库包；不存在时返回 {@code null}
     * @throws IOException 如果 metadata 损坏或不可读
     */
    public static DbPackage openPackage(String scanId) throws IOException {
        return DbManager.find(scanId);
    }

    /**
     * 删除整个数据库包（含所有子数据库）。
     *
     * @return {@code true} 表示确实删除了一个包
     * @throws IOException 如果删除失败
     */
    public static boolean deleteDatabase(String scanId) throws IOException {
        return DbManager.deletePackage(scanId);
    }

    /**
     * 将数据库包（含所有子数据库）复制到新的 scanId。
     *
     * <p>复制是「逐库读入 → 以新身份写出」，新包的负载与 metadata 中的 scanId 始终自洽；
     * 原始数据库保持不变。</p>
     *
     * @param srcScanId 源 scanId
     * @param dstScanId 目标 scanId
     * @return 新数据库包的轻量摘要
     * @throws IOException 若源不存在、目标已存在或复制失败
     */
    public static DbPackage.Info copyDatabase(String srcScanId, String dstScanId) throws IOException {
        DbPackage src = DbManager.find(srcScanId);
        if (src == null) {
            throw new IOException("Source database not found: " + srcScanId);
        }
        try (src) {
            Path parent = src.getDir().getParent() != null ? src.getDir().getParent() : dbDir();
            try (DbPackage dst = src.copyTo(parent, dstScanId)) {
                return dst.toInfo();
            }
        }
    }

    // ==================== 导出镜像（ZIP）读取 ====================

    /**
     * 打开一个导出镜像并解析其元数据（不校验、不解压）。
     *
     * @param zipPath 镜像路径
     * @return 镜像句柄
     * @throws IOException 如果文件不存在、无法读取或 metadata 缺失
     */
    public static DbImage openImage(Path zipPath) throws IOException {
        return DbImage.open(zipPath);
    }

    /**
     * 校验导出镜像的合法性与完整性。
     *
     * <p>检查 analyzerId / database.type 是否已注册、主文件是否存在、
     * 各文件 SHA-256 是否匹配。</p>
     *
     * @return 校验结果；{@link ValidationResult#valid()} 为 true 表示可安全加载
     * @throws IOException 如果镜像无法打开
     */
    public static ValidationResult validateImage(Path zipPath) throws IOException {
        return DbImage.open(zipPath).validate();
    }

    /**
     * 将导出镜像还原为数据库包（还原前先校验）。
     *
     * @param zipPath   镜像路径
     * @param parentDir 还原目标的父目录，包会落在 {@code parentDir/chunkscanner_<hash>/}
     * @return 还原出的数据库包（调用方负责关闭）
     * @throws IOException 如果校验失败、解压失败或工厂缺失
     */
    public static DbPackage loadImage(Path zipPath, Path parentDir) throws IOException {
        return DbImage.open(zipPath).load(parentDir);
    }

    /**
     * 将导出镜像还原为数据库包，可选跳过校验。
     *
     * @param validateFirst {@code false} 时跳过 SHA-256 校验，加载更快但不保证完整性
     * @throws IOException 如果校验失败、解压失败或工厂缺失
     */
    public static DbPackage loadImage(Path zipPath, Path parentDir, boolean validateFirst) throws IOException {
        return DbImage.open(zipPath).load(parentDir, validateFirst);
    }

    // ==================== 导出 ====================

    /**
     * 将数据库包导出为 ZIP 镜像（含 metadata.json 与所有子数据库负载）。
     *
     * <p>metadata.json 在包元数据基础上追加 {@code export} 段，记录导出时间与
     * 各文件 SHA-256，使导入方可用 {@link #loadImage} 完整还原。</p>
     *
     * @param pkg     已打开的数据库包
     * @param outFile 输出路径；{@code null} 时自动生成到 {@link #exportDir()}
     * @return 实际写入的文件路径
     * @throws IOException 如果包内无负载或写入失败
     */
    public static Path exportZip(DbPackage pkg, Path outFile) throws IOException {
        return DbExportUtil.exportRawZip(pkg, outFile);
    }

    /**
     * 按 scanId 导出数据库为 ZIP 镜像。
     *
     * <p>内部会自行打开并关闭数据库包。</p>
     *
     * @param outFile 输出路径；{@code null} 时自动生成到 {@link #exportDir()}
     * @return 实际写入的文件路径
     * @throws IOException 如果数据库不存在或写入失败
     */
    public static Path exportZip(String scanId, Path outFile) throws IOException {
        DbPackage pkg = DbManager.find(scanId);
        if (pkg == null) {
            throw new IOException("Database not found: " + scanId);
        }
        try (pkg) {
            return DbExportUtil.exportRawZip(pkg, outFile);
        }
    }

    /**
     * 将数据库包主库的全部 KV 条目导出为 TSV 文本（每行：十六进制 key + tab + 十六进制 value）。
     *
     * @param pkg     已打开的数据库包
     * @param outFile 输出路径；{@code null} 时自动生成到 {@link #exportDir()}
     * @return 实际写入的文件路径
     * @throws IOException 如果写入失败
     */
    public static Path exportTsv(DbPackage pkg, Path outFile) throws IOException {
        return DbExportUtil.exportTsv(pkg, outFile);
    }

    /**
     * 按 scanId 导出数据库主库为 TSV 文本。
     *
     * <p>内部会自行打开并关闭数据库包。</p>
     *
     * @return 实际写入的文件路径
     * @throws IOException 如果数据库不存在或写入失败
     */
    public static Path exportTsv(String scanId, Path outFile) throws IOException {
        DbPackage pkg = DbManager.find(scanId);
        if (pkg == null) {
            throw new IOException("Database not found: " + scanId);
        }
        try (pkg) {
            return DbExportUtil.exportTsv(pkg, outFile);
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

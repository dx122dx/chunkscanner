package com.billy65536.chunkscanner.core.db;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.config.TaskConfig;
import com.billy65536.chunkscanner.core.AnalyzerRegistry;
import com.billy65536.chunkscanner.core.IChunkDb;

import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 数据库包的<b>目录级</b>管理者。
 *
 * <p>与 {@link DbPackage} 的分工：</p>
 * <ul>
 *   <li>{@code DbManager} —— 管理磁盘上「有哪些包」：递归搜索、列表、定位、
 *       打开或新建、删除，以及 1.x 遗留扁平文件的自动迁移；</li>
 *   <li>{@link DbPackage} —— 管理单个包「内部有什么」：元数据、负载文件、
 *       适配器与生命周期。</li>
 * </ul>
 *
 * <p>新建包时的 {@code adaptorId} 由分析器声明
 * （{@link AnalyzerRegistry#getAdaptorId(Identifier)}）推导，调用方无需关心。</p>
 */
public final class DbManager {

    /** 递归搜索包目录的最大深度：{@code root/<type>/<context>/<package>}。 */
    private static final int MAX_SCAN_DEPTH = 4;

    private DbManager() {}

    // ==================== 列表与查找 ====================

    /**
     * 列出数据库根目录下所有包的摘要，按最后修改时间倒序。
     *
     * <p>扫描前会顺带把遗留的 1.x 扁平文件迁移成新的包目录结构。</p>
     */
    public static List<DbPackage.Info> listAll() {
        List<DbPackage.Info> out = new ArrayList<>();
        for (Path packageDir : scanPackageDirs()) {
            DbPackage.Info info = readInfo(packageDir);
            if (!info.isEmpty()) out.add(info);
        }
        out.sort(Comparator.comparingLong(DbPackage.Info::lastModified).reversed());
        return out;
    }

    /** 列出数据库根目录下所有包的 scanId，按最后修改时间倒序。 */
    public static List<String> listAllScanIds() {
        return listAll().stream().map(DbPackage.Info::scanId).toList();
    }

    /** 跨上下文定位包目录；找不到返回 {@code null}。 */
    public static Path findDir(String scanId) {
        if (scanId == null || scanId.isEmpty()) return null;
        Path direct = DbPackage.dirFor(ChunkScannerMod.getDbDir(), scanId);
        if (Files.isRegularFile(direct.resolve(DbPackage.METADATA_FILE))) return direct;

        String stem = DbFileUtil.safeFilenameStem(scanId);
        for (Path packageDir : scanPackageDirs()) {
            if (packageDir.getFileName().toString().equals(stem)) return packageDir;
        }
        return null;
    }

    /** 跨上下文打开已存在的包；找不到返回 {@code null}。调用方负责关闭。 */
    public static DbPackage find(String scanId) throws IOException {
        Path packageDir = findDir(scanId);
        return packageDir == null ? null : DbPackage.open(packageDir);
    }

    // ==================== 打开或新建 ====================

    /** 在当前游戏上下文目录下打开或创建包。 */
    public static DbPackage openOrCreate(String scanId, Identifier analyzerId) throws IOException {
        return openOrCreate(ChunkScannerMod.getDbDir(), scanId, analyzerId);
    }

    /**
     * 在指定父目录下打开或创建包。
     *
     * <p>已存在时直接打开（{@code analyzerId} 被忽略，以包内 metadata 为准）；
     * 新建时 {@code adaptorId} 由分析器声明推导。</p>
     */
    public static DbPackage openOrCreate(Path parentDir, String scanId, Identifier analyzerId) throws IOException {
        Path target = DbPackage.dirFor(parentDir, scanId);
        if (Files.isRegularFile(target.resolve(DbPackage.METADATA_FILE))) {
            return DbPackage.open(target);
        }
        return DbPackage.create(parentDir, scanId, analyzerId, AnalyzerRegistry.getAdaptorId(analyzerId));
    }

    // ==================== 删除 ====================

    /** 删除指定 scanId 对应的整个包目录。 */
    public static boolean deletePackage(String scanId) throws IOException {
        Path packageDir = findDir(scanId);
        if (packageDir == null) return false;
        DbPackage.deleteRecursively(packageDir);
        return true;
    }

    // ==================== 目录扫描 ====================

    /** 递归搜索根目录下所有含 metadata.json 的包目录（搜索前先迁移遗留数据）。 */
    private static List<Path> scanPackageDirs() {
        Path root = ChunkScannerMod.getDbRoot();
        if (!Files.isDirectory(root)) return List.of();
        migrateLegacyTree(root);

        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, MAX_SCAN_DEPTH)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(DbFileUtil.STEM_PREFIX))
                    .filter(p -> Files.isRegularFile(p.resolve(DbPackage.METADATA_FILE)))
                    .forEach(dirs::add);
        } catch (IOException | UncheckedIOException e) {
            ChunkScannerMod.LOGGER.warn("Failed to scan database root {}: {}", root, e.toString());
        }
        return dirs;
    }

    /** 读取单个包的摘要；不可读时返回 {@link DbPackage.Info#EMPTY}。 */
    private static DbPackage.Info readInfo(Path packageDir) {
        try (DbPackage pkg = DbPackage.open(packageDir)) {
            return pkg.toInfo();
        } catch (IOException | RuntimeException e) {
            ChunkScannerMod.LOGGER.warn("Skipped unreadable database package {}: {}", packageDir, e.toString());
            return DbPackage.Info.EMPTY;
        }
    }

    // ==================== 遗留格式自动迁移 ====================

    /**
     * 把上下文目录下 1.x 的扁平文件迁移成新的包目录结构。
     *
     * <p>旧命名：{@code chunkscanner_<hash>.<analyzer>.<ext>}，
     * 子库为 {@code chunkscanner_<hash>.<analyzer>.sub_<n>.<ext>}。
     * 迁移后主库变为 {@code chunkscanner_<hash>/main.<ext>}，
     * 子库编号 1 映射为 {@code enhancement}，其余映射为 {@code sub_<n>}。</p>
     *
     * <p>迁移失败只记录日志，不阻塞列表读取。</p>
     */
    private static void migrateLegacyTree(Path root) {
        List<Path> contextDirs = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, MAX_SCAN_DEPTH - 1)) {
            stream.filter(Files::isDirectory).forEach(contextDirs::add);
        } catch (IOException e) {
            ChunkScannerMod.LOGGER.warn("Failed to scan for legacy databases under {}: {}", root, e.toString());
            return;
        }
        for (Path contextDir : contextDirs) {
            migrateLegacyDir(contextDir);
        }
    }

    private static void migrateLegacyDir(Path contextDir) {
        List<Path> legacyMains = new ArrayList<>();
        try (Stream<Path> stream = Files.list(contextDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(DbFileUtil.STEM_PREFIX)
                                && !name.contains(".sub_")
                                && !name.endsWith(".tmp");
                    })
                    .forEach(legacyMains::add);
        } catch (IOException e) {
            ChunkScannerMod.LOGGER.warn("Failed to list {}: {}", contextDir, e.toString());
            return;
        }

        for (Path mainFile : legacyMains) {
            try {
                migrateLegacyPackage(contextDir, mainFile);
            } catch (IOException | RuntimeException e) {
                ChunkScannerMod.LOGGER.error("Failed to migrate legacy database {}: {}", mainFile, e.toString());
            }
        }
    }

    private static void migrateLegacyPackage(Path contextDir, Path mainFile) throws IOException {
        DbFileUtil.LegacyHeader header = DbFileUtil.readLegacyHeader(mainFile);
        if (header.isEmpty()) return;

        String fileName = mainFile.getFileName().toString();
        int extDot = fileName.lastIndexOf('.');
        String ext = extDot >= 0 ? fileName.substring(extDot + 1) : "bin";
        // 旧文件名形如 chunkscanner_<hash>.<analyzer>.<ext>，去掉扩展名后即子库文件的公共前缀
        String legacyPrefix = extDot >= 0 ? fileName.substring(0, extDot) : fileName;

        Path packageDir = DbPackage.dirFor(contextDir, header.scanId());
        if (Files.exists(packageDir)) {
            ChunkScannerMod.LOGGER.warn("Skipped legacy migration, target already exists: {}", packageDir);
            return;
        }
        Files.createDirectories(packageDir);

        DbPackage pkg = DbPackage.forMigration(packageDir, header.scanId(), header.analyzerId(),
                AnalyzerRegistry.getAdaptorId(header.analyzerId()),
                TaskConfig.fromJson(header.taskConfigJson()));

        Identifier type = defaultFactoryId();
        Files.move(mainFile, packageDir.resolve(DbPackage.MAIN_ID + "." + ext));
        pkg.registerNode(DbPackage.MAIN_ID, DbPackage.MAIN_ID + "." + ext, type, header.version());

        for (Map.Entry<String, Path> sub : findLegacySubs(contextDir, legacyPrefix, ext).entrySet()) {
            String subId = sub.getKey();
            Files.move(sub.getValue(), packageDir.resolve(subId + "." + ext));
            pkg.registerNode(subId, subId + "." + ext, type, header.version());
        }

        pkg.saveMetadata();
        pkg.close();
        ChunkScannerMod.LOGGER.info("Migrated legacy database {} → {}", fileName, packageDir.getFileName());
    }

    /** 找出属于同一个旧包的子库文件，返回「新 StringId → 旧文件」。 */
    private static Map<String, Path> findLegacySubs(Path contextDir, String legacyPrefix, String ext) {
        Map<String, Path> found = new LinkedHashMap<>();
        String prefix = legacyPrefix + ".sub_";
        String suffix = "." + ext;
        try (Stream<Path> stream = Files.list(contextDir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                if (!name.startsWith(prefix) || !name.endsWith(suffix)) return;
                String num = name.substring(prefix.length(), name.length() - suffix.length());
                found.put(legacySubId(num), p);
            });
        } catch (IOException e) {
            ChunkScannerMod.LOGGER.warn("Failed to list legacy sub-databases in {}: {}", contextDir, e.toString());
        }
        return found;
    }

    /** 旧的数字子库编号 → 新的 StringId。1 号历来用于聊天增强数据。 */
    private static String legacySubId(String legacyNumber) {
        return "1".equals(legacyNumber) ? "enhancement" : "sub_" + legacyNumber;
    }

    private static Identifier defaultFactoryId() {
        IChunkDb.IFactory factory = IChunkDb.FactoryRegistry.getDefault();
        return factory != null ? factory.getId() : ChunkScannerMod.ID_UNKNOWN;
    }
}

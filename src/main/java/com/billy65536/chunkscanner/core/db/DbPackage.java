package com.billy65536.chunkscanner.core.db;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.config.TaskConfig;
import com.billy65536.chunkscanner.core.IChunkDb;
import com.billy65536.chunkscanner.core.IDbAdaptor;
import com.billy65536.chunkscanner.core.AnalyzerRegistry;
import com.billy65536.infrastructure.core.archive.ArchiveIO;
import com.billy65536.infrastructure.core.io.AtomicFiles;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.util.Identifier;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

/**
 * 一个磁盘上的数据库包，是文件与元信息的<b>唯一</b>管理者。
 *
 * <p>目录布局：</p>
 * <pre>
 * chunkscanner_&lt;hash&gt;/
 * ├── metadata.json   元信息：scanId / analyzerId / adaptorId / taskConfig / 主库与子库清单
 * ├── main.bin        主数据库负载
 * └── &lt;subId&gt;.bin     子数据库负载（subId 为字符串标识）
 * </pre>
 *
 * <p>职责划分：本类负责目录、文件名、元数据、原子写与并发；
 * {@link IChunkDb} 只负责把自己的负载读进内存 / 写出到通道，
 * 通过 {@link DbStorage} 交互，全程不接触任何路径。数据库包所承载的适配器
 * （{@link IDbAdaptor}）由 {@link #getAdaptor()} 按 {@code adaptorId} 创建，
 * 分析器与消费端一律通过适配器访问数据，从不直接接触 {@link IChunkDb}。</p>
 *
 * <p>写时安全：任何一次负载写入都走「写临时文件 → {@code force(true)} → 原子改名」，
 * 元数据写入同理，保证崩溃时磁盘上的旧内容仍然完好。</p>
 *
 * <p>目录级管理（列表、查找、删除、遗留迁移）由 {@link DbManager} 负责，
 * 本类只聚焦于单个包的生命周期。</p>
 */
public final class DbPackage implements AutoCloseable {

    /** 包内元数据文件名。 */
    public static final String METADATA_FILE = "metadata.json";

    /** 主数据库的保留节点 ID。 */
    public static final String MAIN_ID = "main";

    /** 子数据库 ID 的合法字符集，同时也是文件名安全字符集。 */
    private static final Pattern SUB_ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]*");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path dir;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    /** 节点清单：MAIN_ID + 各子库 ID → 文件记录。 */
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    /** 已实例化的数据库缓存。 */
    private final Map<String, IChunkDb> dbs = new LinkedHashMap<>();

    private final String scanId;
    private final Identifier analyzerId;
    private final Identifier adaptorId;
    private TaskConfig taskConfig;
    private volatile boolean closed;

    /** 已创建的适配器实例（按包唯一）。 */
    private IDbAdaptor adaptor;

    private DbPackage(Path dir, String scanId, Identifier analyzerId, Identifier adaptorId) {
        this.dir = dir;
        this.scanId = scanId;
        this.analyzerId = analyzerId != null ? analyzerId : ChunkScannerMod.ID_UNKNOWN;
        this.adaptorId = adaptorId != null ? adaptorId : ChunkScannerMod.id("raw");
    }

    // ==================== 静态入口（包级创建/打开） ====================

    /**
     * 新建一个空包并落盘 metadata.json。
     *
     * @param adaptorId 本包使用的适配器 ID（记录进 metadata，供视图/适配器查找）
     * @throws IOException 目标目录已存在同名包时
     */
    public static DbPackage create(Path parentDir, String scanId, Identifier analyzerId, Identifier adaptorId) throws IOException {
        Objects.requireNonNull(parentDir, "parentDir");
        if (scanId == null || scanId.isEmpty()) {
            throw new IOException("scanId must not be empty");
        }
        Path target = dirFor(parentDir, scanId);
        if (Files.isRegularFile(target.resolve(METADATA_FILE))) {
            throw new IOException("Database package already exists: " + target);
        }
        Files.createDirectories(target);
        DbPackage pkg = new DbPackage(target, scanId,
                analyzerId != null ? analyzerId : ChunkScannerMod.ID_UNKNOWN,
                adaptorId != null ? adaptorId : ChunkScannerMod.id("raw"));
        pkg.saveMetadata();
        return pkg;
    }

    /** 由 scanId 推导包目录。 */
    public static Path dirFor(Path parentDir, String scanId) {
        return parentDir.resolve(DbFileUtil.safeFilenameStem(scanId));
    }

    /**
     * 构造一个尚未落盘的包实例，仅供 {@link DbManager} 迁移遗留数据使用。
     *
     * <p>调用方需自行 {@link #registerNode} 登记已就位的负载文件，再 {@link #saveMetadata()}。</p>
     */
    static DbPackage forMigration(Path packageDir, String scanId, Identifier analyzerId,
                                  Identifier adaptorId, TaskConfig taskConfig) {
        DbPackage pkg = new DbPackage(packageDir, scanId, analyzerId, adaptorId);
        pkg.taskConfig = taskConfig;
        return pkg;
    }

    /** 登记一个已存在于包目录内的负载文件，仅供 {@link DbManager} 迁移遗留数据使用。 */
    void registerNode(String nodeId, String fileName, Identifier type, int version) {
        nodes.put(nodeId, new Node(fileName, type, version));
    }

    /** 打开一个已存在的包目录。 */
    public static DbPackage open(Path packageDir) throws IOException {
        Objects.requireNonNull(packageDir, "packageDir");
        Path metaFile = packageDir.resolve(METADATA_FILE);
        if (!Files.isRegularFile(metaFile)) {
            throw new NoSuchFileException(metaFile.toString());
        }
        JsonObject root;
        try (BufferedReader reader = Files.newBufferedReader(metaFile, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IOException("Malformed metadata (not an object): " + metaFile);
            }
            root = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Malformed metadata: " + metaFile, e);
        }

        String scanId = optString(root, "scanId");
        if (scanId == null || scanId.isEmpty()) {
            throw new IOException("Metadata has no scanId: " + metaFile);
        }
        Identifier analyzerId = parseId(optString(root, "analyzerId"));
        Identifier adaptorId = parseId(optString(root, "adaptorId"));
        // 旧包无 adaptorId：从 analyzerId 推导，未注册则回退 raw
        if (adaptorId == ChunkScannerMod.ID_UNKNOWN) {
            adaptorId = AnalyzerRegistry.getAdaptorId(analyzerId);
        }

        DbPackage pkg = new DbPackage(packageDir, scanId, analyzerId, adaptorId);
        String rawConfig = optString(root, "taskConfig");
        pkg.taskConfig = rawConfig != null && !rawConfig.isBlank() ? TaskConfig.parse(rawConfig) : null;

        JsonObject dbNode = root.has("database") && root.get("database").isJsonObject()
                ? root.getAsJsonObject("database") : null;
        if (dbNode != null) {
            pkg.nodes.put(MAIN_ID, Node.fromJson(dbNode, MAIN_ID));
            if (dbNode.has("subsidiaries") && dbNode.get("subsidiaries").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : dbNode.getAsJsonObject("subsidiaries").entrySet()) {
                    if (!e.getValue().isJsonObject()) continue;
                    pkg.nodes.put(e.getKey(), Node.fromJson(e.getValue().getAsJsonObject(), e.getKey()));
                }
            }
        }
        return pkg;
    }

    // ==================== 元信息 ====================

    /** 包目录。 */
    public Path getDir() {
        return dir;
    }

    /** 扫描任务 ID。 */
    public String getScanId() {
        return scanId;
    }

    /** 创建此包的分析器 ID。 */
    public Identifier getAnalyzerId() {
        return analyzerId;
    }

    /** 本包使用的适配器 ID（决定创建哪个 {@link IDbAdaptor}）。 */
    public Identifier getAdaptorId() {
        return adaptorId;
    }

    /** 主数据库的实现类型（工厂 ID）；未知时返回 {@code null}。 */
    public Identifier getDbType() {
        Node main = nodes.get(MAIN_ID);
        return main == null ? null : main.type;
    }

    /** 任务配置；未设置返回 {@code null}。 */
    public TaskConfig getTaskConfig() {
        return taskConfig;
    }

    /** 写入任务配置并立即持久化到 metadata.json。 */
    public void setTaskConfig(TaskConfig config) {
        lock.writeLock().lock();
        try {
            this.taskConfig = config;
            saveMetadata();
        } catch (IOException e) {
            ChunkScannerMod.LOGGER.error("[scan:{}] Failed to persist task config: {}", scanId, e.toString());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 已登记的子数据库 ID 集合。 */
    public Set<String> getSubsidiaryIds() {
        Set<String> ids = new LinkedHashSet<>(nodes.keySet());
        ids.remove(MAIN_ID);
        return ids;
    }

    /** 是否已登记指定子数据库。 */
    public boolean hasSub(String subId) {
        return nodes.containsKey(subId) && !MAIN_ID.equals(subId);
    }

    /** 包内所有文件的总字节数。 */
    public long getStorageSize() {
        long total = 0;
        try (var stream = Files.list(dir)) {
            total = stream.filter(Files::isRegularFile).mapToLong(DbPackage::sizeOf).sum();
        } catch (IOException | RuntimeException e) {
            ChunkScannerMod.LOGGER.debug("Failed to size package {}: {}", dir, e.toString());
        }
        return total;
    }

    /** 本包的轻量摘要（不持有任何打开的资源）。 */
    public Info toInfo() {
        return new Info(scanId, analyzerId, adaptorId, getDbType(),
                getStorageSize(), getLastModifiedTime(), dir);
    }

    /** 包内负载文件的最新修改时间戳；无负载时回退到 metadata.json。 */
    public long getLastModifiedTime() {
        long newest = 0;
        try (var stream = Files.list(dir)) {
            newest = stream.filter(Files::isRegularFile)
                    .mapToLong(p -> p.toFile().lastModified())
                    .max().orElse(0);
        } catch (IOException | RuntimeException e) {
            ChunkScannerMod.LOGGER.debug("Failed to stat package {}: {}", dir, e.toString());
        }
        return newest;
    }

    // ==================== 适配器访问 ====================

    /**
     * 获取本包的适配器（按 {@link #getAdaptorId()} 创建并缓存）。
     *
     * <p>若包声明的 adaptorId 未注册任何工厂，回退到内置的 {@code chunkscanner:raw} 适配器。</p>
     */
    public IDbAdaptor getAdaptor() {
        if (closed) throw new IllegalStateException("DbPackage already closed: " + dir);
        if (adaptor != null) return adaptor;
        IDbAdaptor.IFactory f = IDbAdaptor.FactoryRegistry.get(adaptorId);
        if (f == null) f = IDbAdaptor.FactoryRegistry.get(ChunkScannerMod.id("raw"));
        if (f == null) {
            throw new IllegalStateException("No IDbAdaptor factory registered (not even raw)");
        }
        adaptor = f.create(this);
        return adaptor;
    }

    /**
     * 以强类型获取本包的适配器。
     *
     * @throws IllegalStateException 当本包适配器类型与 {@code type} 不符时
     */
    public <T extends IDbAdaptor> T getAdaptor(Class<T> type) {
        IDbAdaptor a = getAdaptor();
        if (!type.isInstance(a)) {
            throw new IllegalStateException("Package " + scanId + " uses adaptor "
                    + a.getClass().getName() + ", not " + type.getName());
        }
        return type.cast(a);
    }

    // ==================== 数据库访问（仅供适配器使用） ====================

    /** 获取主数据库（立即加载负载）。适配器专用，消费端请走 {@link #getAdaptor()}。 */
    public IChunkDb main() {
        return obtain(MAIN_ID, null, true);
    }

    /**
     * 获取或创建子数据库（立即加载负载）。
     *
     * <p>子库拥有独立的字符串池与 KV 存储，用于存放不应随主数据一起被清除的附加数据。
     * 注意父子库的字符串 ID 不通用，{@code intern}/{@code lookup} 必须在同一实例内配对使用。</p>
     *
     * @param subId 子库标识，须匹配 {@code [a-z0-9][a-z0-9_-]*} 且不为 {@value #MAIN_ID}
     */
    public IChunkDb sub(String subId) {
        return obtain(requireValidSubId(subId), null, true);
    }

    /** 主数据库当前的 KV 条目总数（用于进度/状态展示）。 */
    public int size() {
        return main().size();
    }

    /** 某个 chunk 的上次扫描时间戳（毫秒），0 表示从未扫描。 */
    public long getChunkScanTime(String dimensionId, int cx, int cz) {
        return main().getChunkScanTime(dimensionId, cx, cz);
    }

    /** 更新某个 chunk 的扫描时间戳。 */
    public void updateChunkScanTime(String dimensionId, int cx, int cz, long timestamp) {
        main().updateChunkScanTime(dimensionId, cx, cz, timestamp);
    }

    private IChunkDb obtain(String nodeId, Identifier preferredType, boolean load) {
        if (closed) throw new IllegalStateException("DbPackage already closed: " + dir);
        synchronized (dbs) {
            IChunkDb cached = dbs.get(nodeId);
            if (cached != null) {
                if (load && !cached.isOpen()) cached.open();
                return cached;
            }

            Node node = nodes.get(nodeId);
            IChunkDb.IFactory factory = resolveFactory(node, preferredType);
            if (node == null) {
                node = new Node(nodeId + "." + factory.getExt(), factory.getId(), 0);
                nodes.put(nodeId, node);
                saveMetadataQuietly();
            }

            DbStorage storage = new NodeStorage(nodeId);
            IChunkDb db = factory.create(storage);
            if (load) {
                db.open();
            }
            dbs.put(nodeId, db);
            return db;
        }
    }

    private static IChunkDb.IFactory resolveFactory(Node node, Identifier preferredType) {
        Identifier wanted = node != null ? node.type : preferredType;
        IChunkDb.IFactory factory = wanted != null ? IChunkDb.FactoryRegistry.get(wanted) : null;
        if (factory == null) {
            factory = IChunkDb.FactoryRegistry.getDefault();
        }
        if (factory == null) {
            throw new IllegalStateException("No IChunkDb factory registered");
        }
        return factory;
    }

    // ==================== 生命周期 ====================

    /** 刷写所有已实例化的数据库。 */
    public void flush() {
        List<IChunkDb> snapshot;
        synchronized (dbs) {
            snapshot = new ArrayList<>(dbs.values());
        }
        for (IChunkDb db : snapshot) {
            db.flush();
        }
    }

    /** 刷写并关闭所有数据库，之后本实例不可再用。 */
    @Override
    public void close() {
        List<IChunkDb> snapshot;
        synchronized (dbs) {
            if (closed) return;
            closed = true;
            snapshot = new ArrayList<>(dbs.values());
            dbs.clear();
        }
        for (IChunkDb db : snapshot) {
            db.close();
        }
    }

    // ==================== 复制与删除 ====================

    /**
     * 复制为一个新 scanId 的包。
     *
     * <p>不做字节层面的文件拷贝，而是逐个数据库「读入 → 以新身份写出」，
     * 因此新包的负载、文件名与 metadata 中的 scanId 三者始终自洽。</p>
     *
     * @param parentDir  目标父目录
     * @param newScanId  新的扫描 ID
     * @return 新建的包（已持久化，调用方负责 {@link #close()}）
     */
    public DbPackage copyTo(Path parentDir, String newScanId) throws IOException {
        if (newScanId == null || newScanId.isEmpty()) {
            throw new IOException("Target scanId must not be empty");
        }
        if (newScanId.equals(scanId) && dirFor(parentDir, newScanId).equals(dir)) {
            throw new IOException("Cannot copy a database onto itself: " + scanId);
        }
        flush();

        DbPackage dst = create(parentDir, newScanId, analyzerId, adaptorId);
        try {
            dst.taskConfig = taskConfig != null ? taskConfig.copy() : null;
            for (String nodeId : new ArrayList<>(nodes.keySet())) {
                IChunkDb src = obtain(nodeId, null, true);
                Node srcNode = nodes.get(nodeId);
                IChunkDb.IFactory srcFactory = resolveFactory(srcNode, null);
                Node dstNode = new Node(nodeId + "." + extensionOf(srcNode), srcNode.type, srcFactory.getFormatVersion());
                dst.nodes.put(nodeId, dstNode);
                dst.new NodeStorage(nodeId).write(src::writeTo);
                dstNode.version = srcFactory.getFormatVersion();
            }
            dst.saveMetadata();
            return dst;
        } catch (IOException | RuntimeException e) {
            try {
                dst.close();
                deleteRecursively(dst.dir);
            } catch (IOException | RuntimeException cleanupFailure) {
                ChunkScannerMod.LOGGER.warn("Failed to clean up partial copy {}: {}",
                        dst.dir, cleanupFailure.toString());
            }
            throw e;
        }
    }

    /** 关闭并删除整个包目录。 */
    public void delete() throws IOException {
        close();
        deleteRecursively(dir);
    }

    // ==================== 元数据持久化 ====================

    void saveMetadata() throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("scanId", scanId);
        root.addProperty("analyzerId", (analyzerId != null ? analyzerId : ChunkScannerMod.ID_UNKNOWN).toString());
        root.addProperty("adaptorId", adaptorId.toString());
        if (taskConfig != null && !taskConfig.isAllNull()) {
            root.addProperty("taskConfig", taskConfig.toDisplayString());
        }

        Node main = nodes.get(MAIN_ID);
        if (main != null) {
            JsonObject dbJson = main.toJson();
            JsonObject subs = new JsonObject();
            for (Map.Entry<String, Node> e : nodes.entrySet()) {
                if (MAIN_ID.equals(e.getKey())) continue;
                subs.add(e.getKey(), e.getValue().toJson());
            }
            if (subs.size() > 0) dbJson.add("subsidiaries", subs);
            root.add("database", dbJson);
        }

        Files.createDirectories(dir);
        byte[] payload = (GSON.toJson(root) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.writeBytes(dir.resolve(METADATA_FILE), payload);
    }

    private void saveMetadataQuietly() {
        try {
            saveMetadata();
        } catch (IOException e) {
            ChunkScannerMod.LOGGER.error("[scan:{}] Failed to write {}: {}", scanId, METADATA_FILE, e.toString());
        }
    }

    /** 负载写入成功后同步 metadata 中记录的格式版本（由工厂声明）。 */
    private void onNodeWritten(String nodeId) {
        IChunkDb db;
        synchronized (dbs) {
            db = dbs.get(nodeId);
        }
        Node node = nodes.get(nodeId);
        if (db == null || node == null) return;
        int version = resolveFactory(node, null).getFormatVersion();
        if (node.version != version) {
            node.version = version;
            saveMetadataQuietly();
        }
    }

    // ==================== DbStorage 实现 ====================

    /** 把一个节点的负载文件包装成 {@link DbStorage}，对外只暴露 FileChannel。 */
    private final class NodeStorage implements DbStorage {

        private final String nodeId;

        NodeStorage(String nodeId) {
            this.nodeId = nodeId;
        }

        @Override
        public boolean read(ChannelTask task) throws IOException {
            Path file = fileOf(nodeId);
            lock.readLock().lock();
            try {
                if (!Files.isRegularFile(file) || Files.size(file) == 0) return false;
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                    task.accept(channel);
                }
                return true;
            } finally {
                lock.readLock().unlock();
            }
        }

        @Override
        public void write(ChannelTask task) throws IOException {
            Path file = fileOf(nodeId);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            lock.writeLock().lock();
            try {
                Files.createDirectories(dir);
                try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    task.accept(channel);
                    channel.force(true);
                }
                moveAtomically(tmp, file);
                onNodeWritten(nodeId);
            } catch (IOException | RuntimeException e) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 残留 .tmp 不影响正确性，下次写入会 TRUNCATE_EXISTING 覆盖
                }
                throw e;
            } finally {
                lock.writeLock().unlock();
            }
        }

        @Override
        public long size() {
            return sizeOf(fileOf(nodeId));
        }

        @Override
        public long lastModified() {
            Path file = fileOf(nodeId);
            return Files.isRegularFile(file) ? file.toFile().lastModified() : 0;
        }
    }

    private Path fileOf(String nodeId) {
        Node node = nodes.get(nodeId);
        String fileName = node != null ? node.file : nodeId;
        return dir.resolve(fileName);
    }

    // ==================== 内部工具 ====================

    private static String requireValidSubId(String subId) {
        if (subId == null || MAIN_ID.equals(subId) || !SUB_ID_PATTERN.matcher(subId).matches()) {
            throw new IllegalArgumentException("Illegal subsidiary database id: " + subId);
        }
        return subId;
    }

    private static String extensionOf(Node node) {
        int dot = node.file.lastIndexOf('.');
        return dot >= 0 ? node.file.substring(dot + 1) : "bin";
    }

    private static void moveAtomically(Path from, Path to) throws IOException {
        AtomicFiles.moveAtomically(from, to);
    }

    static void deleteRecursively(Path target) throws IOException {
        ArchiveIO.deleteRecursively(target);
    }

    private static long sizeOf(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private static String optString(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static Identifier parseId(String raw) {
        if (raw == null || raw.isEmpty()) return ChunkScannerMod.ID_UNKNOWN;
        Identifier parsed = raw.indexOf(':') >= 0
                ? Identifier.tryParse(raw)
                : ChunkScannerMod.id(raw.toLowerCase(java.util.Locale.ROOT));
        return parsed != null ? parsed : ChunkScannerMod.ID_UNKNOWN;
    }

    // ==================== 辅助类型 ====================

    /** metadata 中的一条数据库记录。 */
    private static final class Node {
        private final String file;
        private final Identifier type;
        private int version;

        Node(String file, Identifier type, int version) {
            this.file = file;
            this.type = type;
            this.version = version;
        }

        static Node fromJson(JsonObject json, String fallbackId) {
            String file = optString(json, "file");
            Identifier type = parseId(optString(json, "type"));
            JsonElement versionEl = json.get("version");
            int version = versionEl != null && versionEl.isJsonPrimitive() ? versionEl.getAsInt() : 0;
            return new Node(file != null ? file : fallbackId + ".bin", type, version);
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("file", file);
            json.addProperty("type", type != null ? type.toString() : ChunkScannerMod.ID_UNKNOWN.toString());
            json.addProperty("version", version);
            return json;
        }
    }

    /**
     * 包的轻量摘要，用于列表展示，不持有任何打开的资源。
     *
     * @param scanId       扫描 ID
     * @param analyzerId   分析器 ID
     * @param adaptorId    适配器 ID
     * @param dbType       主库实现类型
     * @param size         包内文件总字节数
     * @param lastModified 最新修改时间戳
     * @param dir          包目录
     */
    public record Info(String scanId, Identifier analyzerId, Identifier adaptorId, Identifier dbType,
                       long size, long lastModified, Path dir) {

        /** 无法识别时的空值。 */
        public static final Info EMPTY = new Info("", ChunkScannerMod.ID_UNKNOWN,
                ChunkScannerMod.ID_UNKNOWN, ChunkScannerMod.ID_UNKNOWN, 0, 0, null);

        /** 是否为无效摘要。 */
        public boolean isEmpty() {
            return scanId.isEmpty();
        }
    }
}

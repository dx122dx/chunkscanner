package com.billy65536.chunkscanner.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.infrastructure.core.module.ModuleRegistry;
import com.billy65536.infrastructure.security.ConfigLocker;

/**
 * 配置文件数据模型（AutoConfig 驱动）。
 *
 * <p>全局配置仅作为默认值；每个扫描任务持有自己的副本（见 {@link #copy()}），可独立修改。
 *
 * <p>配置按四大分组组织，分组路径同时也是 {@code /cs get|set|reset} 命令的
 * {@code [name]} 点分路径：
 * <ul>
 *   <li>{@code scanner.*} — 扫描参数</li>
 *   <li>{@code integration.xaero.*} — Xaero 路径点联动</li>
 *   <li>{@code integration.baritone.*} — Baritone 导航</li>
 *   <li>{@code components.qshop.*} — QShop 分析器</li>
 * </ul>
 *
 * <p>持久化由 AutoConfig 的 {@code GsonConfigSerializer} 接管，写入
 * {@code config/chunkscanner.json}，JSON 结构与此处的对象层级一一对应。
 *
 * <p>注意：{@code components.qshop} 只是配置分组的<em>命名</em>，本类不引用
 * {@code com.billy65536.chunkscanner.components} 包中的任何类型，不违反分层规则。
 */
@Config(name = "chunkscanner")
public class ChunkScannerConfig implements ConfigData {

    /**
     * 聊天增强信息获取模式。
     * <ul>
     *   <li>{@link #StrictAutomatic} — 时间窗口 + 商品名双重匹配（默认）</li>
     *   <li>{@link #WeakAutomatic} — 仅时间窗口匹配，不检查商品名</li>
     *   <li>{@link #SemiAutomatic} — 从聊天捕获物品，收到消息后自动提交增强，不自动检测点击</li>
     *   <li>{@link #NonAutomatic} — 从聊天捕获物品但不自动增强，由 /cs components qshop commitEnhancement 命令手动提交</li>
     *   <li>{@link #Disabled} — 禁用增强匹配</li>
     * </ul>
     */
    public enum EnhanceMatchMode {
        StrictAutomatic,
        WeakAutomatic,
        SemiAutomatic,
        NonAutomatic,
        Disabled
    }

    /**
     * 聊天消息拦截方式，决定 QuickShop 物品消息通过哪个通道被捕获。
     * <ul>
     *   <li>{@link #SYSTEM_MIXIN} — 仅拦截系统聊天包（{@code ClientboundSystemChatPacket}，通过 Mixin）</li>
     *   <li>{@link #GAME_EVENT} — 仅通过 Fabric {@code ClientReceiveMessageEvents.GAME} 事件</li>
     *   <li>{@link #BOTH} — 双通道同时启用（默认，兼容旧行为）</li>
     * </ul>
     *
     * <p>当服务器配置导致同一消息通过两个通道重复捕获时（如 Semi-Automatic 模式
     * 重复提交增强），可切换为单一通道解决。</p>
     */
    public enum ChatInterceptionMethod {
        SYSTEM_MIXIN,
        GAME_EVENT,
        BOTH
    }

    /**
     * Baritone 风险警告级别。
     * <ul>
     *   <li>{@link #SHOWN} — 每次加入服务器时显示警告（默认）</li>
     *   <li>{@link #HIDDEN} — 不显示警告，Baritone 正常使用</li>
     *   <li>{@link #BARITONE_DISABLED} — 禁用 Baritone 功能，启用路径点回退</li>
     * </ul>
     *
     * <p>注意：修改此选项后需要重启游戏生效。</p>
     */
    public enum BaritoneRiskWarning {
        SHOWN,
        HIDDEN,
        BARITONE_DISABLED
    }

    // ==================== 顶层分组 ====================

    /** 扫描参数分组。命令路径前缀 {@code scanner.}。 */
    @ConfigEntry.Gui.CollapsibleObject
    public Scanner scanner = new Scanner();

    /** 第三方模组联动分组。命令路径前缀 {@code integration.}。 */
    @ConfigEntry.Gui.CollapsibleObject
    public Integration integration = new Integration();

    /** 组件（分析器）分组。命令路径前缀 {@code components.}。 */
    @ConfigEntry.Gui.CollapsibleObject
    public Components components = new Components();

    // ==================== scanner ====================

    /** 扫描参数。 */
    public static class Scanner {

        /** 最小重访间隔（秒）。默认 60。 */
        @ConfigEntry.BoundedDiscrete(min = 0, max = 3600)
        public int minRevisitIntervalSec = 60;

        /** 每 tick 最大 chunk 数。默认 16。 */
        @ConfigEntry.BoundedDiscrete(min = 1, max = 32)
        public int maxTasksPerTick = 16;

        /** 初始每 tick chunk 数。默认 2。 */
        @ConfigEntry.BoundedDiscrete(min = 1, max = 16)
        public int initialTasksPerTick = 2;

        /** 目标 tick 耗时（纳秒）。默认 5ms。 */
        public long targetTickNs = 5_000_000L;

        /** 批量刷写间隔（tick）。默认 100。 */
        @ConfigEntry.BoundedDiscrete(min = 10, max = 1000)
        public int flushIntervalTicks = 100;

        /** 工作线程数。默认 2。 */
        @ConfigEntry.BoundedDiscrete(min = 1, max = 8)
        public int workerThreads = 2;

        /** 扫描视距倍率。默认 1.0。 */
        public double scanRadiusMultiplier = 1.0;

        Scanner copy() {
            Scanner s = new Scanner();
            s.minRevisitIntervalSec = this.minRevisitIntervalSec;
            s.maxTasksPerTick = this.maxTasksPerTick;
            s.initialTasksPerTick = this.initialTasksPerTick;
            s.targetTickNs = this.targetTickNs;
            s.flushIntervalTicks = this.flushIntervalTicks;
            s.workerThreads = this.workerThreads;
            s.scanRadiusMultiplier = this.scanRadiusMultiplier;
            return s;
        }
    }

    // ==================== integration ====================

    /** 第三方模组联动配置。 */
    public static class Integration {

        /** Xaero 小地图/世界地图路径点联动。命令路径前缀 {@code integration.xaero.}。 */
        @ConfigEntry.Gui.CollapsibleObject
        public Xaero xaero = new Xaero();

        /** Baritone 自动寻路联动。命令路径前缀 {@code integration.baritone.}。 */
        @ConfigEntry.Gui.CollapsibleObject
        public Baritone baritone = new Baritone();

        /** Xaero 路径点参数。 */
        public static class Xaero {

            /** 路径点名称。默认 "选中的坐标点"。 */
            public String name = "选中的坐标点";

            /** 路径点缩写/符号。默认 "目标"。 */
            public String initials = "目标";

            /** 路径点所在组（WaypointSet 名称）。默认 "chunkscanner"。 */
            public String group = "chunkscanner";

            Xaero copy() {
                Xaero x = new Xaero();
                x.name = this.name;
                x.initials = this.initials;
                x.group = this.group;
                return x;
            }
        }

        /** Baritone 导航参数。 */
        public static class Baritone {

            /** 是否启用 Baritone 自动寻路（GoalComposite 重排队列）。默认 false。 */
            public boolean autoEnabled = false;

            /** 导航到达判定距离（格）。玩家距目标在此范围内即判定到达。默认 3.0。 */
            public double reachDist = 3.0;

            /** GoalComposite 模式下最多同时打包的导航目标数（防止反射构造过多 GoalBlock）。默认 128。 */
            @ConfigEntry.BoundedDiscrete(min = 1, max = 1024)
            public int compositeLimit = 128;

            /** Baritone 风险警告级别。默认 SHOWN。修改后需重启游戏生效。 */
            public BaritoneRiskWarning riskWarning = BaritoneRiskWarning.SHOWN;

            Baritone copy() {
                Baritone b = new Baritone();
                b.autoEnabled = this.autoEnabled;
                b.reachDist = this.reachDist;
                b.compositeLimit = this.compositeLimit;
                b.riskWarning = this.riskWarning;
                return b;
            }
        }

        Integration copy() {
            Integration i = new Integration();
            i.xaero = this.xaero.copy();
            i.baritone = this.baritone.copy();
            return i;
        }
    }

    // ==================== components ====================

    /** 组件（分析器）配置。 */
    public static class Components {

        /** QShop 分析器配置。命令路径前缀 {@code components.qshop.}。 */
        @ConfigEntry.Gui.CollapsibleObject
        public QShop qshop = new QShop();

        /** QShop 分析器参数。 */
        public static class QShop {

            /**
             * QShop 第二行"出售/收购 + 数量"的正则模式。
             * 必须包含两个捕获组：group(1) = 出售/收购关键词，group(2) = 数量数字。
             */
            public String sellBuyPattern = "^\\s*(出售|收购)\\s+(\\d+)";

            /** QShop 第二行"出售/收购 + 无限"的正则模式。group(1) = 出售/收购关键词。 */
            public String infinitePattern = "^\\s*(出售|收购)\\s+无限";

            /** QShop 第二行"缺货"的正则模式。 */
            public String outOfStockPattern = "^\\s*缺货";

            /** QShop 第二行"空间不足"的正则模式。 */
            public String outOfSpacePattern = "^\\s*空间不足";

            /** QShop 第四行单价的正则模式。group(1) = 价格文本（去除前缀后）。 */
            public String pricePattern = "单价[：:]\\s*(.+)";

            /** 出售关键词，用于匹配 sellBuyPattern/infinitePattern 的 group(1)。 */
            public String sellKeyword = "出售";

            /** 收购关键词，用于匹配 sellBuyPattern/infinitePattern 的 group(1)。 */
            public String buyKeyword = "收购";

            /** 是否启用 QShop 告示牌高亮边框。默认 false。 */
            public boolean highlightEnabled = false;

            /** 高亮范围（chunk 环数，0 表示仅当前 chunk）。默认 1。 */
            @ConfigEntry.BoundedDiscrete(min = 0, max = 8)
            public int highlightRadius = 1;

            /** 高亮颜色渐变时长（毫秒）。增强数据在此时间内从绿色渐变到黄色。默认 86400000（1 天）。 */
            public long highlightGradientMs = 86400_000L;

            /** 聊天增强信息获取模式。默认 StrictAutomatic。 */
            public EnhanceMatchMode enhanceMatchMode = EnhanceMatchMode.StrictAutomatic;

            /** Non-Automatic / Semi-Automatic 模式下缓存的聊天物品过期时间（毫秒）。默认 30000（30 秒）。 */
            public long manualEnhanceItemExpireMs = 30_000L;

            /** 聊天消息拦截方式。默认 BOTH（双通道）。 */
            public ChatInterceptionMethod chatInterceptionMethod = ChatInterceptionMethod.BOTH;

            QShop copy() {
                QShop q = new QShop();
                q.sellBuyPattern = this.sellBuyPattern;
                q.infinitePattern = this.infinitePattern;
                q.outOfStockPattern = this.outOfStockPattern;
                q.outOfSpacePattern = this.outOfSpacePattern;
                q.pricePattern = this.pricePattern;
                q.sellKeyword = this.sellKeyword;
                q.buyKeyword = this.buyKeyword;
                q.highlightEnabled = this.highlightEnabled;
                q.highlightRadius = this.highlightRadius;
                q.highlightGradientMs = this.highlightGradientMs;
                q.enhanceMatchMode = this.enhanceMatchMode;
                q.manualEnhanceItemExpireMs = this.manualEnhanceItemExpireMs;
                q.chatInterceptionMethod = this.chatInterceptionMethod;
                return q;
            }
        }

        Components copy() {
            Components c = new Components();
            c.qshop = this.qshop.copy();
            return c;
        }
    }

    /**
     * AutoConfig 反序列化后钩子：在每次配置从磁盘加载或 Cloth Config GUI 保存后
     * 重新加载时被调用。
     *
     * <p>这是阻止玩家通过 Cloth Config 界面绕过「服务端 opt-in」配置锁定的关键防线。
     * GUI 编辑直接改写内存中的活动配置实例，并在玩家点击完成时由 AutoConfig 内部
     * 调用 {@code holder.save()} 写回磁盘——该路径不经过 {@link ConfigLoader#load()}
     * 内的 {@code ConfigLocker.applyAll()}，因此玩家原本可借此修改被锁项。
     * 本方法在每次反序列化后统一重放锁定值，无论来源（GUI 保存 / 磁盘加载 / 重载），
     * 被锁定的配置项都会被强制重置回服务器策略值（或仅锁定项保持锁定），
     * 使锁定状态在任何修改途径下都不可被绕过。
     *
     * <p>返回空 {@code Optional} 表示校验通过（无向用户展示的错误），锁定重放在此处
     * 静默完成，不阻断保存流程。
     */
    @Override
    public void validatePostLoad() {
        // 重放服务器锁定强制值（防 Cloth Config GUI 保存绕过）
        var module = ModuleRegistry.get(ChunkScannerMod.MOD_ID);
        if (module != null) {
            ConfigLocker.applyAll(module.getConfigDescriptors());
        } else {
            ChunkScannerMod.LOGGER.warn(
                    "Module not registered yet, server config locks were NOT applied during validatePostLoad.");
        }
    }

    /**
     * 创建一份配置深拷贝，供每个扫描任务独立持有。
     *
     * <p>必须逐层深拷贝，否则任务级配置的修改会污染全局配置实例。
     */
    public ChunkScannerConfig copy() {
        ChunkScannerConfig c = new ChunkScannerConfig();
        c.scanner = this.scanner.copy();
        c.integration = this.integration.copy();
        c.components = this.components.copy();
        return c;
    }
}

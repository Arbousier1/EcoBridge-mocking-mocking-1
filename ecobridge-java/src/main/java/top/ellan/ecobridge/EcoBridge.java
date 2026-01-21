package top.ellan.ecobridge;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
// [Refactor] 移除 Listener 接口，事件监听已下放至各组件内部
// import org.bukkit.event.EventHandler;
// import org.bukkit.event.Listener;
// import org.bukkit.event.player.PlayerJoinEvent;
// import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import top.ellan.ecobridge.api.EcoLimitAPI;
import top.ellan.ecobridge.infrastructure.ffi.bridge.NativeBridge;
import top.ellan.ecobridge.infrastructure.cache.HotDataCache;
import top.ellan.ecobridge.integration.platform.command.AdminCommand; 
import top.ellan.ecobridge.integration.platform.command.TransferCommand;
import top.ellan.ecobridge.infrastructure.persistence.database.DatabaseManager;
import top.ellan.ecobridge.integration.platform.hook.EcoPlaceholderExpansion;
import top.ellan.ecobridge.integration.platform.hook.UltimateShopHook;
import top.ellan.ecobridge.integration.platform.asm.EcoShopTransformer; 
import top.ellan.ecobridge.integration.platform.listener.CacheListener;
import top.ellan.ecobridge.integration.platform.listener.CoinsEngineListener;
import top.ellan.ecobridge.integration.platform.listener.CommandHijacker;
import top.ellan.ecobridge.application.service.*;
import top.ellan.ecobridge.infrastructure.persistence.redis.RedisManager;
import top.ellan.ecobridge.infrastructure.persistence.storage.ActivityCollector;
import top.ellan.ecobridge.infrastructure.persistence.storage.AsyncLogger;
import top.ellan.ecobridge.util.ConfigMigrator;
import top.ellan.ecobridge.util.HolidayManager;
import top.ellan.ecobridge.util.LogUtil;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * EcoBridge v1.6.5 - Initialization Fix
 * <p>
 * 修复日志:
 * 1. [Fix] 修复 ActivityCollector.startHeartbeat 不可见的编译错误，改用 init()。
 * 2. [Refactor] 移除主类中冗余的 Listener 实现，避免事件双重处理。
 */
public final class EcoBridge extends JavaPlugin { // [Fix] 移除 implements Listener

    private static volatile EcoBridge instance;
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static LimitManager limitAPI;

    private ExecutorService virtualExecutor;
    private final AtomicBoolean fullyInitialized = new AtomicBoolean(false);
    
    // 影子模式状态控制
    private final AtomicBoolean shadowMode = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        instance = this;

        // 1. 初始化 Java 25 虚拟线程池
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

        // 2. 引导基础架构
        try {
            LogUtil.init();
            saveDefaultConfig();
            ConfigMigrator.checkAndMigrate(this);
            
            // [修复] 在引导基础设施前，优先初始化物品配置管理器
            ItemConfigManager.init(this);
            
            bootstrapInfrastructure();
            
            // [Fix] 使用 init() 替代 startHeartbeat()
            // init() 内部包含了 startHeartbeat() 逻辑以及必要的事件监听器注册
            ActivityCollector.init(this);
            
            // 注册 ASM 转换器
            setupBytecodeTransformer();
            
        } catch (Exception e) {
            getLogger().severe("基础架构引导失败: " + e.getMessage());
            e.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 3. 环境与依赖校验
        if (!verifyDependencies()) return;

        // 4. 组件加载拓扑
        try {
            EconomyManager.init(this);
            NativeBridge.init(this);
            EconomicStateManager.init(this);
            PricingManager.init(this);
            TransferManager.init(this);
            limitAPI = new LimitManager(this);

            // 5. 核心业务注册 (动态注册适配 Paper)
            registerCommands(); 
            registerListeners();
            registerHooks();

            // 执行物理指令劫持，锁定全服转账入口
            new CommandHijacker(this).hijackAllCurrencies();

            this.fullyInitialized.set(true);
            
            // [Fix] 在所有加载完成后的最后一刻，一次性打印对齐完美的 Banner
            printSummaryBanner();

        } catch (Throwable e) {
            LogUtil.error("致命错误: 初始化拓扑崩溃", e);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        shutdownSequence();
        instance = null;
    }

    /**
     * 初始化字节码转换器
     */
    private void setupBytecodeTransformer() {
        LogUtil.info("<aqua>正在注入 ASM 拦截器 (v1.6.2 Redirection)...</aqua>");
        try {
            Instrumentation inst = getInstrumentation();
            if (inst != null) {
                inst.addTransformer(new EcoShopTransformer(), true);
                
                // 处理 UltimateShop 的热加载逻辑接管
                for (Class<?> clazz : inst.getAllLoadedClasses()) {
                    if (clazz.getName().equals("cn.superiormc.ultimateshop.objects.buttons.ObjectItem")) {
                        inst.retransformClasses(clazz);
                        LogUtil.info("已完成对 ObjectItem (UltimateShop) 的动态逻辑重定向。");
                        break;
                    }
                }
                LogUtil.info("<green>✔ ASM 内核挂载成功。");
            } else {
                LogUtil.warn("未检测到 Instrumentation，建议检查 Agent 配置。");
            }
        } catch (Exception e) {
            LogUtil.error("ASM 注入过程发生致命错误", e);
        }
    }

    private Instrumentation getInstrumentation() {
        try {
            Class<?> agentClass = Class.forName("net.bytebuddy.agent.ByteBuddyAgent");
            Method installMethod = agentClass.getMethod("install");
            return (Instrumentation) installMethod.invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    public static EcoBridge getInstance() {
        EcoBridge inst = instance;
        if (inst == null) throw new IllegalStateException("EcoBridge has not been initialized.");
        return inst;
    }

    public static EcoLimitAPI getLimitAPI() {
        return limitAPI;
    }

    public boolean isShadowMode() {
        return shadowMode.get();
    }

    public void setShadowMode(boolean enabled) {
        this.shadowMode.set(enabled);
    }

    private void shutdownSequence() {
        sendConsole("<yellow>[EcoBridge] 正在启动安全关机序列...");

        if (fullyInitialized.getAndSet(false)) {
            try {
                if (RedisManager.getInstance() != null) RedisManager.getInstance().shutdown();
                HolidayManager.shutdown();

                if (PricingManager.getInstance() != null) PricingManager.getInstance().shutdown();
                if (EconomyManager.getInstance() != null) EconomyManager.getInstance().shutdown();
                if (TransferManager.getInstance() != null) TransferManager.getInstance().shutdown();

                if (AsyncLogger.getInstance() != null) AsyncLogger.getInstance().shutdown();
                
                LogUtil.info("正在执行缓存强制持久化...");
                HotDataCache.saveAllSync();

                NativeBridge.shutdown();

            } catch (Exception e) {
                getLogger().severe("关机期间异常: " + e.getMessage());
            }
        }

        terminateVirtualPool();

        try {
            DatabaseManager.close();
        } catch (Exception ignored) {}

        getServer().getScheduler().cancelTasks(this);
        
        sendConsole("<red>[EcoBridge] 系统资源回收完毕。");
    }

    private void bootstrapInfrastructure() {
        DatabaseManager.init();
        AsyncLogger.init(this);
        HolidayManager.init();
        RedisManager.init(this);
    }

    private void terminateVirtualPool() {
        if (virtualExecutor != null && !virtualExecutor.isShutdown()) {
            virtualExecutor.shutdown();
            try {
                if (!virtualExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    virtualExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                virtualExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean verifyDependencies() {
        var pm = Bukkit.getPluginManager();
        if (!pm.isPluginEnabled("CoinsEngine")) {
            sendConsole("<red>致命错误: 未检测到必要依赖 CoinsEngine。");
            pm.disablePlugin(this);
            return false;
        }
        return true;
    }

    private void registerListeners() {
        var pm = getServer().getPluginManager();
        // [Fix] 移除 pm.registerEvents(this, this); 因为主类不再负责 ActivityCollector 的监听
        pm.registerEvents(new CoinsEngineListener(this), this);
        pm.registerEvents(new CacheListener(), this);
    }

    private void registerHooks() {
        var pm = getServer().getPluginManager();
        if (pm.isPluginEnabled("PlaceholderAPI")) {
            new EcoPlaceholderExpansion(this).register();
        }
        if (pm.isPluginEnabled("UltimateShop")) {
            pm.registerEvents(
                new UltimateShopHook(
                    TransferManager.getInstance(), 
                    PricingManager.getInstance(), 
                    limitAPI
                ), this
            );
        }
    }

    /**
     * [Paper Adaption] 动态指令注册
     * 兼容 paper-plugin.yml，绕过 getCommand() 的限制
     */
    private void registerCommands() {
        try {
            Field commandMapField = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            commandMapField.setAccessible(true);
            CommandMap commandMap = (CommandMap) commandMapField.get(Bukkit.getServer());

            // 1. 注册 ecopay
            commandMap.register("ecobridge", new DynamicCommand(
                "ecopay",
                "通过 Rust 物理内核审计发起受监管的转账",
                "/ecopay <player> <amount>",
                Arrays.asList("epay", "auditpay"),
                "ecobridge.command.transfer",
                new TransferCommand()
            ));

            // 2. 注册 ecoadmin
            commandMap.register("ecobridge", new DynamicCommand(
                "ecoadmin",
                "EcoBridge 核心管理指令 (Native内核控制/重载/影子模式切换)",
                "/ecoadmin <shadow|reload>",
                Arrays.asList("eb", "eco"),
                "ecobridge.admin",
                new AdminCommand()
            ));
            
            LogUtil.info("Paper 模式动态指令注册完成。");

        } catch (Exception e) {
            LogUtil.error("动态指令注册失败 (Paper Mode)", e);
        }
    }

    /**
     * [Helper] 将 CommandExecutor 适配为 Bukkit Command
     */
    private static class DynamicCommand extends org.bukkit.command.Command {
        private final org.bukkit.command.CommandExecutor executor;

        protected DynamicCommand(String name, String description, String usageMessage, List<String> aliases, String permission, org.bukkit.command.CommandExecutor executor) {
            super(name, description, usageMessage, aliases);
            this.setPermission(permission);
            this.executor = executor;
        }

        @Override
        public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
            return executor.onCommand(sender, this, commandLabel, args);
        }
    }

    public void reload() {
        reloadConfig();
        
        // [修复] 优先重载物品配置文件，确保后续组件能正确读取 items.yml
        ItemConfigManager.reload();
        
        ConfigMigrator.checkAndMigrate(this);
        LogUtil.init();
        if (EconomyManager.getInstance() != null) EconomyManager.getInstance().loadState();
        if (PricingManager.getInstance() != null) PricingManager.getInstance().loadConfig();
        if (TransferManager.getInstance() != null) TransferManager.getInstance().loadConfig();
        if (limitAPI != null) limitAPI.reloadCache();
        
        // 重载时也打印一次 Banner
        printSummaryBanner();
    }

    // [Fix] 移除 onJoin / onQuit 冗余方法
    // @EventHandler public void onJoin(PlayerJoinEvent event) ...
    // @EventHandler public void onQuit(PlayerQuitEvent event) ...

    public ExecutorService getVirtualExecutor() { return virtualExecutor; }
    public static MiniMessage getMiniMessage() { return MM; }
    public boolean isFullyInitialized() { return fullyInitialized.get(); }

    private void sendConsole(String msg, TagResolver... resolvers) {
        Bukkit.getConsoleSender().sendMessage(MM.deserialize(msg, resolvers));
    }

    // ========================================================================================
    // [New Feature] 完美对齐的 Banner 生成系统 (v1.6.4)
    // ========================================================================================

    private void printSummaryBanner() {
        String version = getPluginMeta().getVersion();
        
        // 1. 准备所有要显示的内容
        List<String> lines = new ArrayList<>();
        lines.add("EcoBridge v" + version);
        lines.add("Native Engine: " + (NativeBridge.isLoaded() ? "Active (Rust/FFM)" : "Disabled (Java fallback)"));
        lines.add("System Mode: " + (isShadowMode() ? "Shadow (Audit Only)" : "Enforced (Active Block)"));
        lines.add("Concurrency: Rayon & Virtual Threads");
        lines.add("Paper Compatibility: Native Mode");

        // 2. 边框样式
        String borderGradient = "<gradient:aqua:blue>";
        String textGradient = "<gradient:white:gray>";
        int boxWidth = 55; // 盒子总视觉宽度

        // 3. 构建消息
        sendConsole(borderGradient + buildBorder("┏", "━", "┓", boxWidth) + "</gradient>");
        
        for (String line : lines) {
            // -4 是因为左右各有一个 "┃ " (视觉宽度为 2)
            String centeredLine = centerText(line, boxWidth - 4); 
            sendConsole(borderGradient + "┃ <reset>" + textGradient + centeredLine + "</gradient>" + borderGradient + " ┃</gradient>");
        }
        
        sendConsole(borderGradient + buildBorder("┗", "━", "┛", boxWidth) + "</gradient>");
    }

    /**
     * 生成定长边框
     */
    private String buildBorder(String left, String mid, String right, int width) {
        // 左右各占2视觉宽度 (全角或符号)，实际上字符本身是1
        // 这里简单处理：让中间的 ━ 重复足够多次
        // 视觉宽度：left(1) + mid(n) + right(1) = width
        return left + mid.repeat(width - 2) + right;
    }

    /**
     * 核心算法：中英文混合居中
     */
    private String centerText(String text, int width) {
        int textVisualLength = getVisualLength(text);
        
        if (textVisualLength >= width) {
            return text; // 文本太长，不处理
        }

        int padding = width - textVisualLength;
        int leftPad = padding / 2;
        int rightPad = padding - leftPad;

        return " ".repeat(leftPad) + text + " ".repeat(rightPad);
    }

    /**
     * 计算字符串的视觉宽度 (中文=2, 英文=1)
     */
    private int getVisualLength(String s) {
        if (s == null) return 0;
        int length = 0;
        for (char c : s.toCharArray()) {
            // 简单判断：ASCII 范围外通常是宽字符 (包括中文、全角符号)
            // 这种判断对绝大多数控制台字体适用
            length += (c > 127) ? 2 : 1;
        }
        return length;
    }
}
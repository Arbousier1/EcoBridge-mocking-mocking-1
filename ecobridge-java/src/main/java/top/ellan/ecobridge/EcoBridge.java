package top.ellan.ecobridge;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import top.ellan.ecobridge.api.EcoLimitAPI;
import top.ellan.ecobridge.application.bootstrap.CoreServiceLifecycle;
import top.ellan.ecobridge.application.bootstrap.InfrastructureLifecycle;
import top.ellan.ecobridge.infrastructure.ffi.bridge.NativeBridge;
import top.ellan.ecobridge.infrastructure.cache.HotDataCache;
import top.ellan.ecobridge.integration.platform.command.AdminCommand; 
import top.ellan.ecobridge.integration.platform.command.TransferCommand;
import top.ellan.ecobridge.integration.platform.hook.EcoPlaceholderExpansion;
import top.ellan.ecobridge.integration.platform.hook.UltimateShopHook;
import top.ellan.ecobridge.integration.platform.asm.EcoShopTransformer; 
import top.ellan.ecobridge.integration.platform.listener.CacheListener;
import top.ellan.ecobridge.integration.platform.listener.CoinsEngineListener;
import top.ellan.ecobridge.integration.platform.listener.CommandHijacker;
import top.ellan.ecobridge.application.service.*;
import top.ellan.ecobridge.infrastructure.persistence.storage.ActivityCollector;
import top.ellan.ecobridge.application.service.ItemConfigManager; // 纭繚瀵煎叆
import top.ellan.ecobridge.util.LogUtil;
import top.ellan.ecobridge.util.UltimateShopImporter;

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
 * EcoBridge v1.6.7 - Config Safety Patch
 * <p>
 * 鏇存柊璁板綍:
 * 1. [Config] 绂佺敤鑷姩閰嶇疆杩佺Щ鍜岃鐩栵紝淇濇姢鐢ㄦ埛鎵嬪姩淇敼鐨?config.yml銆?
 */
public final class EcoBridge extends JavaPlugin implements Listener {

    private static volatile EcoBridge instance;
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static LimitManager limitAPI;

    private ExecutorService virtualExecutor;
    private final AtomicBoolean fullyInitialized = new AtomicBoolean(false);
    
    // 褰卞瓙妯″紡鐘舵€佹帶鍒?
    private final AtomicBoolean shadowMode = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        instance = this;

        // 1. 鍒濆鍖?Java 25 铏氭嫙绾跨▼姹?
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

        // 2. 寮曞鍩虹鏋舵瀯
        try {
            LogUtil.init();
            
            // [淇敼 1] 绂佹鑷姩瑕嗙洊閰嶇疆锛氫粎褰撴枃浠朵笉瀛樺湪鏃舵墠鍒涘缓
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            if (!new java.io.File(getDataFolder(), "config.yml").exists()) {
                saveDefaultConfig();
            }
            
            // [淇敼 2] 绂佺敤杩佺Щ鍣細闃叉瀹冣€滀慨澶嶁€濇垨鈥滈噸缃€濅綘鐨勯厤缃敞閲婂拰鏁板€?
            // ConfigMigrator.checkAndMigrate(this);
            
            // [鍏抽敭淇] 鍦ㄥ紩瀵煎熀纭€璁炬柦鍓嶏紝浼樺厛鍒濆鍖栫墿鍝侀厤缃鐞嗗櫒
            // 纭繚鍚庣画 LimitManager 鍔犺浇鏃?items.yml 宸插氨缁?
            ItemConfigManager.init(this);
            
            bootstrapInfrastructure();
            ActivityCollector.startHeartbeat(this); // 淇濇寔鍘熸湁鍛藉悕涔犳儻
            
            // 娉ㄥ唽 ASM 杞崲鍣?
            setupBytecodeTransformer();
            
        } catch (Exception e) {
            getLogger().severe("鍩虹鏋舵瀯寮曞澶辫触: " + e.getMessage());
            e.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 3. 鐜涓庝緷璧栨牎楠?
        if (!verifyDependencies()) return;

        // 4. 缁勪欢鍔犺浇鎷撴墤
        try {
            // LimitManager 渚濊禆 ItemConfigManager锛屾鏃跺凡瀹夊叏
            limitAPI = CoreServiceLifecycle.start(this);

            // 5. 鏍稿績涓氬姟娉ㄥ唽 (鍔ㄦ€佹敞鍐岄€傞厤 Paper)
            registerCommands(); 
            registerListeners();
            registerHooks();

            // 鎵ц鐗╃悊鎸囦护鍔寔锛岄攣瀹氬叏鏈嶈浆璐﹀叆鍙?
            new CommandHijacker(this).hijackAllCurrencies();

            // [鏂板] 鍚姩鏃惰嚜鍔ㄤ粠 UltimateShop 瀵煎叆鍟嗗搧鏁版嵁
            if (getServer().getPluginManager().isPluginEnabled("UltimateShop")) {
                double defaultLambda = getConfig().getDouble("economy.default-lambda", 0.002);
                UltimateShopImporter.runImport(defaultLambda);
            }

            this.fullyInitialized.set(true);
            
            // 鎵撳嵃 Banner
            printSummaryBanner();

        } catch (Throwable e) {
            LogUtil.error("鑷村懡閿欒: 鍒濆鍖栨嫇鎵戝穿婧?, e);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        shutdownSequence();
        instance = null;
    }

    /**
     * [鏂板] 瀹夊叏鐨勭姸鎬佹鏌ユ柟娉?
     * 鐢ㄤ簬闃叉寮傛浠诲姟(濡?Caffeine 鍥炶皟)鍦ㄦ彃浠跺叧闂悗璁块棶瀹炰緥瀵艰嚧宕╂簝
     */
    public static boolean isInitialized() {
        return instance != null;
    }

    public static EcoBridge getInstance() {
        EcoBridge inst = instance;
        if (inst == null) throw new IllegalStateException("EcoBridge has not been initialized or is shutting down.");
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

    // ========================================================================================
    // 鍩虹璁炬柦涓庣敓鍛藉懆鏈熺鐞?
    // ========================================================================================

    private void bootstrapInfrastructure() {
        InfrastructureLifecycle.start(this);
    }

    private void shutdownSequence() {
        sendConsole("<yellow>[EcoBridge] 姝ｅ湪鍚姩瀹夊叏鍏虫満搴忓垪...");

        if (fullyInitialized.getAndSet(false)) {
            try {
                CoreServiceLifecycle.stop();
                
                LogUtil.info("姝ｅ湪鎵ц缂撳瓨寮哄埗鎸佷箙鍖?..");
                HotDataCache.saveAllSync();


            } catch (Exception e) {
                getLogger().severe("鍏虫満鏈熼棿寮傚父: " + e.getMessage());
            }
        }

        terminateVirtualPool();

        InfrastructureLifecycle.stop();

        getServer().getScheduler().cancelTasks(this);
        
        sendConsole("<red>[EcoBridge] 绯荤粺璧勬簮鍥炴敹瀹屾瘯銆?);
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

    private void setupBytecodeTransformer() {
        LogUtil.info("<aqua>姝ｅ湪娉ㄥ叆 ASM 鎷︽埅鍣?(v1.6.2 Redirection)...</aqua>");
        try {
            Instrumentation inst = getInstrumentation();
            if (inst != null) {
                inst.addTransformer(new EcoShopTransformer(), true);
                
                for (Class<?> clazz : inst.getAllLoadedClasses()) {
                    if (clazz.getName().equals("cn.superiormc.ultimateshop.objects.buttons.ObjectItem")) {
                        inst.retransformClasses(clazz);
                        LogUtil.info("宸插畬鎴愬 ObjectItem (UltimateShop) 鐨勫姩鎬侀€昏緫閲嶅畾鍚戙€?);
                        break;
                    }
                }
                LogUtil.info("<green>鉁?ASM 鍐呮牳鎸傝浇鎴愬姛銆?);
            } else {
                LogUtil.warn("鏈娴嬪埌 Instrumentation锛屽缓璁鏌?Agent 閰嶇疆銆?);
            }
        } catch (Exception e) {
            LogUtil.error("ASM 娉ㄥ叆杩囩▼鍙戠敓鑷村懡閿欒", e);
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

    private boolean verifyDependencies() {
        var pm = Bukkit.getPluginManager();
        if (!pm.isPluginEnabled("CoinsEngine")) {
            sendConsole("<red>鑷村懡閿欒: 鏈娴嬪埌蹇呰渚濊禆 CoinsEngine銆?);
            pm.disablePlugin(this);
            return false;
        }
        return true;
    }

    private void registerListeners() {
        var pm = getServer().getPluginManager();
        pm.registerEvents(this, this);
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

    private void registerCommands() {
        try {
            Field commandMapField = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            commandMapField.setAccessible(true);
            CommandMap commandMap = (CommandMap) commandMapField.get(Bukkit.getServer());

            commandMap.register("ecobridge", new DynamicCommand(
                "ecopay",
                "閫氳繃 Rust 鐗╃悊鍐呮牳瀹¤鍙戣捣鍙楃洃绠＄殑杞处",
                "/ecopay <player> <amount>",
                Arrays.asList("epay", "auditpay"),
                "ecobridge.command.transfer",
                new TransferCommand()
            ));

            commandMap.register("ecobridge", new DynamicCommand(
                "ecoadmin",
                "EcoBridge 鏍稿績绠＄悊鎸囦护 (Native鍐呮牳鎺у埗/閲嶈浇/褰卞瓙妯″紡鍒囨崲)",
                "/ecoadmin <shadow|reload|import>",
                Arrays.asList("eb", "eco"),
                "ecobridge.admin",
                new AdminCommand()
            ));
            
            LogUtil.info("Paper 妯″紡鍔ㄦ€佹寚浠ゆ敞鍐屽畬鎴愩€?);

        } catch (Exception e) {
            LogUtil.error("鍔ㄦ€佹寚浠ゆ敞鍐屽け璐?(Paper Mode)", e);
        }
    }

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
        
        // [淇] 閲嶈浇鐗╁搧閰嶇疆锛岀‘淇濇渶鏂?items.yml 琚姞杞?
        ItemConfigManager.reload();
        
        // [淇敼 3] 閲嶈浇鏃剁鐢ㄨ縼绉绘鏌ワ紝闃叉鈥滀慨澶嶁€濋厤缃?
        // ConfigMigrator.checkAndMigrate(this);
        
        LogUtil.init();
        CoreServiceLifecycle.reload((LimitManager) limitAPI);

        // [鏂板] 閲嶈浇鏃惰嚜鍔ㄥ啀娆″悓姝?UltimateShop 鏁版嵁
        if (getServer().getPluginManager().isPluginEnabled("UltimateShop")) {
            double defaultLambda = getConfig().getDouble("economy.default-lambda", 0.002);
            UltimateShopImporter.runImport(defaultLambda);
        }
        
        printSummaryBanner();
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { ActivityCollector.updateSnapshot(event.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { ActivityCollector.removePlayer(event.getPlayer().getUniqueId()); }

    public ExecutorService getVirtualExecutor() { return virtualExecutor; }
    public static MiniMessage getMiniMessage() { return MM; }
    public boolean isFullyInitialized() { return fullyInitialized.get(); }

    private void sendConsole(String msg, TagResolver... resolvers) {
        Bukkit.getConsoleSender().sendMessage(MM.deserialize(msg, resolvers));
    }

    private void printSummaryBanner() {
        String version = getPluginMeta().getVersion();
        
        List<String> lines = new ArrayList<>();
        lines.add("EcoBridge v" + version);
        lines.add("Native Engine: " + (NativeBridge.isLoaded() ? "Active (Rust/FFM)" : "Disabled (Java fallback)"));
        lines.add("System Mode: " + (isShadowMode() ? "Shadow (Audit Only)" : "Enforced (Active Block)"));
        lines.add("Concurrency: Rayon & Virtual Threads");
        lines.add("Paper Compatibility: Native Mode");

        String borderGradient = "<gradient:aqua:blue>";
        String textGradient = "<gradient:white:gray>";
        int boxWidth = 55;

        sendConsole(borderGradient + buildBorder("鈹?, "鈹?, "鈹?, boxWidth) + "</gradient>");
        
        for (String line : lines) {
            String centeredLine = centerText(line, boxWidth - 4); 
            sendConsole(borderGradient + "鈹?<reset>" + textGradient + centeredLine + "</gradient>" + borderGradient + " 鈹?/gradient>");
        }
        
        sendConsole(borderGradient + buildBorder("鈹?, "鈹?, "鈹?, boxWidth) + "</gradient>");
    }

    private String buildBorder(String left, String mid, String right, int width) {
        return left + mid.repeat(width - 2) + right;
    }

    private String centerText(String text, int width) {
        int textVisualLength = getVisualLength(text);
        if (textVisualLength >= width) return text;
        int padding = width - textVisualLength;
        int leftPad = padding / 2;
        int rightPad = padding - leftPad;
        return " ".repeat(leftPad) + text + " ".repeat(rightPad);
    }

    private int getVisualLength(String s) {
        if (s == null) return 0;
        int length = 0;
        for (char c : s.toCharArray()) {
            length += (c > 127) ? 2 : 1;
        }
        return length;
    }
}

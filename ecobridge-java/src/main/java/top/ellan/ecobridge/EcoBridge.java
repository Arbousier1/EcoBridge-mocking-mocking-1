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
import top.ellan.ecobridge.application.service.ItemConfigManager;
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
 * Main plugin class.
 * Coordinates startup, dependency checks, hooks, and shutdown.
 */
public final class EcoBridge extends JavaPlugin implements Listener {

    private static volatile EcoBridge instance;
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static LimitManager limitAPI;

    private ExecutorService virtualExecutor;
    private final AtomicBoolean fullyInitialized = new AtomicBoolean(false);
    

    // Shadow mode keeps observation/audit behaviors without strict blocking.
    private final AtomicBoolean shadowMode = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        instance = this;


        // Runtime executor for async plugin tasks.
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();


        try {
            LogUtil.init();
            

            // Ensure data folder and default config exist.
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            if (!new java.io.File(getDataFolder(), "config.yml").exists()) {
                saveDefaultConfig();
            }
            


            


            // Initialize dedicated items.yml loader.
            ItemConfigManager.init(this);
            
            // Start infrastructure and activity heartbeat.
            bootstrapInfrastructure();
            ActivityCollector.startHeartbeat(this);
            

            // Install ASM redirection for UltimateShop hook points.
            setupBytecodeTransformer();
            
        } catch (Exception e) {
            getLogger().severe("闂傚倸鍊烽懗鍓佹兜閸洖鐤鹃柣鎰ゴ閺嬪秹鏌ㄥ┑鍡╂Ф闁逞屽厸缁舵艾鐣烽妸鈺佺骇闁瑰瓨绻傚▓銈夋⒒娴ｅ搫甯堕柟鑺ョ矒瀵偊骞栨担鍝ュ姦濡炪倖甯掗ˇ顖炴倶閻樼偨浜滈柡鍥朵簽缁夘喚鈧鍠楅幐鎶藉箖濠婂牆骞㈡俊銈勭贰閸熷本绻濋悽闈浶為柛銊︽そ瀹曟洟鎳栭埡浣哥亰闂佸憡鎸嗛崟顐ｇ€? " + e.getMessage());
            e.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }


        // Abort startup if required dependencies are missing.
        if (!verifyDependencies()) return;


        try {

            // Start core services then register commands/listeners/hooks.
            limitAPI = CoreServiceLifecycle.start(this);


            registerCommands(); 
            registerListeners();
            registerHooks();


            // Hijack currency commands for unified eco behavior.
            new CommandHijacker(this).hijackAllCurrencies();


            // Import UltimateShop item defaults when plugin exists.
            if (getServer().getPluginManager().isPluginEnabled("UltimateShop")) {
                double defaultLambda = getConfig().getDouble("economy.default-lambda", 0.002);
                UltimateShopImporter.runImport(defaultLambda);
            }

            this.fullyInitialized.set(true);
            

            // Print startup summary banner.
            printSummaryBanner();

        } catch (Throwable e) {
            LogUtil.error("Fatal error during plugin initialization.", e);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        shutdownSequence();
        instance = null;
    }

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





    // ============================== lifecycle helpers ==============================
    private void bootstrapInfrastructure() {
        InfrastructureLifecycle.start(this);
    }

    private void shutdownSequence() {
        sendConsole("<yellow>[EcoBridge] 婵犵數濮甸鏍窗濡ゅ啯宕查柟閭﹀枛缁躲倝鏌﹀Ο渚闁肩増瀵ч妵鍕疀閹捐泛顣洪柣鐔哥懕闂勫嫰濡甸崟顖氬唨闁靛鍔岄ˉ婵嬫偠濮樺崬鏋涢柡宀嬬稻閹棃鏁愰崱妤佺暚婵＄偑鍊х€靛矂宕圭捄铏规殾闁挎繂鐗忛悿鈧┑鐐村灦椤洭藝瑜斿娲箚瑜庣粋瀣煕鐎ｎ亝鍣介柤鍝勫船椤繄鎹勯搹璇″晣闂備礁鎼ˇ浼村垂閸︻厾涓嶅ù鐓庣摠閻?..");

        if (fullyInitialized.getAndSet(false)) {
            try {
                CoreServiceLifecycle.stop();
                
                LogUtil.info("婵犵數濮甸鏍窗濡ゅ啯宕查柟閭﹀枛缁躲倝鏌﹀Ο渚闁肩増瀵ч妵鍕疀閹捐泛顣虹紓浣哄О閸庢娊骞夐幖浣哥闁挎棁銆€閸嬫挻绗熼埀顒勭嵁鐎ｎ喗鍋愰柣銏㈩暜缁辨娊姊绘担绛嬫綈闁稿骸纾竟鏇㈩敇閵忕姷顔戦梺缁橆焽椤ｄ粙宕戦幘鏂ユ灁闁割煈鍠楅悘鍫ユ⒑閸︻厸鎷￠柛瀣工閻ｇ兘宕崟搴㈡瀹曘劑顢橀悪鈧崬璺衡攽閻愯埖褰х紓宥勭窔钘熼柟鍓х帛閸嬧晛霉閻樺樊鍎愰柣?..");
                HotDataCache.saveAllSync();


            } catch (Exception e) {
                getLogger().severe("闂傚倸鍊烽懗鍫曗€﹂崼銏″床婵°倐鍋撻柍璇茬Ч瀵挳鎮欓埡鍌涙殜闂備線鈧偛鑻晶鎾煛瀹€瀣瘈鐎规洖銈告慨鈧柣妯虹－娴滎亝绻濈喊妯活潑闁稿瀚伴幃妯衡攽鐎ｅ灚鏅梺鎸庣箓椤︻垳澹曢崗鍏煎弿婵☆垰鎼懜褰掓煟? " + e.getMessage());
            }
        }

        terminateVirtualPool();

        InfrastructureLifecycle.stop();

        getServer().getScheduler().cancelTasks(this);
        
        sendConsole("<red>[EcoBridge] System resources released.</red>");
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
        LogUtil.info("<aqua>婵犵數濮甸鏍窗濡ゅ啯宕查柟閭﹀枛缁躲倝鏌﹀Ο渚闁肩増瀵ч妵鍕疀閹炬剚浠遍梺鍝勵儐閻楃娀寮婚弴鐔虹闁割煈鍠栨慨搴ㄦ倵?ASM 闂傚倸鍊烽懗鍫曞箠閹捐鍚归柡宥庡幗閳锋棃鏌涢弴銊ョ仩闁哄嫨鍎甸弻娑樷槈濞嗘劗绋囬梻?(v1.6.2 Redirection)...</aqua>");
        try {
            Instrumentation inst = getInstrumentation();
            if (inst != null) {
                inst.addTransformer(new EcoShopTransformer(), true);
                
                for (Class<?> clazz : inst.getAllLoadedClasses()) {
                    if (clazz.getName().equals("cn.superiormc.ultimateshop.objects.buttons.ObjectItem")) {
                        inst.retransformClasses(clazz);
                        LogUtil.info("Applied dynamic redirection to ObjectItem (UltimateShop).");
                        break;
                    }
                }
                LogUtil.info("<green>ASM core mounted successfully.</green>");
            } else {
                LogUtil.warn("Instrumentation not detected, please verify Agent setup.");
            }
        } catch (Exception e) {
            LogUtil.error("ASM injection failed.", e);
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
            sendConsole("<red>Fatal error: required dependency CoinsEngine is missing.</red>");
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
                "Transfer money through EcoBridge economic pipeline.",
                "/ecopay <player> <amount>",
                Arrays.asList("epay", "auditpay"),
                "ecobridge.command.transfer",
                new TransferCommand()
            ));

            commandMap.register("ecobridge", new DynamicCommand(
                "ecoadmin",
                "EcoBridge admin command (shadow/reload/import).",
                "/ecoadmin <shadow|reload|import>",
                Arrays.asList("eb", "eco"),
                "ecobridge.admin",
                new AdminCommand()
            ));
            
            LogUtil.info("Paper mode dynamic command registration complete.");

        } catch (Exception e) {
            LogUtil.error("闂傚倸鍊风粈渚€骞夐敓鐘茶摕闁挎繂顦粈澶屸偓骞垮劚椤︻垶鎮為崹顐犱簻闁硅揪绲剧涵鍫曟煕閺傝法效闁诡喗顨婂畷褰掝敃閵堝孩娈圭紓鍌欑閸婂湱鏁埄鍐х箚闁割偅娲栭獮銏′繆椤栨粎甯涢柣锝呫偢濮婄粯鎷呴崨濠傛殘闂佸憡姊瑰ú鐔煎箠閻旂⒈鏁嶉柣鎰棘閿曞倹鐓欓悗鐢殿焾鏍￠柣?(Paper Mode)", e);
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
        

        // Reload external item settings.
        ItemConfigManager.reload();
        


        
        LogUtil.init();
        CoreServiceLifecycle.reload((LimitManager) limitAPI);


        // Re-import UltimateShop values after reload.
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

        sendConsole(borderGradient + buildBorder("+", "-", "+", boxWidth) + "</gradient>");
        
        for (String line : lines) {
            String centeredLine = centerText(line, boxWidth - 4); 
            sendConsole(borderGradient + "|<reset>" + textGradient + centeredLine + "</gradient>" + borderGradient + "|</gradient>");
        }
        
        sendConsole(borderGradient + buildBorder("+", "-", "+", boxWidth) + "</gradient>");
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

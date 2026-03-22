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
import top.ellan.ecobridge.application.service.ItemConfigManager; // 缁绢収鍠曠换姘扁偓鐢靛帶閸?
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
 * 闁哄洤鐡ㄩ弻濠勬媼閺夎法绉?
 * 1. [Config] 缂佸倷鑳堕弫銈夋嚊椤忓嫬袟闂佹澘绉堕悿鍡樻交娴ｇ洅鈺呭椽瀹€鍐炬船闁烩晜鐗槐婵囩┍濠靛洤袘闁活潿鍔嶉崺娑㈠箥鐎ｎ亜袟濞ｅ浂鍠楅弫濂告儍?config.yml闁?
 */
public final class EcoBridge extends JavaPlugin implements Listener {

    private static volatile EcoBridge instance;
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static LimitManager limitAPI;

    private ExecutorService virtualExecutor;
    private final AtomicBoolean fullyInitialized = new AtomicBoolean(false);
    
    // 鐟滄澘宕悺娆徫熼垾宕囩闁绘鍩栭埀顑跨劍鐢爼宕?
    private final AtomicBoolean shadowMode = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        instance = this;

        // 1. 闁告帗绻傞～鎰板礌?Java 25 闁惧繑纰嶇€氭瑧鐥捄銊㈡煠婵?
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

        // 2. 鐎殿喗娲栭閬嶅春閾忚鏀ㄩ柡瀣煐閻?
        try {
            LogUtil.init();
            
            // [濞ｅ浂鍠楅弫?1] 缂佸倷鐒﹂娑㈡嚊椤忓嫬袟閻熸洖妫涘ú濠囨煀瀹ュ洨鏋傞柨娑欑煯缁氦銇愰幘瀛樼€ù鐘虫构缁楀鈧稒锚濠€顏堝籍閼搁潧顤呴柛鎺撶☉缂?
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            if (!new java.io.File(getDataFolder(), "config.yml").exists()) {
                saveDefaultConfig();
            }
            
            // [濞ｅ浂鍠楅弫?2] 缂佸倷鑳堕弫銈嗘交娴ｇ洅鈺呭闯椤帞绐楅梻鍐ㄥ级椤掓稓鈧懓鍟犻埀顒佺矆閹便劍寰勫浣插亾濠靛洤鐏楅柍銉︾矒閸ｅ摜绱旈鍏夊亾濠靛懐绋戦柣銊ュ閸樸倗绱旈鑺ユ殘闂佹彃锕ら幏浼村极閺夊簱鍋?
            // ConfigMigrator.checkAndMigrate(this);
            
            // [闁稿繑濞婇弫顓熺┍椤旂⒈妲籡 闁革负鍔岀槐鈺冣偓鐢靛帶閻斺偓缁绢厸鍋撻悹浣哄亾閺岋箓宕滃蹇曠濞村吋锚閸樻盯宕氬┑鍡╂綏闁告牗鐗滄晶鍧楀传娓氣偓閸樸倗绱旈缁㈠悁闁荤偛妫楀▍?
            // 缁绢収鍠曠换姘跺触鎼达絿鏁?LimitManager 闁告梻濮惧ù鍥籍?items.yml 鐎瑰憡褰冨銊х磼?
            ItemConfigManager.init(this);
            
            bootstrapInfrastructure();
            ActivityCollector.startHeartbeat(this); // 濞ｅ洦绻冪€垫棃宕㈤悢鍛婄畳闁告稖妫勯幃鏇熺▕閻樺啿鍔?
            
            // 婵炲鍔岄崬?ASM 閺夌儐鍓氬畷鏌ュ闯?
            setupBytecodeTransformer();
            
        } catch (Exception e) {
            getLogger().severe("闁糕晞娅ｉ、鍛村几閼哥數鈧垰顕ｉ弴鐑嗗殼濠㈡儼绮剧憴? " + e.getMessage());
            e.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 3. 闁绘粠鍨伴。銊︾▔鎼存繄璐╅悹褎鐗楅悧搴㈩殽?
        if (!verifyDependencies()) return;

        // 4. 缂備礁瀚▎銏ゅ礉閻樼儤绁伴柟閿嬫尰婢?
        try {
            // LimitManager 濞撴碍绻嗙粋?ItemConfigManager闁挎稑鏈婵嬪籍鐠哄搫鍤掗悗鐟邦槸閸?
            limitAPI = CoreServiceLifecycle.start(this);

            // 5. 闁哄秶顭堢缓鐐▔濮橆剙顫ゆ繛澶堝妼閸?(闁告柣鍔嶉埀顑跨劍閺佺偤宕樺畝鍕ㄥ亾閸岀偛甯?Paper)
            registerCommands(); 
            registerListeners();
            registerHooks();

            // 闁圭瑳鍡╂斀闁绘せ鏅濋幃濠囧箰閸ワ附濮㈤柛鏃戝亝鐎垫棃鏁嶅畝鍕暁閻庤鑹鹃崣蹇涘嫉瀹ュ牊绁悹鎰剁畱閸欏棝宕?
            new CommandHijacker(this).hijackAllCurrencies();

            // [闁哄倹婢橀·鍍?闁告凹鍨版慨鈺呭籍閹澘娈伴柛鏂诲妺缁?UltimateShop 閻庣數鍘ч崣鍡涘疮閸℃鎯傞柡浣哄瀹?
            if (getServer().getPluginManager().isPluginEnabled("UltimateShop")) {
                double defaultLambda = getConfig().getDouble("economy.default-lambda", 0.002);
                UltimateShopImporter.runImport(defaultLambda);
            }

            this.fullyInitialized.set(true);
            
            // 闁瑰灚鎸稿畵?Banner
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

    /**
     * [闁哄倹婢橀·鍍?閻庣懓顦崣蹇涙儍閸曨厼笑闁诡兛鐒﹂ˉ鍛村蓟閵夛附鐓欐繛?
     * 闁活潿鍔嬬花顒勬⒓閸欏鍓剧€殿喖鍊归鐐寸鐠囨彃顫?濠?Caffeine 闁搞儳鍋犻惃?闁革负鍔嶈ぐ鍐╃鐠哄搫褰犻梻鍌ゅ幖閹鎷嬮崸妤侊紪閻庡湱鍋樼欢銉р偓浣冨閸ぱ冪暦閳哄倻鐨?
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
    // 闁糕晞娅ｉ、鍛媼閻愵剚鐓㈠☉鎾虫捣閺佹捇宕ㄩ挊澶嬪櫙闁哄牏鍠撻鎼佹偠?
    // ========================================================================================

    private void bootstrapInfrastructure() {
        InfrastructureLifecycle.start(this);
    }

    private void shutdownSequence() {
        sendConsole("<yellow>[EcoBridge] 婵繐绲藉﹢顏堝触椤栨艾袟閻庣懓顦崣蹇涘礂閾忣偅绨氶幖鏉戠箰閸?..");

        if (fullyInitialized.getAndSet(false)) {
            try {
                CoreServiceLifecycle.stop();
                
                LogUtil.info("婵繐绲藉﹢顏堝箥瑜戦、鎴犵磽閹惧磭鎽犵€殿喖鎼崺妤呭箰娴ｉ鐣介柛?..");
                HotDataCache.saveAllSync();


            } catch (Exception e) {
                getLogger().severe("闁稿繗娅曞┃鈧柡鍫㈠枛濡灝顕ｉ崒姘卞煑: " + e.getMessage());
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
        LogUtil.info("<aqua>婵繐绲藉﹢顏勨枖閵娿儱寮?ASM 闁瑰嚖闄勯崺鍛村闯?(v1.6.2 Redirection)...</aqua>");
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
                "闂侇偅淇虹换?Rust 闁绘せ鏅濋幃濠囧礃閸涱喚澹嬮悗瀛ゃ値鍚€闁告瑦鍨奸幑锝夊矗濡ゅ啯纾х紒鐙呯磿濞堟垶娼鍐槱",
                "/ecopay <player> <amount>",
                Arrays.asList("epay", "auditpay"),
                "ecobridge.command.transfer",
                new TransferCommand()
            ));

            commandMap.register("ecobridge", new DynamicCommand(
                "ecoadmin",
                "EcoBridge 闁哄秶顭堢缓鍓х不閿涘嫭鍊為柟绋挎矗閹?(Native闁告劕鎳忛悧鎶藉箳瑜嶉崺?闂佹彃绉峰ù?鐟滄澘宕悺娆徫熼垾宕囩闁告帒娲﹀畷?",
                "/ecoadmin <shadow|reload|import>",
                Arrays.asList("eb", "eco"),
                "ecobridge.admin",
                new AdminCommand()
            ));
            
            LogUtil.info("Paper mode dynamic command registration complete.");

        } catch (Exception e) {
            LogUtil.error("闁告柣鍔嶉埀顑跨劍鐎垫碍绂掗妶鍡樻殘闁告劕鑻妵鎴犳嫻?(Paper Mode)", e);
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
        
        // [濞ｅ浂鍠栭ˇ鐬?闂佹彃绉峰ù鍥偋閳轰焦鎯傞梺鏉跨Ф閻ゅ棝鏁嶅畝鈧垾妯荤┍濠靛洦浠橀柡?items.yml 閻炴凹鍋勬慨鐐存姜?
        ItemConfigManager.reload();
        
        // [濞ｅ浂鍠楅弫?3] 闂佹彃绉峰ù鍥籍閸撲緡娲ｉ柣顫姀缁鸿偐绮旂紒姗嗘⒕闁哄被鍎荤槐婵嬫⒓閸欏鍓鹃柍銉︾矆閹便劍寰勫浣插亾濠靛甯崇紓?
        // ConfigMigrator.checkAndMigrate(this);
        
        LogUtil.init();
        CoreServiceLifecycle.reload((LimitManager) limitAPI);

        // [闁哄倹婢橀·鍍?闂佹彃绉峰ù鍥籍閹澘娈伴柛鏂诲妼閸熲偓婵炲棌鈧櫕鍊辨慨?UltimateShop 闁轰胶澧楀畵?
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

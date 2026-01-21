package top.ellan.ecobridge.application.service;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.infrastructure.ffi.bridge.NativeBridge;
import top.ellan.ecobridge.domain.algorithm.PriceComputeEngine;
import top.ellan.ecobridge.util.LogUtil;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 宏观经济引擎 (MacroEngine v2.6 - Lifecycle Safe & Global Sync)
 * 职责：
 * 1. 驱动异步 PID 演算与价格快照生成。
 * 2. 协调本地与远程市场的热度同步。
 * <p>
 * 修复记录：
 * 1. [Fix] 增加 injectRemoteVolume 接口，解决跨服热度不同步问题。
 * 2. [Safety] 增加 dt 边界检查，防止重载导致的时间跳变。
 * 3. [Lifecycle] 构造时强制重置 PID 状态，防止内存漂移。
 */
public class MacroEngine {

    private final EcoBridge plugin;
    
    // 状态管理
    private final AtomicReference<Map<String, Double>> priceSnapshot = new AtomicReference<>(Collections.emptyMap());
    
    // 交易计数器：分离本地和远程，以便审计
    private final AtomicLong localTradeCounter = new AtomicLong(0);
    private final AtomicLong remoteTradeCounter = new AtomicLong(0);
    
    private final AtomicReference<MacroSnapshot> metadataMirror = new AtomicReference<>(null);

    private final Arena engineArena = Arena.ofShared(); 
    private final MemorySegment globalPidState;
    
    private final ScheduledExecutorService scheduler;
    private long lastComputeTime = System.currentTimeMillis();

    // 配置参数
    private double defaultLambda;
    private double configTau;
    private double targetTradesPerUser;

    public MacroEngine(EcoBridge plugin) {
        this.plugin = plugin;
        
        // 1. [Lifecycle] 初始化并重置 Native 内存
        // 确保每次 new MacroEngine 时，底层 PID 状态都是干净的，避免继承脏数据
        this.globalPidState = engineArena.allocate(NativeBridge.Layouts.PID_STATE);
        if (NativeBridge.isLoaded()) {
            NativeBridge.resetPidState(globalPidState);
            LogUtil.debug("Native PID 状态已重置 (Addr: " + globalPidState.address() + ")");
        }

        this.scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("EcoBridge-Macro-Scheduler").factory()
        );
    }

    public void updateConfig(double lambda, double tau, double targetVelocity) {
        this.defaultLambda = lambda;
        this.configTau = tau;
        this.targetTradesPerUser = targetVelocity;
    }

    public void start() {
        // 重置时间戳，防止启动瞬间产生巨大的 dt
        this.lastComputeTime = System.currentTimeMillis();
        
        Bukkit.getScheduler().runTaskTimer(plugin, this::syncMetadataFromMain, 20L, 100L);
        scheduler.scheduleAtFixedRate(this::runCycle, 2, 2, TimeUnit.SECONDS);
        
        LogUtil.info("宏观演算引擎已启动 (PID Managed)");
    }

    public void shutdown() {
        LogUtil.info("正在关闭宏观演算引擎...");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (engineArena.scope().isAlive()) {
            engineArena.close();
        }
    }

    /**
     * [新增接口] 注入远程交易量
     * 由 PricingManager 在收到 Redis 消息时调用
     */
    public void injectRemoteVolume(double amount) {
        // 将金额转换为“交易次数”或“交易量单位”，这里假设直接累加绝对值
        // 注意：这里需要与 incrementTradeCounter 的逻辑保持单位一致
        // 如果 globalTradeCounter 统计的是“次数”，这里应 +1
        // 如果统计的是“金额”，这里应 +amount
        // 根据上下文，原代码 incrementTradeCounter 是“次数” (Counter)，
        // 但这里我们可能需要更精细的控制。假设 targetTradesPerUser 是基于“次数”的。
        // 如果是基于金额，请修改为 addAndGet((long) amount)
        remoteTradeCounter.incrementAndGet(); 
    }

    public void incrementTradeCounter() {
        localTradeCounter.incrementAndGet();
    }

    private void syncMetadataFromMain() {
        int online = Bukkit.getOnlinePlayers().size();
        FileConfiguration itemsConfig = ItemConfigManager.get();
        if (itemsConfig == null) return;

        ConfigurationSection section = itemsConfig.getConfigurationSection("item-settings");
        List<PriceComputeEngine.ItemMeta> items = new ArrayList<>();
        
        if (section != null) {
            for (String shopKey : section.getKeys(false)) {
                ConfigurationSection shopSection = section.getConfigurationSection(shopKey);
                if (shopSection == null) continue;

                for (String productId : shopSection.getKeys(false)) {
                    String uniqueKey = shopKey + "." + productId; 
                    double basePrice = shopSection.getDouble(productId + ".base-price", 100.0);
                    double lambda = shopSection.getDouble(productId + ".lambda", defaultLambda);
                    
                    items.add(new PriceComputeEngine.ItemMeta(
                        uniqueKey, shopKey, productId, basePrice, lambda
                    ));
                }
            }
        }
        metadataMirror.set(new MacroSnapshot(online, items));
    }

    public Map<String, Double> getCurrentSnapshot() {
        return priceSnapshot.get();
    }

    public MemorySegment getGlobalPidState() {
        return globalPidState;
    }

    private void runCycle() {
        if (!plugin.isEnabled() || !NativeBridge.isLoaded()) return;

        MacroSnapshot snapshot = metadataMirror.get();
        if (snapshot == null) return;

        try {
            performCalculation(snapshot);
        } catch (Exception e) {
            LogUtil.error("MacroEngine: 异步演算异常", e);
        }
    }

    private void performCalculation(MacroSnapshot snapshot) {
        long now = System.currentTimeMillis();
        double dt = (now - lastComputeTime) / 1000.0;
        lastComputeTime = now;

        // [Safety] 异常时间步长检查
        // 如果服务器卡顿或暂停，dt 可能非常大，会导致 PID 积分项爆炸
        // 强制将 dt 限制在合理范围内 (例如 0.1s ~ 5.0s)
        if (dt < 0.1 || dt > 5.0) {
            // LogUtil.debug("MacroEngine: 异常时间步长 " + dt + "s，已重置为 2.0s");
            dt = 2.0; 
        }

        EconomyManager eco = EconomyManager.getInstance();
        if (eco == null) return;

        // [Fix] 合并本地与远程热度
        long localTrades = localTradeCounter.getAndSet(0);
        long remoteTrades = remoteTradeCounter.getAndSet(0);
        long totalTrades = localTrades + remoteTrades;

        double currentHeat = totalTrades / dt; // 交易频率 (Trades per Second)
        double targetHeat = Math.max(0.1, snapshot.onlinePlayers() * targetTradesPerUser);

        // 计算宏观 PID 调整系数
        double macroAdjustment = NativeBridge.computePidAdjustment(
                globalPidState, targetHeat, currentHeat, dt, eco.getInflationRate(), currentHeat
        );

        // 调用价格计算引擎
        Map<String, Double> nextPrices = PriceComputeEngine.computeSnapshot(
                plugin, 
                configTau, 
                defaultLambda * macroAdjustment, 
                snapshot.items                  
        );

        if (nextPrices != null && !nextPrices.isEmpty()) {
            priceSnapshot.set(Map.copyOf(nextPrices));
        }
    }

    private record MacroSnapshot(
        int onlinePlayers, 
        List<PriceComputeEngine.ItemMeta> items
    ) {}
}
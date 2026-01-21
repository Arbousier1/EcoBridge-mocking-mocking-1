package top.ellan.ecobridge.application.service;

import org.bukkit.Bukkit;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.infrastructure.ffi.bridge.NativeBridge;
import top.ellan.ecobridge.util.LogUtil;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 宏观经济管理器 (EconomyManager v1.6.0 - Precision & Micros Fixed)
 * <p>
 * 核心优化：
 * 1. [Precision] 全量采用 i64 Micros (10^-6) 存储核心经济指标，消除浮点累积误差。
 * 2. [Concurrency] 使用 LongAdder 代替 DoubleAdder 提升超高频交易下的累加性能。
 * 3. [FFI Alignment] 与 Rust 核心通信时进行动态缩放，确保算法输入的单位一致性。
 */
public class EconomyManager {

    private static EconomyManager instance;
    private final EcoBridge plugin;

    // 定点数转换常量 (1.0 = 1,000,000 Micros)
    private static final double PRECISION_SCALE = 1_000_000.0;

    // --- 核心经济指标 ---
    private volatile double inflationRate = 0.0;
    private volatile double marketHeat = 0.0;
    private volatile double ecoSaturation = 0.0;

    // --- 采样与状态累加器 (已由 DoubleAdder 重构为 LongAdder Micros) ---
    private final LongAdder circulationHeat = new LongAdder();      // 长期累积热度 (Micros)
    private final LongAdder tradeVolumeAccumulator = new LongAdder(); // 短期交易脉冲 (Micros)
    private final AtomicLong m1MoneySupplyMicros = new AtomicLong(0); // 货币发行总量 (Micros)
    
    private final AtomicLong lastVolatileTimestamp = new AtomicLong(System.currentTimeMillis());
    private long lastMacroUpdateTime = System.currentTimeMillis();

    // --- 算法配置参数 (保持为 double 以方便配置读取与数学运算) ---
    private double volatilityThreshold; // 波动触发阈值
    private double decayRate;           // 热度自然衰减率
    private double capacityPerUser;     // 单用户承载力

    private final ScheduledExecutorService economicScheduler;
    private final ReentrantLock configLock = new ReentrantLock();

    private EconomyManager(EcoBridge plugin) {
        this.plugin = plugin;
        this.economicScheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("EcoBridge-Economy-Worker").factory()
        );
        
        loadState();
        startEconomicTasks();
        startMacroAnalyticsTask();
    }

    public static void init(EcoBridge plugin) {
        instance = new EconomyManager(plugin);
    }

    public static EconomyManager getInstance() {
        return instance;
    }

    // =================================================================================
    // SECTION: 核心业务逻辑 (API)
    // =================================================================================

    public void loadState() {
        var config = plugin.getConfig();
        
        // 读取配置并转换为 Micros
        double m1Double = config.getDouble("economy.m1-supply", 10_000_000.0);
        this.m1MoneySupplyMicros.set((long) (m1Double * PRECISION_SCALE));
        
        this.volatilityThreshold = config.getDouble("economy.volatility-threshold", 50_000.0);
        this.decayRate = config.getDouble("economy.daily-decay-rate", 0.05);
        this.capacityPerUser = config.getDouble("economy.macro.capacity-per-user", 5000.0);

        double savedHeat = config.getDouble("internal.economy-heat", 0.0);
        circulationHeat.reset();
        circulationHeat.add((long) (savedHeat * PRECISION_SCALE));

        // 初始化基础指标
        this.marketHeat = savedHeat / 100.0; 

        LogUtil.info("EconomyManager 精度重构完成: M1=" + m1Double + " (Micros: " + m1MoneySupplyMicros.get() + ")");
    }

    /**
     * 处理实时交易脉冲
     * @param amount 交易额 (原始 double)
     * @param isMarketActivity 是否为市场行为
     */
    public void onTransaction(double amount, boolean isMarketActivity) {
        // 1. 转换为 Micros
        long amountMicros = (long) (Math.abs(amount) * PRECISION_SCALE);
        
        if (!isMarketActivity) {
            // 影响 M1 总量 (处理正负，用于货币增发/回收)
            long change = (long) (amount * PRECISION_SCALE);
            m1MoneySupplyMicros.addAndGet(change);
            return;
        }

        // 2. 注入短期脉冲累加器
        tradeVolumeAccumulator.add(amountMicros);
        
        // 3. 注入长期累积热度
        circulationHeat.add(amountMicros);

        // 4. 波动监测
        if (Math.abs(amount) >= volatilityThreshold) {
            lastVolatileTimestamp.set(System.currentTimeMillis());
        }
    }

    public void recordTradeVolume(double amount) {
        onTransaction(amount, true);
    }

    // =================================================================================
    // SECTION: 宏观画像演算 (The Brain)
    // =================================================================================

    private void startMacroAnalyticsTask() {
        economicScheduler.scheduleAtFixedRate(() -> {
            try {
                long now = System.currentTimeMillis();
                double dt = (now - lastMacroUpdateTime) / 1000.0;
                if (dt < 0.1) return;

                // A. 计算财富流速 (Market Heat)
                // 从 LongAdder 获取这一秒的 Micros 总量并重置
                long currentWindowMicros = tradeVolumeAccumulator.sumThenReset();
                double currentWindowDouble = currentWindowMicros / PRECISION_SCALE;
                this.marketHeat = currentWindowDouble / dt;

                // B. 计算生态饱和度
                int online = Bukkit.getOnlinePlayers().size();
                double totalCapacity = Math.max(1, online) * capacityPerUser;
                this.ecoSaturation = Math.min(1.0, marketHeat / totalCapacity);

                // C. 计算实时通胀率 (FFI 调用)
                if (NativeBridge.isLoaded()) {
                    // 传递给 Rust 时缩放回 double，Rust 内部进行复杂指数运算
                    double m1Double = m1MoneySupplyMicros.get() / PRECISION_SCALE;
                    this.inflationRate = NativeBridge.calcInflation(marketHeat, m1Double);
                }

                lastMacroUpdateTime = now;
            } catch (Exception e) {
                LogUtil.warn("宏观画像高精度演算任务异常: " + e.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void startEconomicTasks() {
        // 每 30 分钟执行一次热度衰减
        economicScheduler.scheduleAtFixedRate(this::runEconomicDecay, 30, 30, TimeUnit.MINUTES);
    }

    private void runEconomicDecay() {
        if (!NativeBridge.isLoaded()) return;

        double currentTotal = circulationHeat.sum() / PRECISION_SCALE;
        double reductionDouble = NativeBridge.calcDecay(currentTotal, decayRate);

        if (Math.abs(reductionDouble) > 0.01) {
            // 将扣减额转换回 Micros 进行扣除
            long reductionMicros = (long) (reductionDouble * PRECISION_SCALE);
            circulationHeat.add(-reductionMicros);
            
            if (reductionDouble > 100.0) saveState();
        }
    }

    public void saveState() {
        double currentTotal = circulationHeat.sum() / PRECISION_SCALE;
        plugin.getVirtualExecutor().execute(() -> {
            configLock.lock();
            try {
                plugin.getConfig().set("internal.economy-heat", currentTotal);
                plugin.saveConfig();
            } finally {
                configLock.unlock();
            }
        });
    }

    // =================================================================================
    // SECTION: Getters
    // =================================================================================

    public double getMarketHeat() { return this.marketHeat; }
    public double getEcoSaturation() { return this.ecoSaturation; }
    public double getInflationRate() { return this.inflationRate; }
    
    // 获取当前的货币总量 (double 版本供展示)
    public double getM1Supply() {
        return m1MoneySupplyMicros.get() / PRECISION_SCALE;
    }

    public double getMarketStability() {
        if (!NativeBridge.isLoaded()) return 1.0;
        return NativeBridge.calcStability(lastVolatileTimestamp.get(), System.currentTimeMillis());
    }

    public void shutdown() {
        economicScheduler.shutdown();
        try {
            if (!economicScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                economicScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            economicScheduler.shutdownNow();
        }
        saveState();
    }
}
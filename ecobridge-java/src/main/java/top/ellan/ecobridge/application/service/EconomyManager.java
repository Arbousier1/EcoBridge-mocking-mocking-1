package top.ellan.ecobridge.application.service;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration; // [新增]
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.infrastructure.ffi.bridge.NativeBridge;
import top.ellan.ecobridge.util.LogUtil;

import java.io.File; // [新增]
import java.io.IOException; // [新增]
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 宏观经济管理器 (EconomyManager v1.6.1 - Data Separation)
 * <p>
 * 核心优化：
 * 1. [IO Safety] 将动态热度数据(economy-heat)分离至 data.yml，防止 config.yml 注释丢失。
 * 2. [Precision] 全量采用 i64 Micros (10^-6) 存储核心经济指标。
 */
public class EconomyManager {

    private static EconomyManager instance;
    private final EcoBridge plugin;

    // [新增] 独立数据文件，用于存储程序运行时产生的动态数据
    private final File dataFile;
    private YamlConfiguration dataConfig;

    // 定点数转换常量 (1.0 = 1,000,000 Micros)
    private static final double PRECISION_SCALE = 1_000_000.0;

    // --- 核心经济指标 ---
    private volatile double inflationRate = 0.0;
    private volatile double marketHeat = 0.0;
    private volatile double ecoSaturation = 0.0;

    // --- 采样与状态累加器 ---
    private final LongAdder circulationHeat = new LongAdder();      // 长期累积热度 (Micros)
    private final LongAdder tradeVolumeAccumulator = new LongAdder(); // 短期交易脉冲 (Micros)
    private final AtomicLong m1MoneySupplyMicros = new AtomicLong(0); // 货币发行总量 (Micros)
    
    private final AtomicLong lastVolatileTimestamp = new AtomicLong(System.currentTimeMillis());
    private long lastMacroUpdateTime = System.currentTimeMillis();

    // --- 算法配置参数 ---
    private double volatilityThreshold; // 波动触发阈值
    private double decayRate;           // 热度自然衰减率
    private double capacityPerUser;     // 单用户承载力

    private final ScheduledExecutorService economicScheduler;
    private final ReentrantLock configLock = new ReentrantLock();

    private EconomyManager(EcoBridge plugin) {
        this.plugin = plugin;
        
        // [新增] 初始化 data.yml 文件句柄
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");

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
        
        // [新增] 加载 data.yml，如果文件不存在则创建
        if (!dataFile.exists()) {
            try {
                if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                LogUtil.error("无法创建 data.yml 数据文件", e);
            }
        }
        this.dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        
        // 1. 读取静态配置 (从 config.yml 读取，只读)
        double m1Double = config.getDouble("economy.m1-supply", 10_000_000.0);
        this.m1MoneySupplyMicros.set((long) (m1Double * PRECISION_SCALE));
        
        this.volatilityThreshold = config.getDouble("economy.volatility-threshold", 50_000.0);
        this.decayRate = config.getDouble("economy.daily-decay-rate", 0.05);
        this.capacityPerUser = config.getDouble("economy.macro.capacity-per-user", 5000.0);

        // 2. 读取动态热度 (优先 data.yml，兼容 config.yml)
        // 逻辑：如果 data.yml 里没有记录（首次更新），则尝试读取 config.yml 里的旧数据，平滑迁移
        double savedHeat = dataConfig.getDouble("internal.economy-heat", 
            config.getDouble("internal.economy-heat", 0.0));
            
        circulationHeat.reset();
        circulationHeat.add((long) (savedHeat * PRECISION_SCALE));

        // 初始化基础指标
        this.marketHeat = savedHeat / 100.0; 

        LogUtil.info("EconomyManager 状态加载完成 (M1=" + m1Double + ", Heat=" + savedHeat + ")");
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
                long currentWindowMicros = tradeVolumeAccumulator.sumThenReset();
                double currentWindowDouble = currentWindowMicros / PRECISION_SCALE;
                this.marketHeat = currentWindowDouble / dt;

                // B. 计算生态饱和度
                int online = Bukkit.getOnlinePlayers().size();
                double totalCapacity = Math.max(1, online) * capacityPerUser;
                this.ecoSaturation = Math.min(1.0, marketHeat / totalCapacity);

                // C. 计算实时通胀率 (FFI 调用)
                if (NativeBridge.isLoaded()) {
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
                // [修改] 将热度写入 dataConfig 对象
                dataConfig.set("internal.economy-heat", currentTotal);
                
                // [修改] 保存到 data.yml，不再调用 plugin.saveConfig()
                try {
                    dataConfig.save(dataFile);
                } catch (IOException e) {
                    LogUtil.error("无法保存 data.yml 经济数据", e);
                }
                
                // 彻底移除对 config.yml 的写操作
                // plugin.saveConfig(); 
                
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
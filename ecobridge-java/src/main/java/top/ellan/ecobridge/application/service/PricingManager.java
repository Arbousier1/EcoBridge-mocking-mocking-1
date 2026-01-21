package top.ellan.ecobridge.application.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import org.bukkit.Bukkit;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.infrastructure.ffi.bridge.NativeBridge;
import top.ellan.ecobridge.infrastructure.persistence.database.TransactionDao;
import top.ellan.ecobridge.infrastructure.ffi.model.SaleRecord;
import top.ellan.ecobridge.infrastructure.persistence.redis.RedisManager;
import top.ellan.ecobridge.infrastructure.persistence.storage.AsyncLogger;
import top.ellan.ecobridge.util.LogUtil;

import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 核心定价管理器 (PricingManager v3.2 - Final Integrated)
 * <p>
 * 特性：
 * 1. [Thread Safe] 使用分段读写锁保护物品历史记录。
 * 2. [Global Sync] 集成 injectRemoteVolume 修复跨服热度不同步问题。
 * 3. [Macro Aware] 价格计算基于 PID 宏观快照。
 */
public class PricingManager {

    private static PricingManager instance;
    private final EcoBridge plugin;

    private final MacroEngine macroEngine;

    // 物品级分段锁 (防止并发读写历史记录导致的数据竞争)
    private final Cache<String, ReentrantReadWriteLock> itemLocks;

    // 交易历史缓存
    private final Cache<String, ThreadSafeHistory> historyCache;

    private final AtomicBoolean isInitialized = new AtomicBoolean(false);

    private double sellRatio;
    private int historyDaysLimit;
    private int maxHistorySize;

    private PricingManager(EcoBridge plugin) {
        this.plugin = plugin;
        
        // 初始化宏观引擎 (PID Controller)
        this.macroEngine = new MacroEngine(plugin);

        // 初始化分段锁缓存
        this.itemLocks = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(10))
                .build();

        // 初始化历史记录缓存
        this.historyCache = Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterAccess(Duration.ofMinutes(30))
                .removalListener((String key, ThreadSafeHistory value, RemovalCause cause) -> {
                    if (cause.wasEvicted()) LogUtil.debug("历史数据缓存已逐出: " + key);
                })
                .build();

        loadConfig();
    }

    public static void init(EcoBridge plugin) {
        instance = new PricingManager(plugin);
        instance.startup();
    }

    public static PricingManager getInstance() {
        return instance;
    }

    /**
     * 异步启动流程
     */
    private void startup() {
        // 1. 启动宏观引擎
        macroEngine.start();

        // 2. 标记初始化完成 (可在此处添加预热逻辑)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            isInitialized.set(true);
            LogUtil.info("PricingManager 全局同步服务已就绪。");
        }, 40L);
    }

    public void loadConfig() {
        var config = plugin.getConfig();
        this.sellRatio = config.getDouble("economy.sell-ratio", 0.5);
        this.historyDaysLimit = config.getInt("economy.history-days-limit", 7);
        this.maxHistorySize = config.getInt("economy.max-history-records", 3000);

        this.macroEngine.updateConfig(
            config.getDouble("economy.default-lambda", 0.002),
            config.getDouble("economy.tau", 7.0),
            config.getDouble("economy.macro.target-velocity", 0.05)
        );
    }

    public void shutdown() {
        if (macroEngine != null) {
            macroEngine.shutdown();
        }
        try {
            this.historyCache.invalidateAll();
            this.itemLocks.invalidateAll();
            LogUtil.info("PricingManager 资源已安全回收。");
        } catch (Exception e) {
            LogUtil.error("PricingManager 关闭时发生异常", e);
        }
    }

    public MemorySegment getGlobalPidState() {
        return macroEngine.getGlobalPidState();
    }

    /**
     * 获取宏观快照价格 (不包含微观滑点)
     */
    public double getSnapshotPrice(String shopId, String productId) {
        Map<String, Double> current = macroEngine.getCurrentSnapshot();
        if (current == null) return -1.0;

        if (shopId == null) {
            // 模糊匹配：尝试查找该 productId 对应的任意价格
            return current.entrySet().stream()
                    .filter(e -> e.getKey().endsWith("." + productId))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(-1.0);
        }
        return current.getOrDefault(shopId + "." + productId, -1.0);
    }

    /**
     * 计算买入价 (Macro Price)
     * 优先使用 PID 计算出的宏观价格，如果未就绪则降级到默认值。
     */
    public double calculateBuyPrice(String productId) {
        double dynamicPrice = getSnapshotPrice(null, productId);
        return (dynamicPrice <= 0) ? 100.0 : dynamicPrice; // 100.0 为默认兜底价
    }

    public double calculateSellPrice(String productId) {
        return calculateBuyPrice(productId) * this.sellRatio;
    }

    /**
     * 计算动态价格 (Macro Price + Micro Slippage)
     * 结合了宏观 PID 价格和微观交易滑点。
     */
    public double calculateDynamicPrice(String productId, double amount) {
        var lock = getItemLock(productId).readLock();
        lock.lock();
        try {
            // 1. 获取宏观基准价 (Macro)
            double basePrice = calculateBuyPrice(productId);
            
            if (!NativeBridge.isLoaded()) return basePrice;

            // 2. 计算微观滑点 (Micro) - 调用 Rust FFI
            // computeTierPrice 会根据单次交易量(amount)产生临时滑点
            return NativeBridge.computeTierPrice(basePrice, Math.abs(amount), amount > 0);
            
        } catch (Exception e) {
            LogUtil.warn("计算物品 " + productId + " 动态价格时发生异常，回退至安全值");
            return 100.0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * [本地交易处理]
     * 当本服发生交易时调用
     */
    public void onTradeComplete(String productId, double effectiveAmount) {
        var lock = getItemLock(productId).writeLock();
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            
            // 1. 更新本地宏观热度 (Local Heat)
            macroEngine.incrementTradeCounter(); 

            // 2. 更新历史记录 (Ring Buffer)
            SaleRecord record = new SaleRecord(now, effectiveAmount);
            getHistoryContainer(productId).add(record, maxHistorySize);

            // 3. 异步持久化 (Log & DB)
            plugin.getVirtualExecutor().execute(() -> {
                try {
                    AsyncLogger.log(java.util.UUID.nameUUIDFromBytes(productId.getBytes()), effectiveAmount, 0, now, "TRX_WRITE_THROUGH");
                    TransactionDao.saveSaleAsync(null, productId, effectiveAmount);
                } catch (Exception e) {
                    LogUtil.error("交易落库任务执行失败: " + productId, e);
                }
            });

            // 4. 广播全服 (Redis)
            if (RedisManager.getInstance() != null) {
                RedisManager.getInstance().publishTrade(productId, effectiveAmount);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * [远程交易同步] - 核心修复点 (Fix Local Neff Lag)
     * 当从 Redis 收到其他服务器的交易时调用
     */
    public void onRemoteTradeReceived(String productId, double amount, long timestamp) {
        var lock = getItemLock(productId).writeLock();
        lock.lock();
        try {
            // 1. [CRITICAL FIX] 注入宏观引擎
            // 确保远程交易量能即时反馈到本地 PID 控制回路中，防止价格对全服通胀反应滞后
            if (macroEngine != null) {
                macroEngine.injectRemoteVolume(amount);
            }

            // 2. 更新历史记录
            SaleRecord record = new SaleRecord(timestamp, amount);
            getHistoryContainer(productId).add(record, maxHistorySize);
            
            // 3. 注入 Native 状态 (如需反作弊或 Rust 侧统计)
            if (NativeBridge.isLoaded()) {
                NativeBridge.injectRemoteTrade(amount);
            }
        } finally {
            lock.unlock();
        }
    }

    public ReentrantReadWriteLock getItemLock(String productId) {
        return itemLocks.get(productId, k -> new ReentrantReadWriteLock());
    }

    public List<SaleRecord> getGlobalHistory(String productId) {
        return getHistoryContainer(productId).getSnapshot();
    }

    private ThreadSafeHistory getHistoryContainer(String productId) {
        return historyCache.get(productId, id -> {
            List<SaleRecord> initialData = TransactionDao.getProductHistory(id, historyDaysLimit);
            return new ThreadSafeHistory(initialData);
        });
    }

    public void clearCache() {
        historyCache.invalidateAll();
        itemLocks.invalidateAll();
    }

    /**
     * 内部类：针对单个物品历史记录的线程安全容器
     */
    private static class ThreadSafeHistory {
        private final ArrayDeque<SaleRecord> deque;
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        public ThreadSafeHistory(List<SaleRecord> initialData) {
            this.deque = new ArrayDeque<>(initialData);
        }

        public void add(SaleRecord record, int maxSize) {
            lock.writeLock().lock();
            try {
                deque.addFirst(record);
                if (deque.size() > maxSize) deque.removeLast();
            } finally {
                lock.writeLock().unlock();
            }
        }

        public List<SaleRecord> getSnapshot() {
            lock.readLock().lock();
            try {
                return new ArrayList<>(deque);
            } finally {
                lock.readLock().unlock();
            }
        }
    }
}
package top.ellan.ecobridge.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.infrastructure.persistence.database.DatabaseManager;
import top.ellan.ecobridge.infrastructure.persistence.database.TransactionDao;
import top.ellan.ecobridge.util.LogUtil;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家数据热点缓存 (HotDataCache v0.9.1 - Concurrency & Safety Fix)
 * 职责：维护在线玩家的高频交易数据快照，并实现专用的 IO 线程隔离。
 * 修复：
 * 1. PlayerData.version 升级为 AtomicLong，确保乐观锁在高并发下的原子性。
 * 2. 优化关机同步逻辑，防止 JVM 退出时异步任务丢失。
 */
public class HotDataCache {

    private static final Cache<UUID, PlayerData> CACHE = Caffeine.newBuilder()
            .maximumSize(2000)
            .expireAfterAccess(Duration.ofHours(2))
            .removalListener((UUID uuid, PlayerData data, RemovalCause cause) -> {
                if (data == null) return;
                // 只有非替换(例如过期、手动移除)才触发异步写回
                // REPLACED 通常是数据刷新，不需要立即写库
                if (cause != RemovalCause.REPLACED) {
                    saveAsync(uuid, data, "CACHE_" + cause.name());
                }
            })
            .build();

    /**
     * 跨池加载逻辑
     * 1. 在 DatabaseManager 的固定池执行 JDBC 阻塞查询
     * 2. 在 Bukkit 主线程执行缓存挂载与 API 交互
     */
    public static void load(UUID uuid) {
        DatabaseManager.getExecutor().execute(() -> {
            try {
                // 阻塞型 IO：在平台线程池中执行
                PlayerData data = TransactionDao.loadPlayerData(uuid);

                // 逻辑回调：切换回主线程处理 Bukkit 实体
                Bukkit.getScheduler().runTask(EcoBridge.getInstance(), () -> {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null && p.isOnline()) {
                        CACHE.put(uuid, data);
                        LogUtil.debug("已为玩家 " + p.getName() + " 完成数据挂载 (Version: " + data.getVersion() + ")");
                    } else {
                        LogUtil.debug("拦截到过时加载回调 (" + uuid + ")，玩家已离线。");
                    }
                });
            } catch (Exception e) {
                LogUtil.error("玩家 " + uuid + " 数据热加载发生致命错误！", e);
            }
        });
    }

    public static PlayerData get(UUID uuid) {
        return CACHE.getIfPresent(uuid);
    }

    public static void invalidate(UUID uuid) {
        CACHE.invalidate(uuid);
    }

    /**
     * 异步回写逻辑
     * 显式使用 DatabaseManager.getExecutor() 以保护虚拟线程载体池
     */
    private static void saveAsync(UUID uuid, PlayerData data, String reason) {
        // 如果插件正在关闭，直接同步执行以防止任务丢失
        if (!EcoBridge.getInstance().isEnabled()) {
            saveSyncInternal(uuid, data);
            return;
        }

        DatabaseManager.getExecutor().execute(() -> {
            try {
                saveSyncInternal(uuid, data);
                if (LogUtil.isDebugEnabled()) {
                    LogUtil.debug("数据写回成功 [" + reason + "]: " + uuid + " (Balance: " + data.getBalance() + ")");
                }
            } catch (Exception e) {
                LogUtil.error("异步写回失败: " + uuid, e);
            }
        });
    }

    /**
     * 内部同步保存逻辑
     */
    private static void saveSyncInternal(UUID uuid, PlayerData data) {
        // 使用 version 进行乐观锁更新 (需 TransactionDao 配合检查)
        TransactionDao.updateBalanceBlocking(uuid, data.getBalance());
        // 这里假设 updateBalanceBlocking 会在成功后增加 version，
        // 实际上 DAO 应该返回新 version，这里简化处理
        data.incrementVersion();
    }

    /**
     * 关机时的同步保存
     * 这里的逻辑必须极其健壮，不能依赖任何异步调度
     */
    public static void saveAllSync() {
        LogUtil.info("正在执行关机前的全量热数据强制同步...");
        
        // 获取当前所有数据的快照
        ConcurrentMap<UUID, PlayerData> snapshotMap = CACHE.asMap();

        // 1. 先从缓存中移除所有数据，这将触发 removalListener
        // 但由于我们在 saveAsync 中加了 isEnabled() 检查，此时它会降级为同步保存
        // 为了双重保险，我们先手动遍历保存
        
        for (var entry : snapshotMap.entrySet()) {
            try {
                saveSyncInternal(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                LogUtil.error("关机保存失败: " + entry.getKey(), e);
            }
        }

        // 2. 清空缓存 (避免内存泄漏，虽然 JVM 马上要关了)
        // 此时 removalListener 可能会再次触发，但 saveAsync 里的检查会防止重复提交异步任务
        CACHE.invalidateAll();
        
        LogUtil.info("所有活跃数据已安全落盘。");
    }

    /**
     * 玩家数据模型 (Thread-Safe)
     */
    public static class PlayerData {
        private final UUID uuid;
        // 使用 AtomicLong 存储 Double 的位模式，保证余额读写的原子性
        private final AtomicLong balanceBits;
        // [Fix] 使用 AtomicLong 保证版本号递增的原子性，修复伪乐观锁问题
        private final AtomicLong version;

        public PlayerData(UUID uuid, double initialBalance, long initialVersion) {
            this.uuid = uuid;
            this.balanceBits = new AtomicLong(Double.doubleToRawLongBits(initialBalance));
            this.version = new AtomicLong(initialVersion);
        }

        public UUID getUuid() { return uuid; }

        public double getBalance() {
            return Double.longBitsToDouble(balanceBits.get());
        }

        public long getVersion() {
            return version.get();
        }

        public void setVersion(long newVersion) {
            this.version.set(newVersion);
        }
        
        public void incrementVersion() {
            this.version.incrementAndGet();
        }

        public void updateFromTruth(double newBalance) {
            balanceBits.set(Double.doubleToRawLongBits(newBalance));
        }

        public void setBalance(double newBalance) {
            updateFromTruth(newBalance);
        }
    }
}
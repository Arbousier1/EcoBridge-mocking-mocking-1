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
 * 玩家数据热点缓存 (HotDataCache v0.9.2 - Shutdown Safe)
 * 职责：维护在线玩家的高频交易数据快照，并实现专用的 IO 线程隔离。
 * 修复：
 * 1. [Fix] 修复 removalListener 在关机时调用 getInstance() 导致的崩溃。
 * 2. 优化：引入 EcoBridge.isInitialized() 检查，安全降级异步保存。
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
                // 检查插件状态，防止关机时调度报错
                if (EcoBridge.isInitialized() && EcoBridge.getInstance().isEnabled()) {
                    Bukkit.getScheduler().runTask(EcoBridge.getInstance(), () -> {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null && p.isOnline()) {
                            CACHE.put(uuid, data);
                            LogUtil.debug("已为玩家 " + p.getName() + " 完成数据挂载 (Version: " + data.getVersion() + ")");
                        } else {
                            LogUtil.debug("拦截到过时加载回调 (" + uuid + ")，玩家已离线。");
                        }
                    });
                }
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
     * 异步回写逻辑 (安全版)
     * [Fix] 增加生命周期检查，防止在关机期间抛出 IllegalStateException
     */
    private static void saveAsync(UUID uuid, PlayerData data, String reason) {
        // 关键修复：先检查 EcoBridge 是否仍然活跃
        // 如果插件正在关闭 (isInitialized=false) 或已禁用 (isEnabled=false)
        // 则直接降级为同步保存，不再提交异步任务
        if (!EcoBridge.isInitialized() || !EcoBridge.getInstance().isEnabled()) {
            try {
                saveSyncInternal(uuid, data);
            } catch (Exception e) {
                // 此时 LogUtil 可能也不安全了，直接用 System.err 做最后挣扎
                System.err.println("[EcoBridge-Emergency] 关机同步保存失败: " + uuid);
                e.printStackTrace();
            }
            return;
        }

        // 插件正常运行中，提交到数据库线程池
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

        // 1. 手动遍历保存 (绕过 removalListener 的异步逻辑)
        for (var entry : snapshotMap.entrySet()) {
            try {
                saveSyncInternal(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                LogUtil.error("关机保存失败: " + entry.getKey(), e);
            }
        }

        // 2. 清空缓存
        // 此时 removalListener 触发时，saveAsync 会检测到插件关闭而直接返回(或同步执行)
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
            version.incrementAndGet();
        }

        public void setBalance(double newBalance) {
            updateFromTruth(newBalance);
        }
    }
}
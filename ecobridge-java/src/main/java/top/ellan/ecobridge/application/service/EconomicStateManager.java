package top.ellan.ecobridge.application.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.infrastructure.persistence.database.TransactionDao;
import top.ellan.ecobridge.util.LogUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 经济状态管理器 (v0.9.6 - Thread-Safe & Atomic)
 * 职责：
 * 1. 采用原子操作分析市场情绪，防止并发环境下的重复广播。
 * 2. 协调交易事件对定价引擎的反馈。
 * 3. 在极端行情下安全地触发配置文件持久化。
 */
public class EconomicStateManager {

    private static volatile EconomicStateManager instance;
    private final Map<String, MarketPhase> lastKnownPhases = new ConcurrentHashMap<>();

    // 锚点值缓存：线程安全
    private final Cache<String, Double> anchorCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(1000)
            .build();

    public enum MarketPhase {
        STABLE,      // 稳定
        SATURATED,   // 饱和
        EMERGENCY,   // 紧急
        HEALING      // 恢复
    }

    private EconomicStateManager() {}

    public static void init(EcoBridge plugin) {
        if (instance == null) {
            synchronized (EconomicStateManager.class) {
                if (instance == null) {
                    instance = new EconomicStateManager();
                }
            }
        }
    }

    public static EconomicStateManager getInstance() {
        return instance;
    }

    public void recordPurchase(Player player, String productId, int amount) {
        LogUtil.debug("记录购买行为: " + player.getName() + " -> " + productId + " x" + amount);

        if (PricingManager.getInstance() != null) {
            // 购买行为减少供应，传入负值
            PricingManager.getInstance().onTradeComplete(productId, -amount);
        }

        Bukkit.getScheduler().runTaskAsynchronously(EcoBridge.getInstance(), () -> {
            analyzeMarketAndNotify(productId, (double) -amount);
        });
    }

    public void recordSale(Player player, String productId, int amount) {
        LogUtil.debug("记录出售行为: " + player.getName() + " -> " + productId + " x" + amount);

        if (PricingManager.getInstance() != null) {
            PricingManager.getInstance().onTradeComplete(productId, amount);
        }
        
        Bukkit.getScheduler().runTaskAsynchronously(EcoBridge.getInstance(), () -> {
            analyzeMarketAndNotify(productId, (double) amount);
        });
    }

    public MarketPhase analyzeMarketAndNotify(String productId, double currentNeff) {
        Double anchorValue = anchorCache.get(productId, k -> TransactionDao.get7DayAverage(k));

        if (anchorValue == null || anchorValue <= 0) return MarketPhase.STABLE;

        double impactIndex = Math.abs(currentNeff) / anchorValue;
        
        // 获取当前最新相位以便进行逻辑判定（此处读操作不要求强一致性，只作为初筛）
        MarketPhase oldPhase = lastKnownPhases.getOrDefault(productId, MarketPhase.STABLE);
        MarketPhase targetPhase;

        if (impactIndex > 3.5) {
            targetPhase = MarketPhase.EMERGENCY;
        } else if (impactIndex > 1.8) {
            targetPhase = MarketPhase.SATURATED;
        } else if (oldPhase == MarketPhase.EMERGENCY && impactIndex < 1.5) {
            targetPhase = MarketPhase.HEALING;
        } else if (impactIndex < 1.2) {
            targetPhase = MarketPhase.STABLE;
        } else {
            targetPhase = oldPhase;
        }

        // 核心修复：原子性状态更新
        checkAndBroadcastAtomic(productId, targetPhase);
        return targetPhase;
    }

    /**
     * [修复] 采用原子操作确保状态切换只触发一次广播
     */
    private void checkAndBroadcastAtomic(String productId, MarketPhase newPhase) {
        lastKnownPhases.compute(productId, (id, currentPhase) -> {
            // 如果计算出的新相位与当前记录的相位不同，则执行切换逻辑
            if (currentPhase != newPhase) {
                executeBroadcast(id, newPhase);
                return newPhase;
            }
            return currentPhase;
        });
    }

    private void executeBroadcast(String productId, MarketPhase phase) {
        String msg = switch (phase) {
            case EMERGENCY -> "<red>⚖ [商会紧急干预] <white><id> <red>遭遇抛售狂潮！开启“价格保护”模式。";
            case SATURATED -> "<yellow>⚠ [市场警告] <white><id> <yellow>库存积压，收购价将下调。";
            case HEALING -> "<aqua>❈ [秩序恢复] <white><id> <aqua>市场正在回暖。";
            case STABLE -> "<green>✔ [贸易正常化] <white><id> <green>恢复自由贸易定价。";
        };

        // 同步回主线程广播，确保 API 安全
        Bukkit.getScheduler().runTask(EcoBridge.getInstance(), () -> {
            Bukkit.broadcast(EcoBridge.getMiniMessage().deserialize(
                msg.replace("<id>", productId)
            ));
        });

        // 紧急状态下的异步持久化
        if (phase == MarketPhase.EMERGENCY) {
            Bukkit.getScheduler().runTaskAsynchronously(EcoBridge.getInstance(), () -> {
                double currentPrice = PricingManager.getInstance().calculateBuyPrice(productId);
                if (currentPrice > 0) {
                    // ItemConfigManager 内部应自备锁来处理并发 save()
                    ItemConfigManager.updateItemBasePrice(productId, currentPrice);
                }
            });
        }
    }

    public double getBehavioralLambdaModifier(MarketPhase phase) {
        return switch (phase) {
            case EMERGENCY -> 0.35;
            case SATURATED -> 0.60;
            case HEALING -> 0.85;
            default -> 1.0;
        };
    }
}
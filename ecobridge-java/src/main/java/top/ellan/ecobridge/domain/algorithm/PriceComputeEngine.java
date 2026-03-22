package top.ellan.ecobridge.domain.algorithm;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import top.ellan.ecobridge.infrastructure.ffi.bridge.NativeBridge;
import top.ellan.ecobridge.infrastructure.ffi.bridge.NativeBridge.Layouts;
import top.ellan.ecobridge.infrastructure.ffi.bridge.NativeContextBuilder;
import top.ellan.ecobridge.infrastructure.persistence.database.TransactionDao;
import top.ellan.ecobridge.application.service.EconomyManager;
import top.ellan.ecobridge.application.service.ItemConfigManager; // 确保导入了新的管理器
import top.ellan.ecobridge.util.LogUtil;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 价格计算引擎 (PriceComputeEngine v2.4 - Micros Compatible)
 * 职责：
 * 1. 自动同步：接受外部传入的自动发现物品列表。
 * 2. 配置覆盖：正确应用 items.yml 中的个性化设置。
 * 3. 性能优化：使用 FFM SIMD 批处理计算价格。
 * 4. 动态响应：适配 Rust i64 微米级内核。
 */
public class PriceComputeEngine {

    // --- [集成部分：静态物品缓存] ---
    private static final List<ItemMeta> CACHED_ITEMS = new CopyOnWriteArrayList<>();
    
    /**
     * 更新全量缓存物品，供监听器使用
     */
    public static void updateAllItems(List<ItemMeta> items) {
        CACHED_ITEMS.clear();
        if (items != null) {
            CACHED_ITEMS.addAll(items);
        }
    }

    /**
     * 数据传输对象 (DTO)
     */
    public record ItemMeta(
            String uniqueKey,
            String shopId,
            String productId,
            double basePrice,
            double lambda,
            int index // 用于数组定位
    ) {
        public ItemMeta(String uniqueKey, String shopId, String productId, double basePrice, double lambda) {
            this(uniqueKey, shopId, productId, basePrice, lambda, -1);
        }
        
        public ItemMeta withIndex(int newIndex) {
            return new ItemMeta(uniqueKey, shopId, productId, basePrice, lambda, newIndex);
        }
    }

    /**
     * [入口方法] 计算当前时间步的所有价格 (全自动同步版)
     */
    public static Map<String, Double> computeSnapshot(Plugin plugin, double configTau, double macroLambda, List<ItemMeta> autoDiscoveredItems) {
        long startTime = System.nanoTime();
        Map<String, Double> resultMap = new HashMap<>();

        if (!NativeBridge.isLoaded()) {
            return resultMap;
        }

        // 1. 加载主配置文件 (用于读取系统级参数如 debug)
        FileConfiguration config = plugin.getConfig();
        
        // [修改点]：这里不再从 config.yml 读 item-settings，而是从独立文件管理器读取
        ConfigurationSection itemSettings = ItemConfigManager.get().getConfigurationSection("item-settings");

        // 2. 数据源选择
        List<ItemMeta> sourceItems = (autoDiscoveredItems == null || autoDiscoveredItems.isEmpty()) 
                                    ? CACHED_ITEMS 
                                    : autoDiscoveredItems;

        // 3. 合并物品列表 (自动同步 + 配置覆盖)
        List<ItemMeta> activeItems = mergeItems(sourceItems, itemSettings, macroLambda);
        if (activeItems.isEmpty()) return resultMap;

        int count = activeItems.size();
        long now = System.currentTimeMillis();

        // 4. IO 聚合
        Map<String, Double> histAvgMap = loadHistoryAverages(activeItems);

        double stability = EconomyManager.getInstance().getMarketStability();
        double activeVolatility = NativeBridge.computeVolatilityFromStability(stability);

        // 5. FFM 资源安全封装
        try (Arena arena = Arena.ofConfined()) {
            // 6. Per-item keyed calculation
            for (int i = 0; i < count; i++) {
                ItemMeta meta = activeItems.get(i);

                MemorySegment ctxSeg = arena.allocate(Layouts.TRADE_CONTEXT);
                MemorySegment cfgSeg = arena.allocate(Layouts.MARKET_CONFIG);

                NativeContextBuilder.fillGlobalContext(ctxSeg, now, 1.0);
                // Conversion math is delegated to Rust to keep numeric rules centralized.
                long basePriceMicros = NativeBridge.moneyToMicros(meta.basePrice());
                NativeBridge.VH_CTX_BASE_PRICE_MICROS.set(ctxSeg, 0L, basePriceMicros);

                ConfigurationSection itemConfig = null;
                if (itemSettings != null) {
                    itemConfig = itemSettings.getConfigurationSection(meta.shopId() + "." + meta.productId());
                }
                fillMarketConfigAtOffset(cfgSeg, 0L, itemConfig, config, meta.lambda(), activeVolatility);

                double histAvg = histAvgMap.getOrDefault(meta.uniqueKey(), meta.basePrice());
                double neff = NativeBridge.queryNeffForKey(now, configTau, meta.uniqueKey());
                double epsilon = NativeBridge.calculateEpsilon(ctxSeg, cfgSeg);
                double computedPrice = NativeBridge.computePriceBounded(
                    meta.basePrice(),
                    neff,
                    0.0,
                    meta.lambda(),
                    epsilon,
                    histAvg
                );

                resultMap.put(
                    meta.uniqueKey(),
                    (Double.isFinite(computedPrice) && computedPrice > 0) ? computedPrice : meta.basePrice()
                );
            }
            
            if (config.getBoolean("system.debug", false)) {
                double durationMs = (System.nanoTime() - startTime) / 1_000_000.0;
                LogUtil.debug(String.format("快照演算完成: %d 个商品, 波动因子: %.4f, 耗时: %.2fms", 
                    count, activeVolatility, durationMs));
            }

        } catch (Throwable e) {
            LogUtil.error("PriceComputeEngine: SIMD 批量计算失败", e);
        }

        return resultMap;
    }

    private static List<ItemMeta> mergeItems(List<ItemMeta> autoDiscovered, ConfigurationSection configSection, double defaultLambda) {
        if (autoDiscovered == null || autoDiscovered.isEmpty()) return new ArrayList<>();
        
        List<ItemMeta> merged = new ArrayList<>(autoDiscovered.size());
        int index = 0; 

        for (ItemMeta autoItem : autoDiscovered) {
            String shopId = autoItem.shopId();
            String prodId = autoItem.productId();
            
            double finalLambda = defaultLambda;
            double finalBasePrice = autoItem.basePrice();

            if (configSection != null) {
                String path = shopId + "." + prodId;
                if (configSection.contains(path)) {
                    finalLambda = configSection.getDouble(path + ".lambda", defaultLambda);
                    finalBasePrice = configSection.getDouble(path + ".base-price", autoItem.basePrice());
                }
            }

            ItemMeta finalMeta = new ItemMeta(
                autoItem.uniqueKey(),
                shopId,
                prodId,
                finalBasePrice,
                finalLambda,
                index++
            );

            merged.add(finalMeta);
        }
        return merged;
    }

    private static Map<String, Double> loadHistoryAverages(List<ItemMeta> items) {
        List<String> ids = items.stream().map(ItemMeta::uniqueKey).distinct().toList();
        return TransactionDao.get7DayAveragesBatch(ids);
    }

    private static void fillMarketConfigAtOffset(MemorySegment cfgBase, long offset, ConfigurationSection itemSec, FileConfiguration globalConfig, double currentLambda, double volatility) {
        NativeBridge.VH_CFG_LAMBDA.set(cfgBase, offset, currentLambda);
        NativeBridge.VH_CFG_VOLATILITY.set(cfgBase, offset, volatility);
        
        NativeBridge.VH_CFG_S_AMP.set(cfgBase, offset, globalConfig.getDouble("economy.environment.seasonal-amplitude", 0.15));
        NativeBridge.VH_CFG_W_MULT.set(cfgBase, offset, globalConfig.getDouble("economy.environment.weekend-multiplier", 1.2));
        NativeBridge.VH_CFG_N_PROT.set(cfgBase, offset, globalConfig.getDouble("economy.environment.newbie-protection", 0.2));

        if (itemSec != null) {
            NativeBridge.VH_CFG_W_SEASONAL.set(cfgBase, offset, itemSec.getDouble("weights.seasonal", 0.25));
            NativeBridge.VH_CFG_W_WEEKEND.set(cfgBase, offset, itemSec.getDouble("weights.weekend", 0.25));
            NativeBridge.VH_CFG_W_NEWBIE.set(cfgBase, offset, itemSec.getDouble("weights.newbie", 0.25));
            NativeBridge.VH_CFG_W_INFLATION.set(cfgBase, offset, itemSec.getDouble("weights.inflation", 0.25));
        } else {
            NativeBridge.VH_CFG_W_SEASONAL.set(cfgBase, offset, 0.25);
            NativeBridge.VH_CFG_W_WEEKEND.set(cfgBase, offset, 0.25);
            NativeBridge.VH_CFG_W_NEWBIE.set(cfgBase, offset, 0.25);
            NativeBridge.VH_CFG_W_INFLATION.set(cfgBase, offset, 0.25);
        }
    }
}

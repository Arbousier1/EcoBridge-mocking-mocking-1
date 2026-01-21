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
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SequenceLayout;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;

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
    
    // 安全阈值：单次 Critical FFI 调用处理的最大商品数
    private static final int GC_SAFE_THRESHOLD = 500;
    
    // [Fix] 精度转换常量
    private static final double MICROS_SCALE = 1_000_000.0;

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
        double activeVolatility = 1.0 + (1.0 - stability) * 2.0;

        // 5. FFM 资源安全封装
        try (Arena arena = Arena.ofConfined()) {
            SequenceLayout tradeCtxLayout = MemoryLayout.sequenceLayout(count, Layouts.TRADE_CONTEXT);
            SequenceLayout marketCfgLayout = MemoryLayout.sequenceLayout(count, Layouts.MARKET_CONFIG);
            SequenceLayout doubleArrLayout = MemoryLayout.sequenceLayout(count, JAVA_DOUBLE);

            MemorySegment ctxArray = arena.allocate(tradeCtxLayout);
            MemorySegment cfgArray = arena.allocate(marketCfgLayout);
            MemorySegment histAvgArray = arena.allocate(doubleArrLayout);
            MemorySegment lambdaArray = arena.allocate(doubleArrLayout);
            MemorySegment resultsArray = arena.allocate(doubleArrLayout);

            double neff = NativeBridge.queryNeffVectorized(now, configTau);

            // 6. 数据 Packing
            for (int i = 0; i < count; i++) {
                ItemMeta meta = activeItems.get(i);
                
                long ctxOffset = (long) i * Layouts.TRADE_CONTEXT.byteSize();
                long cfgOffset = (long) i * Layouts.MARKET_CONFIG.byteSize();

                MemorySegment ctxSlice = ctxArray.asSlice(ctxOffset, Layouts.TRADE_CONTEXT.byteSize());
                
                // 填充基础数据 (NativeContextBuilder 已适配 Micros)
                NativeContextBuilder.fillGlobalContext(ctxSlice, now, 1.0);
                
                // [Fix] 这里的 basePrice 写入必须手动适配 micros 精度
                // 将 double 转换为 i64 (微米)
                long basePriceMicros = (long) (meta.basePrice() * MICROS_SCALE);
                NativeBridge.VH_CTX_BASE_PRICE_MICROS.set(ctxArray, ctxOffset, basePriceMicros);

                ConfigurationSection itemConfig = null;
                if (itemSettings != null) {
                    itemConfig = itemSettings.getConfigurationSection(meta.shopId() + "." + meta.productId());
                }
                // 这里传入 config 用于读取全局环境参数，传入 itemConfig 用于读取权重覆盖
                fillMarketConfigAtOffset(cfgArray, cfgOffset, itemConfig, config, meta.lambda(), activeVolatility);

                double histAvg = histAvgMap.getOrDefault(meta.productId(), meta.basePrice());
                histAvgArray.setAtIndex(JAVA_DOUBLE, i, histAvg);
                lambdaArray.setAtIndex(JAVA_DOUBLE, i, meta.lambda());
            }

            // 7. 执行分片 FFI 调用
            for (int i = 0; i < count; i += GC_SAFE_THRESHOLD) {
                int currentBatchSize = Math.min(GC_SAFE_THRESHOLD, count - i);
                
                long offsetCtx = (long) i * Layouts.TRADE_CONTEXT.byteSize();
                long offsetCfg = (long) i * Layouts.MARKET_CONFIG.byteSize();
                long offsetDouble = (long) i * JAVA_DOUBLE.byteSize();

                NativeBridge.computeBatchPrices(
                    (long) currentBatchSize,
                    neff,
                    ctxArray.asSlice(offsetCtx),
                    cfgArray.asSlice(offsetCfg),
                    histAvgArray.asSlice(offsetDouble),
                    lambdaArray.asSlice(offsetDouble),
                    resultsArray.asSlice(offsetDouble)
                );
            }

            // 8. 结果 Unpacking
            for (int i = 0; i < count; i++) {
                double computedPrice = resultsArray.getAtIndex(JAVA_DOUBLE, i);
                ItemMeta meta = activeItems.get(i);
                
                if (Double.isFinite(computedPrice) && computedPrice > 0) {
                    resultMap.put(meta.uniqueKey(), computedPrice);
                } else {
                    resultMap.put(meta.uniqueKey(), meta.basePrice());
                }
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
            
            double finalLambda = autoItem.lambda(); 
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
        List<String> ids = items.stream().map(ItemMeta::productId).distinct().toList();
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
package top.ellan.ecobridge.integration.platform.hook;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.infrastructure.ffi.bridge.NativeBridge;
import top.ellan.ecobridge.infrastructure.persistence.storage.ActivityCollector;
import top.ellan.ecobridge.application.service.EconomicStateManager;
import top.ellan.ecobridge.application.service.EconomyManager;
import top.ellan.ecobridge.application.service.PricingManager;
import top.ellan.ecobridge.util.HolidayManager;

import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * EcoBridge 占位符扩展 - 高性能注册表版 (v1.6.0)
 * * 核心重构：
 * 1. 引入 Map 路由表取代冗长的 switch/if-else 结构。
 * 2. 保持 VarHandle 的高安全性内存访问逻辑。
 * 3. 支持前缀动态变量路由。
 */
public class EcoPlaceholderExpansion extends PlaceholderExpansion {

    private final EcoBridge plugin;
    
    // 静态变量处理器 (精确匹配)
    private final Map<String, BiFunction<OfflinePlayer, String, String>> staticHandlers = new HashMap<>();

    public EcoPlaceholderExpansion(EcoBridge plugin) {
        this.plugin = plugin;
        initializeRegistry();
    }

    private void initializeRegistry() {
        // --- 1. 宏观经济指标 ---
        staticHandlers.put("inflation", (p, args) -> 
            String.format("%.2f%%", EconomyManager.getInstance().getInflationRate() * 100));
        staticHandlers.put("stability", (p, args) -> 
            String.format("%.2f", EconomyManager.getInstance().getMarketStability()));
        staticHandlers.put("market_heat", (p, args) -> 
            String.format("%.1f", EconomyManager.getInstance().getMarketHeat()));
        staticHandlers.put("eco_saturation", (p, args) -> 
            String.format("%.2f%%", EconomyManager.getInstance().getEcoSaturation() * 100));
        staticHandlers.put("is_holiday", (p, args) -> 
            HolidayManager.isTodayHoliday() ? "是" : "否");
        staticHandlers.put("holiday_mult", (p, args) -> 
            String.format("%.1fx", HolidayManager.getHolidayEpsilonFactor()));

        // --- 2. PID 内核与 Native 状态 ---
        staticHandlers.put("native_status", (p, args) -> 
            NativeBridge.isLoaded() ? "已就绪" : "未加载");
        staticHandlers.put("pid_kp", (p, args) -> getPidValue("pid_kp"));
        staticHandlers.put("pid_ki", (p, args) -> getPidValue("pid_ki"));
        staticHandlers.put("pid_kd", (p, args) -> getPidValue("pid_kd"));
        
        staticHandlers.put("native_logs", (p, args) -> getNativeStat(0));
        staticHandlers.put("native_dropped", (p, args) -> getNativeStat(1));

        // --- 3. 玩家画像 (需在线玩家上下文) ---
        staticHandlers.put("player_hours", (p, args) -> {
            if (p == null || !p.isOnline()) return "0.0";
            var snapshot = ActivityCollector.capture(p.getPlayer(), 48.0);
            return String.format("%.1f", snapshot.hours());
        });
        staticHandlers.put("player_is_newbie", (p, args) -> {
            if (p == null || !p.isOnline()) return "新手";
            var snapshot = ActivityCollector.capture(p.getPlayer(), 48.0);
            return (snapshot.isNewbie() == 1) ? "新手" : "资深";
        });
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (EconomyManager.getInstance() == null) return "N/A";

        // 优先进行 O(1) 级别的精确匹配查找
        BiFunction<OfflinePlayer, String, String> handler = staticHandlers.get(params.toLowerCase());
        if (handler != null) {
            return handler.apply(player, params);
        }

        // --- 4. 动态参数路由 (基于前缀匹配) ---
        
        // 市场分析颜色: %ecobridge_state_color_<ID>%
        if (params.startsWith("state_color_")) {
            String productId = params.substring(12);
            var phase = EconomicStateManager.getInstance().analyzeMarketAndNotify(productId, 0.0);
            return switch (phase) {
                case STABLE -> "&a";
                case SATURATED -> "&e";
                case EMERGENCY -> "&c";
                case HEALING -> "&b";
                default -> "&7";
            };
        }

        // 市场相位名称: %ecobridge_state_name_<ID>%
        if (params.startsWith("state_name_")) {
            String productId = params.substring(11);
            return EconomicStateManager.getInstance().analyzeMarketAndNotify(productId, 0.0).name();
        }

        // 买入价: %ecobridge_buy_price_<ID>%
        if (params.startsWith("buy_price_")) {
            String productId = params.substring(10);
            return PricingManager.getInstance() != null ? 
                String.format("%.2f", PricingManager.getInstance().calculateBuyPrice(productId)) : "0.00";
        }

        // 卖出价: %ecobridge_sell_price_<ID>%
        if (params.startsWith("sell_price_")) {
            String productId = params.substring(11);
            return PricingManager.getInstance() != null ? 
                String.format("%.2f", PricingManager.getInstance().calculateSellPrice(productId)) : "0.00";
        }

        return null;
    }

    // --- 内部辅助工具方法 (封装 FFI 逻辑) ---

    private String getPidValue(String param) {
        if (!NativeBridge.isLoaded() || PricingManager.getInstance() == null) return "0.000";
        MemorySegment pidSeg = PricingManager.getInstance().getGlobalPidState();
        if (pidSeg == null || pidSeg.address() == 0 || !pidSeg.scope().isAlive()) return "0.000";
        
        try {
            return switch (param) {
                case "pid_kp" -> String.format("%.3f", (double) NativeBridge.VH_PID_KP.get(pidSeg, 0L));
                case "pid_ki" -> String.format("%.3f", (double) NativeBridge.VH_PID_KI.get(pidSeg, 0L));
                case "pid_kd" -> String.format("%.3f", (double) NativeBridge.VH_PID_KD.get(pidSeg, 0L));
                default -> "0.000";
            };
        } catch (Exception e) {
            return "ERR";
        }
    }

    private String getNativeStat(int index) {
        if (!NativeBridge.isLoaded()) return "0";
        long[] stats = new long[2];
        NativeBridge.getHealthStats(stats);
        return String.valueOf(stats[index]);
    }

    @Override
    public @NotNull String getIdentifier() { return "ecobridge"; }

    @Override
    public @NotNull String getAuthor() { return "Ellan"; }

    @Override
    public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }

    @Override
    public boolean persist() { return true; }
}
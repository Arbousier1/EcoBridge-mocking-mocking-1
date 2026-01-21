/**
 * EcoBridge 变量注册表 (MiniMessage 标签系统)
 * * --- 1. 全局经济变量 (Global Resolver) ---
 * <inflation>        - 当前全服通货膨胀率 (例如: "5.25%")
 * <stability>        - 市场稳定性指标 (例如: "0.85")
 * <market_heat>      - 市场热度/交易频率评分 (例如: "42.5")
 * <eco_saturation>   - 全服货币饱和度 (例如: "75.20%")
 * <pid_kp>           - 实时 PID 控制器比例增益 (来自 Native 内存)
 * <pid_ki>           - 实时 PID 控制器积分增益 (来自 Native 内存)
 * <pid_kd>           - 实时 PID 控制器微分增益 (来自 Native 内存)
 * <holiday_status>   - 今日是否为节假日 (返回: "是" / "否")
 * <holiday_mult>     - 节假日经济波动因子倍率 (例如: "1.20x")
 * * --- 2. 系统底层状态 (System Resolver) ---
 * <native_status>    - FFM 跨语言桥接加载状态 (返回: "已就绪" / "未加载")
 * <native_logs>      - 底层内核已处理的交易日志总数
 * <native_dropped>   - 因性能瓶颈丢弃的日志数据包数量
 * * --- 3. 玩家画像变量 (Player Resolver) ---
 * <player_hours>     - 玩家在采样周期内的活跃小时数 (例如: "12.5")
 * <newbie_tag>       - 玩家身份标签 (返回: "新手" / "资深")
 * * --- 4. 市场/物品变量 (Market Resolver - 需传入 productId) ---
 * <market_phase>     - 物品当前市场相位名称 (例如: "STABLE", "EMERGENCY")
 * <market_color>     - 相位对应的 MiniMessage 颜色标签 (例如: <green>, <red>)
 * <buy_price>        - 经算法修正后的物品实时【买入】单价
 * <sell_price>       - 经算法修正后的物品实时【卖出】单价
 */

package top.ellan.ecobridge.util;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.ellan.ecobridge.infrastructure.ffi.bridge.NativeBridge;
import top.ellan.ecobridge.infrastructure.persistence.storage.ActivityCollector;
import top.ellan.ecobridge.application.service.EconomicStateManager;
import top.ellan.ecobridge.application.service.EconomyManager;
import top.ellan.ecobridge.application.service.PricingManager;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * EcoBridge 内部变量注册器 - 最终集成稳定版
 * * 职责：
 * 1. 适配 MiniMessage 变量系统。
 * 2. 提供安全的跨语言内存数据读取。
 * 3. 封装玩家画像逻辑与实时价格显示。
 */
public final class InternalPlaceholder {

    private InternalPlaceholder() {}

    /**
     * 1. 获取全局经济变量 (含 PID 实时内核数据)
     */
    @NotNull
    public static TagResolver getGlobalResolver() {
        EconomyManager eco = EconomyManager.getInstance();
        double currentKp = 0, currentKi = 0, currentKd = 0;

        // [FFI Safety Check] 增加 Scope 存活检查与地址验证
        if (NativeBridge.isLoaded() && PricingManager.getInstance() != null) {
            MemorySegment pidSeg = PricingManager.getInstance().getGlobalPidState();
            
            // 关键：必须检查 MemorySegment 的 Scope 是否存活且地址不为 0
            if (pidSeg != null && pidSeg.address() != 0 && pidSeg.scope().isAlive()) {
                try {
                    currentKp = pidSeg.get(ValueLayout.JAVA_DOUBLE, 0);
                    currentKi = pidSeg.get(ValueLayout.JAVA_DOUBLE, 8);
                    currentKd = pidSeg.get(ValueLayout.JAVA_DOUBLE, 16);
                } catch (IllegalStateException e) {
                    // 捕获可能在读取瞬间产生的 Scope closed 异常
                }
            }
        }

        return TagResolver.resolver(
            Placeholder.unparsed("inflation", String.format("%.2f%%", eco.getInflationRate() * 100)),
            Placeholder.unparsed("stability", String.format("%.2f", eco.getMarketStability())),
            Placeholder.unparsed("market_heat", String.format("%.1f", eco.getMarketHeat())),
            Placeholder.unparsed("eco_saturation", String.format("%.2f%%", eco.getEcoSaturation() * 100)),
            Placeholder.unparsed("pid_kp", String.format("%.3f", currentKp)),
            Placeholder.unparsed("pid_ki", String.format("%.3f", currentKi)),
            Placeholder.unparsed("pid_kd", String.format("%.3f", currentKd)),
            Placeholder.unparsed("holiday_status", HolidayManager.isTodayHoliday() ? "是" : "否"),
            Placeholder.unparsed("holiday_mult", String.format("%.1fx", HolidayManager.getHolidayEpsilonFactor()))
        );
    }

    /**
     * 2. 系统底层 FFM 状态 (适配 long[] 安全接口)
     */
    @NotNull
    public static TagResolver getSystemResolver() {
        String status = NativeBridge.isLoaded() ? "已就绪" : "未加载";
        long totalLogs = 0;
        long droppedLogs = 0;

        if (NativeBridge.isLoaded()) {
            long[] stats = new long[2];
            NativeBridge.getHealthStats(stats);
            totalLogs = stats[0];
            droppedLogs = stats[1];
        }

        return TagResolver.resolver(
            Placeholder.unparsed("native_status", status),
            Placeholder.unparsed("native_logs", String.valueOf(totalLogs)),
            Placeholder.unparsed("native_dropped", String.valueOf(droppedLogs))
        );
    }

    /**
     * 3. 玩家画像
     */
    @NotNull
    public static TagResolver getPlayerResolver(@Nullable Player player) {
        if (player == null) return TagResolver.empty();
        
        var snapshot = ActivityCollector.capture(player, 48.0);
        String tag = (snapshot.isNewbie() == 1) ? "新手" : "资深";
        
        return TagResolver.resolver(
            getGlobalResolver(),
            Placeholder.unparsed("player_hours", String.format("%.1f", snapshot.hours())),
            Placeholder.unparsed("newbie_tag", tag)
        );
    }

    /**
     * 4. 市场分析状态机
     * 集成了特定物品的实时购买价格 (buy_price) 与出售价格 (sell_price)
     */
    @NotNull
    public static TagResolver getMarketResolver(@NotNull String productId) {
        var phase = EconomicStateManager.getInstance().analyzeMarketAndNotify(productId, 0.0);
        String color = switch (phase) {
            case STABLE -> "<green>";
            case SATURATED -> "<yellow>";
            case EMERGENCY -> "<red>";
            case HEALING -> "<aqua>";
        };

        // 获取实时价格数据
        double buyPrice = 0.0;
        double sellPrice = 0.0;
        PricingManager pm = PricingManager.getInstance();
        if (pm != null) {
            buyPrice = pm.calculateBuyPrice(productId);
            sellPrice = pm.calculateSellPrice(productId);
        }

        return TagResolver.resolver(
            Placeholder.unparsed("market_phase", phase.name()),
            Placeholder.parsed("market_color", color),
            // 新增物品单价显示
            Placeholder.unparsed("buy_price", String.format("%.2f", buyPrice)),
            Placeholder.unparsed("sell_price", String.format("%.2f", sellPrice))
        );
    }
}
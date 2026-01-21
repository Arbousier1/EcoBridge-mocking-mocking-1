package top.ellan.ecobridge.infrastructure.ffi.bridge;

import top.ellan.ecobridge.application.service.EconomyManager;
import top.ellan.ecobridge.util.HolidayManager;
import top.ellan.ecobridge.util.LogUtil;

import java.lang.foreign.MemorySegment;
import java.time.OffsetDateTime;

/**
 * Native 内存上下文构建器 (v0.9.7 - Micros Compatible)
 * <p>
 * 职责: 封装 VarHandle 操作，确保 Java 填充的数据与 Rust 侧 TradeContext 内存布局完全对齐。
 * 修复日志:
 * 1. [Layout] 适配 NativeBridge 微米级字段 (base_price_micros, current_amount).
 * 2. [Precision] 增加 double -> long (micros) 的精度转换逻辑.
 */
public class NativeContextBuilder {

    // 缓存时区偏移量，减少高频交易时的系统调用开销
    private static final int TIMEZONE_OFFSET = OffsetDateTime.now().getOffset().getTotalSeconds();
    private static final double MICROS_SCALE = 1_000_000.0;

    /**
     * 填充标准的交易上下文 (Global Context) - 默认交易量为 1.0
     * @param ctxSeg 必须是 NativeBridge.Layouts.TRADE_CONTEXT 大小的段
     * @param now    当前 Unix 时间戳
     */
    public static void fillGlobalContext(MemorySegment ctxSeg, long now) {
        fillGlobalContext(ctxSeg, now, 1.0);
    }

    /**
     * 填充标准的交易上下文 (Global Context) - 带交易量暗示
     * @param ctxSeg     内存段
     * @param now        时间戳
     * @param amountHint 预估交易量 (用于计算滑动点差 Preview)。
     * 传入 1.0 代表查询"当前买入1个"的单价；
     * 传入 64.0 代表查询"当前买入一组"的平均单价。
     */
    public static void fillGlobalContext(MemorySegment ctxSeg, long now, double amountHint) {
        if (!NativeBridge.isLoaded()) {
            return;
        }

        try {
            // 1. 基础时间数据 (Offset 24)
            NativeBridge.VH_CTX_TIMESTAMP.set(ctxSeg, 0L, now);

            // 2. 宏观经济指标
            EconomyManager eco = EconomyManager.getInstance();
            if (eco != null) {
                // 通胀率 (Offset 16) - 保持 double
                NativeBridge.VH_CTX_INF_RATE.set(ctxSeg, 0L, eco.getInflationRate());
                
                // 市场热度 (Offset 48) 与 生态饱和度 (Offset 56) - 保持 double
                NativeBridge.VH_CTX_MARKET_HEAT.set(ctxSeg, 0L, eco.getMarketHeat());
                NativeBridge.VH_CTX_ECO_SAT.set(ctxSeg, 0L, eco.getEcoSaturation());
            }

            // 3. 时区偏移 (Offset 40) - int
            NativeBridge.VH_CTX_TIMEZONE_OFFSET.set(ctxSeg, 0L, TIMEZONE_OFFSET);

            // 4. 特殊状态掩码 (Offset 44) - int
            // Bit 1: 是否节假日
            int mask = (HolidayManager.isTodayHoliday() ? 1 : 0) << 1;
            NativeBridge.VH_CTX_NEWBIE_MASK.set(ctxSeg, 0L, mask);

            // 5. 交易属性初始化
            // [Audit] play_time_seconds 暂时保留为 0，Rust 定价逻辑目前不依赖此字段，但保留结构兼容性。
            NativeBridge.VH_CTX_PLAY_TIME.set(ctxSeg, 0L, 0L);
            
            // [Fix] 适配 Micros 精度：将 double 数量转换为 long 微米值
            // Rust 端 current_amount 类型为 i64，此处需进行定点数转换
            long amountMicros = (long) (amountHint * MICROS_SCALE);
            NativeBridge.VH_CTX_CURR_AMT.set(ctxSeg, 0L, amountMicros);

        } catch (Exception e) {
            LogUtil.error("填充 GlobalContext 失败: 内存段可能已失效", e);
        }
    }

    /**
     * 更新特定商品的独立属性 (Offset 0)
     * @param ctxSeg    上下文内存段
     * @param basePrice 商品基准价
     */
    public static void updateItemContext(MemorySegment ctxSeg, double basePrice) {
        if (!NativeBridge.isLoaded()) return;
        
        try {
            // [Fix] 适配 Micros 精度：base_price -> base_price_micros
            // 将 Java 的 double 价格转换为 Rust 的 i64 微米价格
            long priceMicros = (long) (basePrice * MICROS_SCALE);
            NativeBridge.VH_CTX_BASE_PRICE_MICROS.set(ctxSeg, 0L, priceMicros);
        } catch (Exception e) {
            LogUtil.error("更新 ItemContext 失败", e);
        }
    }
}
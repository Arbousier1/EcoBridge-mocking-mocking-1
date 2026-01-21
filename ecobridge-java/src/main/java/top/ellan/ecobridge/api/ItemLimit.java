package top.ellan.ecobridge.api;

// [优化] 移除对 manager 包的依赖，改用 api 包下的 MarketPhase

/**
 * 物品限额快照 - 存储经过时长演算后的个人上限
 */
public record ItemLimit(
    String productId,
    double currentPrice,     // 当前基准价格 (通常为买入单价)
    double sellRatio,        // 售出折扣率 (如 0.5)
    double lambda,           // 价格弹性系数
    MarketPhase marketPhase, // 市场阶段 (稳定/饱和/恐慌等)
    double dynamicMaxQuantity, // 动态限额 (经过时长 sqrt 计算后的最终值)
    boolean isUnderProtection  // 是否处于价格保护模式
) {
    /**
     * 获取建议的单件售出价格
     * 注意：这只是基于基准价格的估算，实际批量出售可能会因价格滑动而变动。
     */
    public double getSuggestedSellPrice() {
        return currentPrice * sellRatio;
    }
}
package top.ellan.ecobridge.api;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.Optional;

public interface EcoLimitAPI {
    /**
     * 获取物品限额快照（包含专门为该玩家计算的动态上限）
     * 包含：当前价格、lambda系数、市场阶段、针对该玩家的动态限额等
     */
    Optional<ItemLimit> getItemLimit(UUID playerUuid, String productId);

    /**
     * 判定交易是否超限
     * @param quantity 交易数量
     */
    boolean isBlockedByDynamicLimit(UUID playerUuid, String productId, double quantity);

    /**
     * 获取预估税费 (增强版)
     * <p>
     * 修改理由：Rust 内核的税费计算依赖于玩家的“资金流速”和“贫富差距”。
     * 仅提供 amount 无法计算出准确的动态税率。
     * * @param player 交易发起人 (如果为 null，则返回基础税率)
     * @param amount 交易金额
     * @return 预计扣除的税费绝对值
     */
    double getEstimatedTax(@Nullable Player player, double amount);

    /**
     * 获取市场状态颜色
     * <p>
     * 建议：如果你的服务器支持 RGB/Hex 颜色，返回 Component 会比 String (Legacy Code) 更灵活。
     * 如果为了兼容性保留 String 也可以，但建议注明是 Legacy 格式 (e.g. "&c") 还是 MiniMessage。
     */
    String getMarketColor(String productId);
    // 或者使用: Component getMarketColorComponent(String productId);
}
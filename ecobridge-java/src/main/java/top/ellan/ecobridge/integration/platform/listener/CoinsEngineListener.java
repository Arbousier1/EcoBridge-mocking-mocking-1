package top.ellan.ecobridge.integration.platform.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

// 1. CoinsEngine API
import su.nightexpress.coinsengine.api.currency.Currency;
import su.nightexpress.coinsengine.api.event.ChangeBalanceEvent;

// 2. 项目内部组件
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.application.context.TransactionContext; // [关键修复] 引入上下文
import top.ellan.ecobridge.application.service.EconomyManager;
import top.ellan.ecobridge.infrastructure.cache.HotDataCache;
import top.ellan.ecobridge.infrastructure.cache.HotDataCache.PlayerData; // [关键修复] 显式导入内部类
import top.ellan.ecobridge.infrastructure.persistence.database.TransactionDao;
import top.ellan.ecobridge.infrastructure.persistence.storage.AsyncLogger;

// 3. Java 基础类
import java.util.UUID;

/**
 * 经济传感器 (CoinsEngineListener v0.9.1 - Context Aware)
 * 职责：作为底层传感器，实时捕获 CoinsEngine 余额变动信号并同步至全服经济系统。
 * * 修复记录：
 * 1. [Fix] 引入 TransactionContext 解决 M1 与 Velocity 的混淆问题。
 * 2. [Fix] 通过只统计负向 Delta 解决双重计数 (Double Counting) 问题。
 * 3. [Fix] 修复 PlayerData 类型引用的编译错误。
 */
public class CoinsEngineListener implements Listener {

    private final String targetCurrencyId;
    private static final double EPSILON = 1e-6; // 过滤计算舍入产生的噪声

    public CoinsEngineListener(EcoBridge plugin) {
        // 从配置中锁定主监控货币 ID (如 "coins")
        this.targetCurrencyId = plugin.getConfig().getString("economy.currency-id", "coins");
    }

    /**
     * 实时监听余额变动事件
     * 优先级设定为 MONITOR，仅观察成交结果，确保不干涉其他插件的业务逻辑。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBalanceChange(ChangeBalanceEvent event) {
        // 1. 货币类型过滤：仅监控目标主货币
        Currency currency = event.getCurrency();
        if (!targetCurrencyId.equals(currency.getId())) {
            return;
        }

        // 2. 差分演算 (Delta Calculation)
        double oldAmount = event.getOldAmount();
        double newAmount = event.getNewAmount();
        double delta = newAmount - oldAmount;

        // 3. 噪声过滤：忽略由于浮点精度产生的微小抖动
        if (Math.abs(delta) < EPSILON) {
            return;
        }

        // --- 核心修复：基于上下文识别变动来源 ---
        // 检查当前线程是否持有“市场交易”标记 (由 TransferManager 设置)
        boolean isMarketTrade = TransactionContext.isMarketTrade();

        if (isMarketTrade) {
            // [Fix Double Counting]
            // 市场交易（转账）会产生两笔变动：A扣款，B入账。
            // 我们只在扣款发生时(delta < 0)记录一次交易额，避免热度虚高 2 倍。
            if (delta < 0) {
                // 记录交易热度 (Velocity)，传入 true 触发 PID 演算
                EconomyManager.getInstance().onTransaction(Math.abs(delta), true);
            }
        } else {
            // 非 EcoBridge 触发的变动（如控制台指令、其他插件发奖、每日利息等）
            // 视为 M1 供应量变化，不计入市场热度，避免错误拉高物价。
            EconomyManager.getInstance().onTransaction(delta, false);
        }

        // 4. [SSoT 修复]: 触发数据同步与审计
        // 注意：CoinsEngine 的 getUser().getId() 通常返回 UUID
        syncToStorage(event.getUser().getId(), newAmount, delta, isMarketTrade);
    }

    private void syncToStorage(UUID uuid, double balance, double delta, boolean isMarketTrade) {
        // 1. 同步热数据缓存 (HotDataCache)
        // 显式使用 PlayerData 类型，解决之前的 Object 类型报错
        PlayerData cachedData = HotDataCache.get(uuid);
        if (cachedData != null) {
            cachedData.updateFromTruth(balance);
        }

        // 2. [SSoT 修复]: 异步持久化快照
        // 在数据库中更新该玩家的最终余额快照
        TransactionDao.updateBalance(uuid, balance);

        // 3. 审计层：触发异步持久化日志 (AsyncLogger)
        // 根据上下文标记 Meta，方便 DuckDB 分析资金来源
        String meta = isMarketTrade ? "INTERNAL_MARKET_TRADE" : "EXTERNAL_API_CHANGE";

        AsyncLogger.log(
                uuid,
                delta,                  // 交易变动净值
                balance,                // 交易后余额快照
                System.currentTimeMillis(),
                meta                    // [Fix] 动态 Meta 标签
        );
    }
}
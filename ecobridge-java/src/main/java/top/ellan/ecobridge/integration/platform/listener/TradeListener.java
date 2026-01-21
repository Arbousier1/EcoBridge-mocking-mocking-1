package top.ellan.ecobridge.integration.platform.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import top.ellan.ecobridge.EcoBridge;

/**
 * 行为引导型交易监听器 (TradeListener v0.8.6 - Async Safety Hardened)
 * 职责：负责玩家基础清理工作。
 * * 注意：已移除对 UltimateShop API 的集成逻辑及相关的冗余私有方法和字段。
 */
public class TradeListener implements Listener {

    public TradeListener(EcoBridge plugin) {
        // 构造函数保留，以兼容 EcoBridge 主类中的 registerListeners 调用
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // 目前作为基础监听器保留，用于后续对接通用交易事件或执行玩家临时数据清理
    }
}
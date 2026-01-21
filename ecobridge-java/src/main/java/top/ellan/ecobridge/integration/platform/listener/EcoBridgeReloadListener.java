package top.ellan.ecobridge.integration.platform.listener;

import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import top.ellan.ecobridge.integration.platform.hook.CraftEngineSupport;
import top.ellan.ecobridge.domain.algorithm.PriceComputeEngine;
import top.ellan.ecobridge.EcoBridge;

import java.util.List;

/**
 * 监听 CraftEngine 重载事件，确保 EcoBridge 价格数据与自定义物品同步
 */
public class EcoBridgeReloadListener implements Listener {

    private final EcoBridge plugin;

    public EcoBridgeReloadListener(EcoBridge plugin) {
        this.plugin = plugin;
    }

    /**
     * 使用 MONITOR 优先级确保在 CraftEngine 自身处理完逻辑后再执行
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        plugin.getLogger().info("检测到 CraftEngine 重载，正在同步物品元数据到价格引擎...");
        
        // 1. 获取默认敏感度参数
        double defaultLambda = plugin.getConfig().getDouble("prices.default-lambda", 0.01);

        // 2. 调用 Hook 获取最新的 CraftEngine 物品列表
        List<PriceComputeEngine.ItemMeta> metadataList = CraftEngineSupport.getCraftItemsAsMetadata(defaultLambda);

        if (metadataList.isEmpty()) {
            plugin.getLogger().warning("未找到任何已加载的 CraftEngine 物品，跳过同步。");
            return;
        }

        // 3. 将新发现的物品推送到引擎缓存
        try {
            PriceComputeEngine.updateAllItems(metadataList);
            plugin.getLogger().info("同步成功！当前共有 " + metadataList.size() + " 个物品受市场价格演算控制。");
        } catch (Exception e) {
            plugin.getLogger().severe("同步价格引擎时发生错误: " + e.getMessage());
        }
    }
}
package top.ellan.ecobridge.integration.platform.hook;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;
import top.ellan.ecobridge.domain.algorithm.PriceComputeEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * CraftEngine 物品兼容性支持类
 */
public class CraftEngineSupport {

    private CraftEngineSupport() {}

    /**
     * 将 CraftEngine 所有已加载的自定义物品转换为 EcoBridge 演算元数据
     * * @param defaultLambda 默认价格敏感度系数
     * @return 转换后的 ItemMeta 列表
     */
    public static List<PriceComputeEngine.ItemMeta> getCraftItemsAsMetadata(double defaultLambda) {
        List<PriceComputeEngine.ItemMeta> metadataList = new ArrayList<>();
        
        try {
            // 方式 A (推荐): 直接调用 API 提供的静态方法
            // 如果报错，说明依赖中找不到 BukkitItemManager 类（它是静态方法的返回值类型）
            Set<Key> keys = CraftEngineItems.loadedItems().keySet();
            
            // 方式 B (备选): 如果方式 A 报错，取消下方注释并使用方式 B
            // Set<Key> keys = BukkitItemManager.instance().loadedItems().keySet();
            
            if (keys == null || keys.isEmpty()) {
                return metadataList;
            }

            for (Key key : keys) {
                // 构造内部 ID: "craftengine:namespace:id"
                String combinedId = "craftengine:" + key.namespace() + ":" + key.value();
                
                metadataList.add(new PriceComputeEngine.ItemMeta(
                    combinedId,
                    "craftengine",
                    key.toString(),
                    100.0,
                    defaultLambda
                ));
            }
        } catch (Exception e) {
            // 捕获异常，防止 CraftEngine 未就绪时崩溃
        }
        
        return metadataList;
    }

    /**
     * 获取 ItemStack 对应的内部唯一标识符
     *
     * @param item 物品堆
     * @return 如果是 CraftEngine 物品，返回格式化的 ID
     */
    public static Optional<String> getInternalId(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return Optional.empty();
        }

        try {
            // 使用 API 提供的工具方法获取 Key
            Key key = CraftEngineItems.getCustomItemId(item);
            if (key != null) {
                return Optional.of("craftengine:" + key.namespace() + ":" + key.value());
            }
        } catch (Exception ignored) {
            // 插件未安装或未加载时忽略
        }
        
        return Optional.empty();
    }
}
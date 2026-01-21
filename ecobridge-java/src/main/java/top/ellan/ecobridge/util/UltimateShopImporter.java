package top.ellan.ecobridge.util;

import cn.superiormc.ultimateshop.managers.ConfigManager;
import cn.superiormc.ultimateshop.objects.ObjectShop;
import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.application.service.ItemConfigManager;

import java.util.concurrent.atomic.AtomicInteger;

public class UltimateShopImporter {

    public static String runImport(double defaultLambda) {
        if (!EcoBridge.getInstance().getServer().getPluginManager().isPluginEnabled("UltimateShop")) {
            return "<red>未检测到 UltimateShop 插件，无法执行导入。";
        }

        // 获取 items.yml 配置对象
        FileConfiguration itemConfig = ItemConfigManager.get();
        
        // 确保根节点存在
        if (!itemConfig.isConfigurationSection("item-settings")) {
            itemConfig.createSection("item-settings");
        }

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger skipCount = new AtomicInteger(0);

        try {
            // 修正 1: 使用 ConfigManager.configManager.getShopList() 获取商店列表
            //
            for (ObjectShop shop : ConfigManager.configManager.getShopList()) {
                
                // 修正 2: 获取商店名称作为分类 Key
                String shopId = shop.getShopName(); 

                // 修正 3: 使用 getProductList() 遍历物品
                for (ObjectItem item : shop.getProductList()) {
                    
                    // 修正 4: 使用 getItemConfig() 获取配置片段
                    //
                    ConfigurationSection itemSection = item.getItemConfig();
                    if (itemSection == null) continue;

                    // 检查并获取 Vault 价格
                    double buyPrice = getVaultPrice(itemSection);
                    
                    if (buyPrice <= 0.001) {
                        skipCount.incrementAndGet();
                        continue;
                    }

                    // 修正 5: 使用 getProduct() 获取物品ID (即配置节名称)
                    String productId = item.getProduct();
                    if (productId == null || productId.isEmpty()) {
                        productId = "UNKNOWN_" + successCount.get();
                    }

                    String path = "item-settings." + shopId + "." + productId;
                    
                    // 写入到 items.yml
                    if (!itemConfig.contains(path)) {
                        itemConfig.set(path + ".base-price", buyPrice);
                        itemConfig.set(path + ".lambda", defaultLambda);
                        // 写入默认权重
                        itemConfig.set(path + ".weights.seasonal", 0.25);
                        itemConfig.set(path + ".weights.weekend", 0.25);
                        itemConfig.set(path + ".weights.newbie", 0.25);
                        itemConfig.set(path + ".weights.inflation", 0.25);
                        
                        successCount.incrementAndGet();
                        LogUtil.debug("已导入: " + shopId + " -> " + productId + " ($" + buyPrice + ")");
                    } else {
                        skipCount.incrementAndGet();
                    }
                }
            }
        } catch (NoClassDefFoundError | Exception e) {
            LogUtil.error("导入 UltimateShop 配置时发生错误，请检查插件版本兼容性。", e);
            return "<red>导入失败: " + e.getMessage();
        }

        // 保存并重载
        ItemConfigManager.save();
        
        if (EcoBridge.getInstance().isFullyInitialized()) {
            EcoBridge.getInstance().reload();
        }

        return "<green>导入完成 (items.yml)!</green> <gray>新增: <white>" + successCount.get() + "</white>, 跳过/已存在: <white>" + skipCount.get() + "</white>";
    }

    /**
     * 解析配置以获取 Vault 价格
     * 直接读取 ConfigurationSection 可以兼容不同的 ObjectPrices 实现版本
     */
    private static double getVaultPrice(ConfigurationSection section) {
        // 模式 1: buy-prices 复杂节点 (UltimateShop 新版标准)
        // 结构通常为:
        // buy-prices:
        //   economy: 100.0
        if (section.isConfigurationSection("buy-prices")) {
            ConfigurationSection prices = section.getConfigurationSection("buy-prices");
            if (prices != null) {
                // "economy" 是 UltimateShop 默认代表 Vault 的键
                if (prices.contains("economy")) return prices.getDouble("economy");
                // 有些版本可能直接使用 "vault"
                if (prices.contains("vault")) return prices.getDouble("vault");
            }
        }
        
        // 模式 2: 简单价格模式 (旧版或简化版)
        // 结构通常为:
        // price:
        //   buy: 100.0
        // economy-plugin: Vault
        if (section.contains("price.buy")) {
            String ecoType = section.getString("economy-plugin", "Exp");
            if ("Vault".equalsIgnoreCase(ecoType) || "CoinsEngine".equalsIgnoreCase(ecoType) || "Economy".equalsIgnoreCase(ecoType)) {
                return section.getDouble("price.buy");
            }
        }

        return -1.0;
    }
}
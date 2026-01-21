package top.ellan.ecobridge.util;

import cn.superiormc.ultimateshop.managers.ConfigManager;
import cn.superiormc.ultimateshop.objects.ObjectShop;
import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.application.service.ItemConfigManager;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * UltimateShopImporter (v1.1 - Enhanced Compatibility)
 * 职责：从 UltimateShop 自动同步商品数据到 EcoBridge 体系。
 */
public class UltimateShopImporter {

    public static String runImport(double defaultLambda) {
        // 1. 插件启用检查
        if (!EcoBridge.getInstance().getServer().getPluginManager().isPluginEnabled("UltimateShop")) {
            return "<red>未检测到 UltimateShop 插件，无法执行导入。";
        }

        // 2. 运行时实例安全检查 (防止 UltimateShop 还没加载完)
        if (ConfigManager.configManager == null || ConfigManager.configManager.getShopList() == null) {
            return "<yellow>UltimateShop 实例尚未准备就绪，请稍后重试。";
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
            // 遍历所有商店
            for (ObjectShop shop : ConfigManager.configManager.getShopList()) {
                if (shop == null) continue;

                String shopId = shop.getShopName(); 

                // 遍历商店内所有物品
                for (ObjectItem item : shop.getProductList()) {
                    if (item == null) continue;

                    // 获取该物品原始的 YAML 配置片段
                    ConfigurationSection itemSection = item.getItemConfig();
                    if (itemSection == null) continue;

                    // 增强版价格识别逻辑
                    double buyPrice = getVaultPrice(itemSection);
                    
                    // 过滤掉无价格或无效价格的物品
                    if (buyPrice <= 0.001) {
                        skipCount.incrementAndGet();
                        continue;
                    }

                    // 获取物品标识符 (通常是 material 名或物品键)
                    String productId = item.getProduct();
                    if (productId == null || productId.isEmpty()) {
                        productId = "UNKNOWN_" + successCount.get();
                    }

                    String path = "item-settings." + shopId + "." + productId;
                    
                    // 执行增量同步，不覆盖已有配置
                    if (!itemConfig.contains(path)) {
                        itemConfig.set(path + ".base-price", buyPrice);
                        itemConfig.set(path + ".lambda", defaultLambda);
                        
                        // 初始化经济模型默认权重
                        itemConfig.set(path + ".weights.seasonal", 0.25);
                        itemConfig.set(path + ".weights.weekend", 0.25);
                        itemConfig.set(path + ".weights.newbie", 0.25);
                        itemConfig.set(path + ".weights.inflation", 0.25);
                        
                        successCount.incrementAndGet();
                        LogUtil.debug("已导入新商品: " + shopId + " -> " + productId + " ($" + buyPrice + ")");
                    } else {
                        skipCount.incrementAndGet();
                    }
                }
            }
        } catch (NoClassDefFoundError | Exception e) {
            LogUtil.error("导入 UltimateShop 配置时发生致命错误，请检查版本兼容性。", e);
            return "<red>导入失败: " + e.getMessage();
        }

        // 保存文件到磁盘并重载 EcoBridge 内存缓存
        ItemConfigManager.save();
        
        if (EcoBridge.getInstance().isFullyInitialized()) {
            EcoBridge.getInstance().reload();
        }

        return "<green>✔ 导入完成!</green> <gray>新增物品: <white>" + successCount.get() + "</white>, 跳过/已存在: <white>" + skipCount.get() + "</white>";
    }

    /**
     * 增强版价格获取逻辑：支持显式定义识别与递归通配符搜索
     */
    private static double getVaultPrice(ConfigurationSection section) {
        // --- 策略 1: 检查已知标准路径 (高优先级) ---
        if (section.contains("buy-prices.economy")) return section.getDouble("buy-prices.economy");
        if (section.contains("buy-prices.vault")) return section.getDouble("buy-prices.vault");
        if (section.contains("price.buy")) return section.getDouble("price.buy");
        
        // --- 策略 2: 递归通配符识别 (针对自定义配置或混淆版) ---
        // 遍历该物品节点下所有的键 (含深层节点)
        for (String key : section.getKeys(true)) {
            // 匹配包含 "price" 且不包含 "sell" 的数字键
            String lowerKey = key.toLowerCase();
            if (lowerKey.contains("price") && !lowerKey.contains("sell")) {
                // 确保它是一个可以解析为 double 的数值
                if (section.isDouble(key) || section.isInt(key)) {
                    double val = section.getDouble(key);
                    // 过滤掉 0 或负数，确保拿到的是真实标价
                    if (val > 0.001) return val;
                }
            }
        }

        // --- 兜底逻辑 ---
        // 特殊检查：有些版本可能直接在根节点使用 economy 键
        if (section.contains("economy") && section.isDouble("economy")) {
            return section.getDouble("economy");
        }

        return -1.0;
    }
}
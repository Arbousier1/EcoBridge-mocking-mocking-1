package top.ellan.ecobridge.util;

import cn.superiormc.ultimateshop.managers.ConfigManager;
import cn.superiormc.ultimateshop.objects.ObjectShop;
import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.application.service.ItemConfigManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Imports product base prices from UltimateShop into items.yml.
 */
public final class UltimateShopImporter {

    private UltimateShopImporter() {
    }

    public static String runImport(double defaultLambda) {
        if (!EcoBridge.getInstance().getServer().getPluginManager().isPluginEnabled("UltimateShop")) {
            return "<red>UltimateShop is not enabled, import aborted.</red>";
        }

        if (ConfigManager.configManager == null || ConfigManager.configManager.getShopList() == null) {
            return "<yellow>UltimateShop is not ready yet, please retry later.</yellow>";
        }

        FileConfiguration itemConfig = ItemConfigManager.getSnapshot();
        if (itemConfig == null) {
            return "<red>items.yml is not initialized, import aborted.</red>";
        }

        if (!itemConfig.isConfigurationSection("item-settings")) {
            itemConfig.createSection("item-settings");
        }

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger skipCount = new AtomicInteger(0);

        try {
            for (ObjectShop shop : ConfigManager.configManager.getShopList()) {
                if (shop == null) {
                    continue;
                }

                String shopId = shop.getShopName();
                for (ObjectItem item : shop.getProductList()) {
                    if (item == null) {
                        continue;
                    }

                    ConfigurationSection itemSection = item.getItemConfig();
                    if (itemSection == null) {
                        continue;
                    }

                    double buyPrice = getVaultPrice(itemSection);
                    if (buyPrice <= 0.001) {
                        skipCount.incrementAndGet();
                        continue;
                    }

                    String productId = item.getProduct();
                    if (productId == null || productId.isEmpty()) {
                        productId = "UNKNOWN_" + successCount.get();
                    }

                    String path = "item-settings." + shopId + "." + productId;
                    if (!itemConfig.contains(path)) {
                        itemConfig.set(path + ".base-price", buyPrice);
                        itemConfig.set(path + ".lambda", defaultLambda);
                        itemConfig.set(path + ".weights.seasonal", 0.25);
                        itemConfig.set(path + ".weights.weekend", 0.25);
                        itemConfig.set(path + ".weights.newbie", 0.25);
                        itemConfig.set(path + ".weights.inflation", 0.25);

                        successCount.incrementAndGet();
                        LogUtil.debug("Imported item: " + shopId + " -> " + productId + " ($" + buyPrice + ")");
                    } else {
                        skipCount.incrementAndGet();
                    }
                }
            }
        } catch (NoClassDefFoundError | Exception e) {
            LogUtil.error("Failed to import UltimateShop config.", e);
            return "<red>Import failed: " + e.getMessage() + "</red>";
        }

        ItemConfigManager.mutateAndSave(config -> {
            if (config instanceof org.bukkit.configuration.file.YamlConfiguration yaml
                    && itemConfig instanceof org.bukkit.configuration.file.YamlConfiguration snapshot) {
                List<String> existingKeys = new ArrayList<>(yaml.getKeys(true));
                existingKeys.forEach(k -> yaml.set(k, null));
                List<String> incomingKeys = new ArrayList<>(snapshot.getKeys(true));
                incomingKeys.forEach(k -> yaml.set(k, snapshot.get(k)));
            }
        });

        if (EcoBridge.getInstance().isFullyInitialized()) {
            EcoBridge.getInstance().reload();
        }

        return "<green>Import completed.</green> <gray>Added: <white>" + successCount.get()
                + "</white>, Skipped: <white>" + skipCount.get() + "</white>";
    }

    private static double getVaultPrice(ConfigurationSection section) {
        if (section.contains("buy-prices.economy")) {
            return section.getDouble("buy-prices.economy");
        }
        if (section.contains("buy-prices.vault")) {
            return section.getDouble("buy-prices.vault");
        }
        if (section.contains("price.buy")) {
            return section.getDouble("price.buy");
        }

        for (String key : section.getKeys(true)) {
            String lowerKey = key.toLowerCase();
            if (lowerKey.contains("price") && !lowerKey.contains("sell")) {
                if (section.isDouble(key) || section.isInt(key)) {
                    double val = section.getDouble(key);
                    if (val > 0.001) {
                        return val;
                    }
                }
            }
        }

        if (section.contains("economy") && section.isDouble("economy")) {
            return section.getDouble("economy");
        }

        return -1.0;
    }
}
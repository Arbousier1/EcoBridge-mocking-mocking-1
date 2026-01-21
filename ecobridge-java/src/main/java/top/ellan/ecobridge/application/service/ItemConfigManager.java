package top.ellan.ecobridge.application.service;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.util.LogUtil;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 物品配置文件管理器 (ItemConfigManager)
 * 修复说明：
 * 1. 引入 ReentrantReadWriteLock，解决异步写入与同步读取的竞态条件。
 * 2. 确保 save() 和 reload() 操作具有原子性，防止物理磁盘文件损坏。
 */
public class ItemConfigManager {
    private static File file;
    private static FileConfiguration config;
    
    // 引入读写锁，支持多线程安全访问
    private static final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    /**
     * 初始化物品配置
     */
    public static void init(EcoBridge plugin) {
        file = new File(plugin.getDataFolder(), "items.yml");
        if (!file.exists()) {
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                LogUtil.error("无法创建 items.yml", e);
            }
        }
        reload();
    }

    /**
     * 线程安全地从磁盘重载配置
     */
    public static void reload() {
        rwLock.writeLock().lock();
        try {
            config = YamlConfiguration.loadConfiguration(file);
            LogUtil.info("已加载物品配置文件 (items.yml)");
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 获取配置对象
     * 注意：由于 YamlConfiguration 内部不是线程安全的，
     * 若外部拿到此对象后进行 set 操作，仍需自行处理同步，
     * 建议仅通过此类提供的 update 方法进行修改。
     */
    public static FileConfiguration get() {
        rwLock.readLock().lock();
        try {
            return config;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 线程安全地物理保存配置
     */
    public static void save() {
        rwLock.writeLock().lock();
        try {
            if (config != null && file != null) {
                config.save(file);
            }
        } catch (IOException e) {
            LogUtil.error("无法保存 items.yml", e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * [核心修复] 线程安全地动态更新商品的基准价格。
     * 采用写锁确保在修改内容和保存文件的全过程中，没有其他线程在读取或写入。
     */
    public static void updateItemBasePrice(String productId, double newPrice) {
        rwLock.writeLock().lock();
        try {
            if (config == null) return;

            ConfigurationSection settingsSection = config.getConfigurationSection("item-settings");
            if (settingsSection == null) return;

            boolean found = false;
            // 遍历所有商店节点
            for (String shopKey : settingsSection.getKeys(false)) {
                ConfigurationSection shopSection = settingsSection.getConfigurationSection(shopKey);
                if (shopSection != null && shopSection.contains(productId)) {
                    shopSection.set(productId + ".base-price", newPrice);
                    found = true;
                }
            }

            if (found) {
                // 在持有锁的情况下执行物理保存，确保数据一致性
                try {
                    config.save(file);
                    LogUtil.info("配置文件已自动修正：商品 " + productId + " 的基准价已更新为 " + String.format("%.2f", newPrice));
                } catch (IOException e) {
                    LogUtil.error("回写基准价失败: " + productId, e);
                }
            } else {
                LogUtil.debug("未在 items.yml 中找到商品 " + productId + "，跳过自动回写。");
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }
}
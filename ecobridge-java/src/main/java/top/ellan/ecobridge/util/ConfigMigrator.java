package top.ellan.ecobridge.util;

import org.bukkit.configuration.file.FileConfiguration;
import top.ellan.ecobridge.EcoBridge;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * 配置自动迁移工具 (ConfigMigrator v1.1 - Cleaned)
 * 职责：检测配置版本，无损迁移用户设置，同时保留新版配置文件的注释。
 */
public class ConfigMigrator {

    private static final int LATEST_VERSION = 2;

    public static void checkAndMigrate(EcoBridge plugin) {
        plugin.reloadConfig();
        FileConfiguration current = plugin.getConfig();
        
        int version = current.getInt("config-version", 1); // 默认为 1

        if (version < LATEST_VERSION) {
            LogUtil.warn("检测到旧版配置文件 (v" + version + ")，正在执行自动迁移...");
            LogUtil.warn("您的旧配置将被备份，自定义设置将自动合并到新文件中。");
            
            try {
                migrateToV2(plugin, current);
            } catch (Exception e) {
                LogUtil.error("配置迁移失败！请手动备份 config.yml 并删除后重启。", e);
            }
        }
    }

    private static void migrateToV2(EcoBridge plugin, FileConfiguration oldConfig) throws IOException {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        File backupFile = new File(plugin.getDataFolder(), "config.v1.bak");

        // 1. 备份旧文件
        if (configFile.exists()) {
            Files.copy(configFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            LogUtil.info("已创建备份: " + backupFile.getName());
        }

        // 2. 删除旧文件 (为了释放带有完整注释的新文件)
        if (configFile.exists()) {
            configFile.delete();
        }

        // 3. 释放新版默认配置 (带有最新的注释和 config-version: 2)
        plugin.saveResource("config.yml", false);
        plugin.reloadConfig(); // 重新加载，现在 plugin.getConfig() 是全新的 v2 默认配置
        FileConfiguration newConfig = plugin.getConfig();

        // 4. [核心逻辑] 回填用户自定义的旧值
        
        // --- 数据库设置 ---
        copyValue(oldConfig, newConfig, "database.host");
        copyValue(oldConfig, newConfig, "database.port");
        copyValue(oldConfig, newConfig, "database.database");
        copyValue(oldConfig, newConfig, "database.username");
        copyValue(oldConfig, newConfig, "database.password");
        copyValue(oldConfig, newConfig, "database.pool-size");

        // --- Redis 设置 ---
        copyValue(oldConfig, newConfig, "redis.enabled");
        copyValue(oldConfig, newConfig, "redis.host");
        copyValue(oldConfig, newConfig, "redis.port");
        copyValue(oldConfig, newConfig, "redis.password");
        copyValue(oldConfig, newConfig, "redis.server-id");

        // --- 经济参数 (保留用户调优过的 PID 参数) ---
        copyValue(oldConfig, newConfig, "economy.macro.target-velocity");
        copyValue(oldConfig, newConfig, "economy.pid.kp");
        copyValue(oldConfig, newConfig, "economy.pid.ki");
        copyValue(oldConfig, newConfig, "economy.pid.kd");
        copyValue(oldConfig, newConfig, "economy.default-lambda");
        
        // --- 特殊物品覆盖 ---
        if (oldConfig.isConfigurationSection("item-settings")) {
            newConfig.set("item-settings", oldConfig.getConfigurationSection("item-settings"));
        }

        // --- 5. 保存并生效 ---
        plugin.saveConfig();
        LogUtil.info("配置迁移完成！当前版本: v" + LATEST_VERSION);
    }

    /**
     * 辅助方法：仅当旧配置中有值时才复制，否则保留新版默认值
     */
    private static void copyValue(FileConfiguration oldConfig, FileConfiguration newConfig, String path) {
        if (oldConfig.contains(path)) {
            newConfig.set(path, oldConfig.get(path));
        }
    }
}
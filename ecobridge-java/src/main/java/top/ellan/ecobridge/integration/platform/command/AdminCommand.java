package top.ellan.ecobridge.integration.platform.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.application.service.ItemConfigManager;
import top.ellan.ecobridge.application.service.PricingManager;
import top.ellan.ecobridge.util.UltimateShopImporter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * EcoBridge 管理指令 (v1.2.0 - Secured)
 * 职责：
 * 1. 切换影子模式与重载配置。
 * 2. 强制设置商品基准价 (setprice)。
 * 3. 重置商品经济数据 (reset)。
 * 4. 从 UltimateShop 导入商品数据 (import)。
 * * 修复：
 * - [Security] 修复了 Tab 补全的权限泄露问题。
 * - [Fix] 增加了配置读取的空指针防御。
 */
public class AdminCommand implements TabExecutor {

    private static final String PERMISSION = "ecobridge.admin";

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>权限不足: 需要 " + PERMISSION));
            return true;
        }

        if (args.length > 0) {
            String subCommand = args[0].toLowerCase();

            switch (subCommand) {
                case "shadow":
                    boolean newState = !EcoBridge.getInstance().isShadowMode();
                    EcoBridge.getInstance().setShadowMode(newState);
                    String statusColor = newState ? "<green>开启</green>" : "<red>关闭</red>";
                    sender.sendMessage(EcoBridge.getMiniMessage().deserialize(
                        "<gray>[EcoBridge] 影子模式已 " + statusColor + " <dark_gray>(不拦截交易)</dark_gray>"
                    ));
                    return true;

                case "reload":
                    EcoBridge.getInstance().reload();
                    sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<green>[EcoBridge] 配置及物价引擎已重载。"));
                    return true;

                case "setprice":
                    if (args.length < 3) {
                        sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>用法: /ecoadmin setprice <商品ID> <新价格>"));
                        return true;
                    }
                    String setPid = args[1];
                    try {
                        double newPrice = Double.parseDouble(args[2]);
                        if (newPrice < 0 || Double.isInfinite(newPrice) || Double.isNaN(newPrice)) {
                            throw new NumberFormatException();
                        }
                        ItemConfigManager.updateItemBasePrice(setPid, newPrice);
                        sender.sendMessage(EcoBridge.getMiniMessage().deserialize(
                            "<green>成功！已将 <white>" + setPid + "</white> 的基准价强制设为 <yellow>" + newPrice + "</yellow> 并同步至磁盘。"
                        ));
                    } catch (NumberFormatException e) {
                        sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>错误: 价格必须是一个有效的正数。"));
                    }
                    return true;

                case "reset":
                    if (args.length < 2) {
                        sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>用法: /ecoadmin reset <商品ID>"));
                        return true;
                    }
                    String resetPid = args[1];
                    if (PricingManager.getInstance() != null) {
                        // 触发一次 0 数额交易通常用于重置或重新计算，具体取决于 PricingManager 实现
                        PricingManager.getInstance().onTradeComplete(resetPid, 0.0);
                        sender.sendMessage(EcoBridge.getMiniMessage().deserialize(
                            "<green>已重置商品 <white>" + resetPid + "</white> 的波动计数。价格将回归基准价。"
                        ));
                    } else {
                        sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>错误: 定价引擎尚未初始化。"));
                    }
                    return true;

                case "import":
                    sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<gray>正在从 UltimateShop 导入商品数据..."));
                    double defaultLambda = EcoBridge.getInstance().getConfig().getDouble("economy.default-lambda", 0.002);
                    String importResult = UltimateShopImporter.runImport(defaultLambda);
                    sender.sendMessage(EcoBridge.getMiniMessage().deserialize(importResult));
                    return true;
            }
        }

        sendHelp(sender);
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(EcoBridge.getMiniMessage().deserialize(
            "<gradient:aqua:blue>EcoBridge 管理面板</gradient>\n" +
            "<gray>用法:\n" +
            "<yellow>/ecoadmin setprice <id> <price> <gray>- 强制修正商品基准价\n" +
            "<yellow>/ecoadmin reset <id> <gray>- 重置商品价格波动数据\n" +
            "<yellow>/ecoadmin import <gray>- 从 UltimateShop 同步商店物品\n" +
            "<yellow>/ecoadmin shadow <gray>- 切换影子审计模式\n" +
            "<yellow>/ecoadmin reload <gray>- 重载配置文件"
        ));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        // [Security Fix] 只有拥有管理员权限的玩家才能看到补全
        if (!sender.hasPermission(PERMISSION)) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return List.of("setprice", "reset", "shadow", "reload", "import").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("setprice") || args[0].equalsIgnoreCase("reset"))) {
            // [NPE Fix] 增加 null 检查，防止配置文件未加载时报错
            if (ItemConfigManager.get() == null) return Collections.emptyList();
            
            ConfigurationSection section = ItemConfigManager.get().getConfigurationSection("item-settings");
            if (section != null) {
                List<String> ids = new ArrayList<>();
                for (String shopKey : section.getKeys(false)) {
                    ConfigurationSection shop = section.getConfigurationSection(shopKey);
                    if (shop != null) ids.addAll(shop.getKeys(false));
                }
                return ids.stream()
                        .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }
        return Collections.emptyList();
    }
}
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * EcoBridge 管理指令
 * 职责：
 * 1. 切换影子模式与重载配置。
 * 2. [新增] 强制设置商品基准价 (setprice)。
 * 3. [新增] 重置商品经济数据 (reset)。
 */
public class AdminCommand implements TabExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("ecobridge.admin")) {
            sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>权限不足: 需要 ecobridge.admin"));
            return true;
        }

        if (args.length > 0) {
            String subCommand = args[0].toLowerCase();

            switch (subCommand) {
                case "shadow" -> {
                    boolean newState = !EcoBridge.getInstance().isShadowMode();
                    EcoBridge.getInstance().setShadowMode(newState);
                    String statusColor = newState ? "<green>开启</green>" : "<red>关闭</red>";
                    sender.sendMessage(EcoBridge.getMiniMessage().deserialize(
                        "<gray>[EcoBridge] 影子模式已 " + statusColor + " <dark_gray>(不拦截交易)</dark_gray>"
                    ));
                    return true;
                }

                case "reload" -> {
                    EcoBridge.getInstance().reload();
                    sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<green>[EcoBridge] 配置及物价引擎已重载。"));
                    return true;
                }

                case "setprice" -> {
                    if (args.length < 3) {
                        sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>用法: /ecoadmin setprice <商品ID> <新价格>"));
                        return true;
                    }
                    String productId = args[1];
                    try {
                        double newPrice = Double.parseDouble(args[2]);
                        if (newPrice < 0) throw new NumberFormatException();

                        // 1. 物理回写配置文件 (内部已带锁)
                        ItemConfigManager.updateItemBasePrice(productId, newPrice);
                        
                        // 2. 强制 PricingManager 刷新内存快照 (可选：如果需要即时生效而不等待缓存过期)
                        // PricingManager.getInstance().forceRefreshBasePrice(productId, newPrice);

                        sender.sendMessage(EcoBridge.getMiniMessage().deserialize(
                            "<green>成功！已将 <white>" + productId + "</white> 的基准价强制设为 <yellow>" + newPrice + "</yellow> 并同步至磁盘。"
                        ));
                    } catch (NumberFormatException e) {
                        sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>错误: 价格必须是一个有效的正数。"));
                    }
                    return true;
                }

                case "reset" -> {
                    if (args.length < 2) {
                        sender.sendMessage(EcoBridge.getMiniMessage().deserialize("<red>用法: /ecoadmin reset <商品ID>"));
                        return true;
                    }
                    String productId = args[1];
                    // 重置逻辑：通常是将有效供给 (Neff) 归零
                    if (PricingManager.getInstance() != null) {
                        PricingManager.getInstance().onTradeComplete(productId, 0.0); // 传入 0 尝试重置状态记录
                        sender.sendMessage(EcoBridge.getMiniMessage().deserialize(
                            "<green>已重置商品 <white>" + productId + "</white> 的波动计数。价格将回归基准价。"
                        ));
                    }
                    return true;
                }
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
            "<yellow>/ecoadmin shadow <gray>- 切换影子审计模式\n" +
            "<yellow>/ecoadmin reload <gray>- 重载配置文件"
        ));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("setprice", "reset", "shadow", "reload");
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("setprice") || args[0].equalsIgnoreCase("reset"))) {
            // 动态获取当前配置中所有的商品 ID 以供选择
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
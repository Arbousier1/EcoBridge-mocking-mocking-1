package top.ellan.ecobridge.integration.platform.listener;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.coinsengine.api.CoinsEngineAPI;
import su.nightexpress.coinsengine.api.currency.Currency;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.util.LogUtil;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 深度指令劫持系统 (v1.7.6 - Cleaned)
 * <p>
 * 修复日志:
 * 1. [Cleanup] 移除了未使用的 plugin 字段和 PluginCommand 引用。
 * 2. [Refactor] 保持构造函数签名兼容，但内部不再持有 Plugin 实例。
 */
public class CommandHijacker {

    // [Cleanup] 移除了未使用的 private final EcoBridge plugin; 字段
    private static Field knownCommandsField;
    private Map<String, Command> knownCommands;

    public CommandHijacker(EcoBridge ignored) {
        // [Note] 这里的 plugin 参数保留是为了兼容 EcoBridge.java 的调用，
        // 但实际上本类逻辑已改为直接操作底层 CommandMap，不再需要插件实例。
        setupReflection();
    }

    private void setupReflection() {
        try {
            Field commandMapField = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            commandMapField.setAccessible(true);
            SimpleCommandMap commandMap = (SimpleCommandMap) commandMapField.get(Bukkit.getServer());

            if (knownCommandsField == null) {
                knownCommandsField = SimpleCommandMap.class.getDeclaredField("knownCommands");
                knownCommandsField.setAccessible(true);
            }

            @SuppressWarnings("unchecked")
            Map<String, Command> map = (Map<String, Command>) knownCommandsField.get(commandMap);
            this.knownCommands = map;
        } catch (Exception e) {
            LogUtil.error("指令劫持系统反射失败，请检查核心兼容性", e);
        }
    }

    public void hijackAllCurrencies() {
        if (knownCommands == null) return;

        // 直接从 CommandMap 查找指令，不依赖 JavaPlugin API
        Command ecopay = knownCommands.get("ecopay");
        
        if (ecopay == null) {
            ecopay = knownCommands.get("ecobridge:ecopay");
        }

        if (ecopay == null) {
            LogUtil.error("严重错误: 无法在 CommandMap 中找到 'ecopay' 指令，劫持系统启动中止。");
            return;
        }

        for (Currency currency : CoinsEngineAPI.getCurrencyRegistry().getCurrencies()) {
            for (String alias : currency.getCommandAliases()) {
                applyPhysicalHijack(alias.toLowerCase(), ecopay);
            }

            if (currency.isPrimary()) {
                Command finalEcopay = ecopay;
                Arrays.asList("pay", "transfer", "epay").forEach(label -> 
                    applyPhysicalHijack(label, finalEcopay)
                );
            }
        }
    }

    private void applyPhysicalHijack(String label, Command target) {
        List<String> keysToRemove = new ArrayList<>();
        
        for (String key : knownCommands.keySet()) {
            if (key.equalsIgnoreCase(label) || key.endsWith(":" + label)) {
                keysToRemove.add(key);
            }
        }

        for (String key : keysToRemove) {
            knownCommands.remove(key);
        }

        HijackedCommandWrapper wrapper = new HijackedCommandWrapper(label, target);
        knownCommands.put(label, wrapper);
        knownCommands.put("ecobridge:" + label, wrapper);
    }

    private class HijackedCommandWrapper extends Command {
        private final Command auditTarget;

        protected HijackedCommandWrapper(String name, Command target) {
            super(name);
            this.auditTarget = target;
            this.setPermission("ecobridge.command.transfer");
        }

        @Override
        public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
            if (args.length > 0) {
                String sub = args[0].toLowerCase();
                if (!isTransferAction(sub)) {
                    sender.sendMessage("§7[EcoBridge] 审计内核已放行管理类指令。");
                    return true; 
                }
            }
            return auditTarget.execute(sender, label, args);
        }
        
        private boolean isTransferAction(String arg) {
            return arg.equals("pay") || arg.equals("send");
        }
    }
}
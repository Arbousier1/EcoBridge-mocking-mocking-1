package top.ellan.ecobridge.integration.platform.command;

import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/** Simple command wrapper for dynamic registration through Bukkit CommandMap. */
public final class DynamicCommand extends Command {

  private final CommandExecutor executor;

  public DynamicCommand(
      String name,
      String description,
      String usageMessage,
      List<String> aliases,
      String permission,
      CommandExecutor executor) {
    super(name, description, usageMessage, aliases);
    setPermission(permission);
    this.executor = executor;
  }

  @Override
  public boolean execute(
      @NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
    return executor.onCommand(sender, this, commandLabel, args);
  }
}

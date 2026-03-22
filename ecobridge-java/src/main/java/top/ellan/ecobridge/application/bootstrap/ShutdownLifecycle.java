package top.ellan.ecobridge.application.bootstrap;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.application.lifecycle.LifecycleComponent;
import top.ellan.ecobridge.application.lifecycle.LifecyclePhase;
import top.ellan.ecobridge.infrastructure.cache.HotDataCache;
import top.ellan.ecobridge.infrastructure.i18n.I18n;
import top.ellan.ecobridge.util.LogUtil;

/** Shutdown flow orchestration for graceful resource release. */
public final class ShutdownLifecycle implements LifecycleComponent {

  public static final ShutdownLifecycle INSTANCE = new ShutdownLifecycle();
  private static final MiniMessage MM = MiniMessage.miniMessage();

  private ShutdownLifecycle() {}

  @Override
  public LifecyclePhase phase() {
    return LifecyclePhase.SHUTDOWN;
  }

  @Override
  public String componentName() {
    return "shutdown";
  }

  public static void stop(
      EcoBridge plugin, AtomicBoolean fullyInitialized, ExecutorService executor) {
    sendConsole("<yellow>[EcoBridge] " + I18n.tr("shutdown.begin") + "</yellow>");

    if (fullyInitialized.getAndSet(false)) {
      try {
        CoreServiceLifecycle.stop();
        LogUtil.info(I18n.tr("shutdown.core_stopped"));
        HotDataCache.saveAllSync();
      } catch (Exception e) {
        plugin.getLogger().severe(I18n.tr("shutdown.core_error", e.getMessage()));
      }
    }

    terminateVirtualPool(executor);
    InfrastructureLifecycle.stop();
    plugin.getServer().getScheduler().cancelTasks(plugin);

    sendConsole("<red>[EcoBridge] " + I18n.tr("shutdown.done") + "</red>");
  }

  private static void terminateVirtualPool(ExecutorService executor) {
    if (executor == null || executor.isShutdown()) {
      return;
    }
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private static void sendConsole(String msg) {
    Bukkit.getConsoleSender().sendMessage(MM.deserialize(msg));
  }
}

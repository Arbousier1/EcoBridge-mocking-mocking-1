package top.ellan.ecobridge.application.bootstrap;

import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.infrastructure.persistence.database.DatabaseManager;
import top.ellan.ecobridge.infrastructure.persistence.redis.RedisManager;
import top.ellan.ecobridge.infrastructure.persistence.storage.AsyncLogger;
import top.ellan.ecobridge.util.HolidayManager;

/** Infrastructure lifecycle orchestration. */
public final class InfrastructureLifecycle {

  private InfrastructureLifecycle() {}

  public static void start(EcoBridge plugin) {
    DatabaseManager.init();
    AsyncLogger.init(plugin);
    HolidayManager.init();
    RedisManager.init(plugin);
  }

  public static void stop() {
    try {
      if (RedisManager.getInstance() != null) {
        RedisManager.getInstance().shutdown();
      }
    } catch (Exception ignored) {
    }

    try {
      HolidayManager.shutdown();
    } catch (Exception ignored) {
    }

    try {
      if (AsyncLogger.getInstance() != null) {
        AsyncLogger.getInstance().shutdown();
      }
    } catch (Exception ignored) {
    }

    try {
      DatabaseManager.close();
    } catch (Exception ignored) {
    }
  }
}

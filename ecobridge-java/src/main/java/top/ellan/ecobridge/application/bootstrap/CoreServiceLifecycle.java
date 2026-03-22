package top.ellan.ecobridge.application.bootstrap;

import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.application.service.EconomicStateManager;
import top.ellan.ecobridge.application.service.EconomyManager;
import top.ellan.ecobridge.application.service.LimitManager;
import top.ellan.ecobridge.application.service.PlayerMarketPolicyService;
import top.ellan.ecobridge.application.service.PricingManager;
import top.ellan.ecobridge.application.service.TransferManager;
import top.ellan.ecobridge.infrastructure.ffi.bridge.NativeBridge;

/** Core service lifecycle orchestration. */
public final class CoreServiceLifecycle {

  private CoreServiceLifecycle() {}

  public static LimitManager start(EcoBridge plugin) {
    EconomyManager.init(plugin);
    PlayerMarketPolicyService.init(plugin);
    NativeBridge.init(plugin);
    EconomicStateManager.init(plugin);
    PricingManager.init(plugin);
    TransferManager.init(plugin);
    return new LimitManager(plugin);
  }

  public static void reload(LimitManager limitManager) {
    if (EconomyManager.getInstance() != null) EconomyManager.getInstance().loadState();
    if (PlayerMarketPolicyService.getInstance() != null)
      PlayerMarketPolicyService.getInstance().loadConfig();
    if (PricingManager.getInstance() != null) PricingManager.getInstance().loadConfig();
    if (TransferManager.getInstance() != null) TransferManager.getInstance().loadConfig();
    if (limitManager != null) limitManager.reloadCache();
  }

  public static void stop() {
    try {
      if (PricingManager.getInstance() != null) PricingManager.getInstance().shutdown();
    } catch (Exception ignored) {
    }

    try {
      if (EconomyManager.getInstance() != null) EconomyManager.getInstance().shutdown();
    } catch (Exception ignored) {
    }

    try {
      if (TransferManager.getInstance() != null) TransferManager.getInstance().shutdown();
    } catch (Exception ignored) {
    }

    try {
      NativeBridge.shutdown();
    } catch (Exception ignored) {
    }
  }
}

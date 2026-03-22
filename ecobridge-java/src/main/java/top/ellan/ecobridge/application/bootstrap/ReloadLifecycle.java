package top.ellan.ecobridge.application.bootstrap;

import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.application.lifecycle.LifecycleComponent;
import top.ellan.ecobridge.application.lifecycle.LifecyclePhase;
import top.ellan.ecobridge.application.service.ItemConfigManager;
import top.ellan.ecobridge.application.service.LimitManager;
import top.ellan.ecobridge.util.LogUtil;

/** Reload flow orchestration for runtime config and integration refresh. */
public final class ReloadLifecycle implements LifecycleComponent {

  public static final ReloadLifecycle INSTANCE = new ReloadLifecycle();

  private ReloadLifecycle() {}

  @Override
  public LifecyclePhase phase() {
    return LifecyclePhase.RELOAD;
  }

  @Override
  public String componentName() {
    return "reload";
  }

  public static void reload(EcoBridge plugin, LimitManager limitManager) {
    plugin.reloadConfig();
    ItemConfigManager.reload();
    LogUtil.init();
    CoreServiceLifecycle.reload(limitManager);
    PlatformIntegrationLifecycle.reload(plugin);
  }
}

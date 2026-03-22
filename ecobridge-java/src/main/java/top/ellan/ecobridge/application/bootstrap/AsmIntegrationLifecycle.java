package top.ellan.ecobridge.application.bootstrap;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import top.ellan.ecobridge.application.lifecycle.LifecycleComponent;
import top.ellan.ecobridge.application.lifecycle.LifecyclePhase;
import top.ellan.ecobridge.infrastructure.i18n.I18n;
import top.ellan.ecobridge.integration.platform.asm.EcoShopTransformer;
import top.ellan.ecobridge.util.LogUtil;

/** ASM integration lifecycle for UltimateShop bytecode redirection. */
public final class AsmIntegrationLifecycle implements LifecycleComponent {

  public static final AsmIntegrationLifecycle INSTANCE = new AsmIntegrationLifecycle();

  private AsmIntegrationLifecycle() {}

  @Override
  public LifecyclePhase phase() {
    return LifecyclePhase.ASM_INSTRUMENTATION;
  }

  @Override
  public String componentName() {
    return "asm-instrumentation";
  }

  public static void install() {
    LogUtil.info(I18n.tr("asm.init"));
    try {
      Instrumentation inst = getInstrumentation();
      if (inst == null) {
        LogUtil.warn(I18n.tr("asm.instrumentation_missing"));
        return;
      }

      inst.addTransformer(new EcoShopTransformer(), true);
      for (Class<?> clazz : inst.getAllLoadedClasses()) {
        if ("cn.superiormc.ultimateshop.objects.buttons.ObjectItem".equals(clazz.getName())) {
          inst.retransformClasses(clazz);
          LogUtil.info(I18n.tr("asm.objectitem_redirected"));
          break;
        }
      }
      LogUtil.info(I18n.tr("asm.mounted"));
    } catch (Exception e) {
      LogUtil.error(I18n.tr("asm.inject_failed"), e);
    }
  }

  private static Instrumentation getInstrumentation() {
    try {
      Class<?> agentClass = Class.forName("net.bytebuddy.agent.ByteBuddyAgent");
      Method installMethod = agentClass.getMethod("install");
      return (Instrumentation) installMethod.invoke(null);
    } catch (Exception e) {
      return null;
    }
  }
}

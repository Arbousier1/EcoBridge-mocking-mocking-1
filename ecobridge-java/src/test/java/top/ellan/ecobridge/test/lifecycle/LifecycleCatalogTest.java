package top.ellan.ecobridge.test.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import top.ellan.ecobridge.application.lifecycle.LifecycleCatalog;
import top.ellan.ecobridge.application.lifecycle.LifecyclePhase;

class LifecycleCatalogTest {

  @Test
  void startupPhaseOrderShouldMatchRuntimeSequence() {
    List<LifecyclePhase> phases =
        LifecycleCatalog.startup().stream().map(component -> component.phase()).toList();

    assertEquals(
        List.of(
            LifecyclePhase.INFRASTRUCTURE,
            LifecyclePhase.ASM_INSTRUMENTATION,
            LifecyclePhase.CORE_SERVICES,
            LifecyclePhase.PLATFORM_INTEGRATION),
        phases);
  }

  @Test
  void startupFlowStringShouldBeStableForLogs() {
    assertEquals(
        "INFRASTRUCTURE -> ASM_INSTRUMENTATION -> CORE_SERVICES -> PLATFORM_INTEGRATION",
        LifecycleCatalog.startupPhaseFlow());
    assertEquals(
        "infrastructure -> asm-instrumentation -> core-services -> platform-integration",
        LifecycleCatalog.startupComponentFlow());
  }

  @Test
  void fullCatalogShouldContainReloadAndShutdownForLifecycleCompleteness() {
    List<LifecyclePhase> allPhases =
        LifecycleCatalog.all().stream().map(component -> component.phase()).toList();

    assertTrue(allPhases.contains(LifecyclePhase.RELOAD));
    assertTrue(allPhases.contains(LifecyclePhase.SHUTDOWN));
  }
}

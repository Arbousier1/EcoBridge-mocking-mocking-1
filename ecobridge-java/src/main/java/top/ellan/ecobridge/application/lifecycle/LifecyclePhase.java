package top.ellan.ecobridge.application.lifecycle;

/** Ordered plugin lifecycle phases used for orchestration and testing. */
public enum LifecyclePhase {
  INFRASTRUCTURE,
  ASM_INSTRUMENTATION,
  CORE_SERVICES,
  PLATFORM_INTEGRATION,
  RELOAD,
  SHUTDOWN
}

package top.ellan.ecobridge.application.lifecycle;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import top.ellan.ecobridge.application.bootstrap.AsmIntegrationLifecycle;
import top.ellan.ecobridge.application.bootstrap.CoreServiceLifecycle;
import top.ellan.ecobridge.application.bootstrap.InfrastructureLifecycle;
import top.ellan.ecobridge.application.bootstrap.PlatformIntegrationLifecycle;
import top.ellan.ecobridge.application.bootstrap.ReloadLifecycle;
import top.ellan.ecobridge.application.bootstrap.ShutdownLifecycle;

/** Central registry for lifecycle components and their phase order. */
public final class LifecycleCatalog {

  private LifecycleCatalog() {}

  public static List<LifecycleComponent> all() {
    return List.of(
        InfrastructureLifecycle.INSTANCE,
        AsmIntegrationLifecycle.INSTANCE,
        CoreServiceLifecycle.INSTANCE,
        PlatformIntegrationLifecycle.INSTANCE,
        ReloadLifecycle.INSTANCE,
        ShutdownLifecycle.INSTANCE);
  }

  /** Effective startup chain used by EcoBridge#onEnable. */
  public static List<LifecycleComponent> startup() {
    return all().stream()
        .filter(
            component ->
                component.phase() != LifecyclePhase.RELOAD
                    && component.phase() != LifecyclePhase.SHUTDOWN)
        .sorted(Comparator.comparingInt(component -> component.phase().ordinal()))
        .toList();
  }

  public static String startupPhaseFlow() {
    return startup().stream()
        .map(component -> component.phase().name())
        .collect(Collectors.joining(" -> "));
  }

  public static String startupComponentFlow() {
    return startup().stream()
        .map(LifecycleComponent::componentName)
        .collect(Collectors.joining(" -> "));
  }
}

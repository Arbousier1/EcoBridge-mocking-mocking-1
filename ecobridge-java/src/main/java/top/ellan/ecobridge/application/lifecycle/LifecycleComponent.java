package top.ellan.ecobridge.application.lifecycle;

/** Minimal contract for all lifecycle modules. */
public interface LifecycleComponent {

  LifecyclePhase phase();

  String componentName();
}

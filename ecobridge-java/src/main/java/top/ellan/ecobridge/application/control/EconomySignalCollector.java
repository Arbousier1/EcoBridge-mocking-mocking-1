package top.ellan.ecobridge.application.control;

public interface EconomySignalCollector {
    EconomyControlSignals collect(int onlinePlayers, double dtSeconds, double targetVelocity);
}


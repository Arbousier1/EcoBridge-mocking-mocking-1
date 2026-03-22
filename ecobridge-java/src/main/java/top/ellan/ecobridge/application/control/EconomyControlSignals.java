package top.ellan.ecobridge.application.control;

public record EconomyControlSignals(
        double inflationRate,
        double marketHeat,
        double ecoSaturation,
        double m1Supply,
        double faucetRate,
        double sinkRate,
        int onlinePlayers,
        double dtSeconds,
        double targetVelocity,
        double targetM1Supply
) {}


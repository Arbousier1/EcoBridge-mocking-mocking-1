package top.ellan.ecobridge.application.control;

import top.ellan.ecobridge.application.service.EconomyManager;

public class DefaultEconomySignalCollector implements EconomySignalCollector {

    private final EconomyManager economyManager;
    private final double targetM1Supply;

    public DefaultEconomySignalCollector(EconomyManager economyManager, double targetM1Supply) {
        this.economyManager = economyManager;
        this.targetM1Supply = targetM1Supply;
    }

    @Override
    public EconomyControlSignals collect(int onlinePlayers, double dtSeconds, double targetVelocity) {
        double safeDt = Math.max(0.1, dtSeconds);
        double faucetRate = economyManager.pollFaucetMicros() / 1_000_000.0 / safeDt;
        double sinkRate = economyManager.pollSinkMicros() / 1_000_000.0 / safeDt;

        return new EconomyControlSignals(
                economyManager.getInflationRate(),
                economyManager.getMarketHeat(),
                economyManager.getEcoSaturation(),
                economyManager.getM1Supply(),
                faucetRate,
                sinkRate,
                onlinePlayers,
                safeDt,
                targetVelocity,
                targetM1Supply
        );
    }
}


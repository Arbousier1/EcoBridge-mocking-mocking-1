package top.ellan.ecobridge.application.control;

public record MacroControlDecision(
        double lambdaMultiplier,
        double sinkBoost,
        double faucetBoost,
        String reason
) {
    public static MacroControlDecision neutral() {
        return new MacroControlDecision(1.0, 0.0, 0.0, "neutral");
    }
}


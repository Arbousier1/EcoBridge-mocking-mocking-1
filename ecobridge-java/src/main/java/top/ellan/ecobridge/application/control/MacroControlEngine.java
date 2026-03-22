package top.ellan.ecobridge.application.control;

public interface MacroControlEngine {
    MacroControlDecision decide(EconomyControlSignals signals);
}


# Economy Stability Artifacts

This folder is generated from `EconomyStabilityLongRunTest` and committed for human review.

## Files

- `long_run_stability.csv`: full hourly time series for 180 days
- `long_run_stability.svg`: visual curve chart (supply ratio / price index / lambda multiplier)

## Regenerate

Run:

```bash
cd ecobridge-java
./gradlew test --tests "top.ellan.ecobridge.test.simulation.EconomyStabilityLongRunTest"
```

Then copy from:

`ecobridge-java/build/reports/economy-stability/`

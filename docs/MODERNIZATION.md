# EcoBridge Modernization Plan

## Phase 1 (Completed in this refactor)
- Centralized UltimateShop compatibility logic in one resolver:
  - `integration/platform/compat/UltimateShopCompat`
- Refactored price hook flow:
  - `UltimateShopHook` now delegates API-drift handling to compat resolver.
  - `ASMBridge` now uses the same resolver for `shopId` and `productId`.
- Improved test baseline:
  - Added compatibility resolver unit tests.
  - Updated UltimateShop signature sentinel test to match current upstream APIs.
- Improved engineering defaults:
  - Added `.editorconfig`
  - Added `.gitattributes`
  - Enabled JUnit 5 platform in Gradle test task.

## Phase 2 (Recommended next)
- Split plugin bootstrap (`EcoBridge`) into explicit module initializers.
- Replace ad-hoc reflection blocks with small typed adapters and contract tests.
- Add structured error/result types for native bridge operations.

## Phase 3 (Recommended next)
- Introduce integration-test workflow with pinned UltimateShop artifact checks.
- Add static analysis/linting gates for PRs.
- Add release automation (versioning + changelog + artifact publish).


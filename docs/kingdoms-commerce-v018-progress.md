# Kingdoms & Commerce 0.18.0 — progress

Base: main/origin/main `203a976`, confirmed with fetch on 2026-10-13; includes the whole 0.17.0 release. Branch `codex/kingdoms-commerce-v018`. Minecraft 1.21.1, NeoForge 21.1.252, Java 21. Local commits authorized; no merge/push. The user will do the visual validation: do not open a browser or Minecraft client.

## Contracts and owners
- Main: storage block/menu over KingdomEconomy, building/guard/repair administration, alerts/history/advancements, integration and tests.
- commerce_v018: Merchant profession, market and transactions; ProfessionService/ProfessionSavedData/FunctionalBuilding/CitizenRoutine and commerce packets. Bootstrap/locales are integrated centrally.
- citizen_visuals_v018: native humanoid presentation only for registered citizens/Mayor, original skins, labels and animations tied to real work. Own packets and payload; keeps vanilla entities/UUID/AI.
- preview_v018: bounded NBT projection, rotation and validated confirmation; ConstructionService and SettlementLayoutPlanner. Bootstrap/general UI centralized.

## Completed phases
1. Physical storage: single balance, native menu, deposit of the selected stack, construction/food reserves; breaking the block keeps the stock.
2. Market: working Merchant, real inventory/emeralds, persisted treasury/quotas, prices/config and single-use offers.
3–4. Humanoids: native renderer, 80 skins, tools/animation tied to progress, real equipment, labels/card/config.
5. Preview: NBT, rotation/movement, bounds/entrance/costs and confirmation with fresh validation, through the Mayor's local capability.
6. Administration: levels 1–3, housing/slots/capacity/efficiency, repair, equipment, expansion and history through the GUI.
7. Alerts and six advancements: after real transitions, bounded receipts and queue/history; no replay on login.
8. Integration validated. Permanent architectural building changes and advanced economic simulation are not part of this release.

## Final evidence
- 428 JUnit, no failures/errors/skips; test/build successful: build/v018-final-release-build.log.
- 174 GameTests passed; server/Gradle exit 0: runs/gametest-v018-integrated-b, build/v018-integrated-gametest-b.log. Includes nine real SavedData files copied from the synthetic 0.17.0 world, entity/equipment reload and permissions/transactions from two simulated actors.
- First run 170/171: the alerts fixture shared a location; it was isolated. The snapshot test moved to GameTest because it needs loaded door tags, without removing checks. Houses are now upgradable: the 0.17 expectation was updated, keeping cost/limit checks.
- Static/asset/JAR checks passed: 14 modules, 80 skins, storage, HUD/civic/locales. Resource validator passed (three vanilla references). git diff --check passed.
- Artifact build/libs/livingkingdoms-0.18.0.jar, 1,283,077 bytes; SHA256 43af39f1dd941e022d21c18f24379e51b4493cff99a4bccf18b27a4ed90f42c3.
- Commits: 7c65d1e storage; d18cdd2 commerce; 3469cf1 citizens; 6bcb8c1 preview; b29c977 alerts/advancements; b8b7f6e administration/UI. Final QA/documentation checked and ready for delivery. No merge/push, main 203a976 intact.
- The user validates graphics and the client. No runClient or browser; no driver changes. Pending manual: real multiplayer, full save copies, shaders/scales, FPS/TPS/MSPT. The old graphical blocker is not kept as an excuse to stop development.

Details and checklist: [kingdoms-commerce-v018.md](kingdoms-commerce-v018.md).

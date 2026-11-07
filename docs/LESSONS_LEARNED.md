# Lessons learned

Every entry is a real mistake from this project's history, the rule it produced, and how to check it. Read this
before starting a feature; add an entry whenever a test run, review or playtest catches something non-obvious.

Format: **What happened** → **Rule** → **Check**.

## Process

1. **Over-hardening a working API broke eight Builder GameTests** (0.17 `workers-b`, 147/156).
   → Do not tighten preconditions of an existing service "for safety" in the same change as a feature.
   → Separate refactor commits; run the full GameTest suite before and after.

2. **A test tried to mutate Minecraft's immutable player list.**
   → Test fixtures use `FakePlayer`/`UiTestPlayer`; never add or remove from server collections in tests.
   → Services that must follow a player keep a reference (weak) and fall back to a UUID lookup, so fake players work
     without production test hooks (see `ChallengeService.owner`).

3. **A fixture with a nonexistent workplace UUID polluted shared SavedData** and made another test fail depending
   on execution order.
   → Fixtures create only valid data and use unique coordinates (search `found(h,` before picking one).
   → Run GameTests in a fresh `-PgameTestDirectory=runs/<name>`; reused worlds also exhaust the encounter cap.

4. **Fixture collision between two GameTests** (0.18 170/171).
   → Each GameTest owns a coordinate block ≥ 1024 apart from every other test.

5. **A wrong expectation about a historical counter** (pledged food) was caught only by the full suite.
   → Never "fix" a failing assertion by loosening it; read what the production value means first.

6. **Visual work cannot be verified on the dev machine** (AMD `atio6axx.dll` crash in `glfwCreateWindow`).
   → Every UI/visual change ships with a manual QA checklist. Never report visuals as verified when only
     headless tests ran.

7. **External 3D tooling produced wrong UVs, face mapping and clip names** (first Argon 4 warband script).
   → Give the generator exact bone names/pivots, UV formulas and clip naming rules from the existing
     `*.geo.json`/`*.animation.json`, and require a self-check. Validate assets with the asset tests before use.

## Gameplay and UX

8. **The first founding mission was a 64-log/32-stone/4-iron Town Hall before any home**, which players read as
   odd and grindy.
   → First missions must be cheap, immediately useful and teach exactly one mechanic. (0.21: House first.)

9. **"Complete a new kingdom building" hid four separate actions** (plan, supply, assign Builder, wait).
   → Any multi-step objective shows a checklist derived from real state. If the player could ask "how?", the UI
     answers it.

10. **Rewards were only emeralds + reputation**, so mining was often the better use of time.
    → Quests and trials pay things that cannot be crafted (whetstones, tomes, trophies). Check the long-term
      math whenever a reward table changes.

11. **Claiming felt silent.**
    → Every claim plays the ceremony (sound, particles, banner). Silent success is a bug.

12. **Stone means smooth stone, not cobblestone**, by deliberate design and tests. It still surprises new players.
    → Behavior that surprises players but is intentional needs visible text (icon + tooltip), not a code change.
      Changing it is an owner decision recorded in the spec.

## Engineering

13. **Missing/changed NBT modules blocked work silently** (0.17 second pass).
    → Validate footprint/entrance up front and pause with a visible reason instead of failing quietly.

14. **Inherited background rendering blurred custom screens** (0.16 `VillageScreen`, later `MayorOnboardingScreen`).
    → All Living Kingdoms screens extend `KingdomScreen`, which owns background and motion. Shared fixes go there.

15. **Payload enums changed without a protocol bump** risk mismatched client/server decoding.
    → Adding or reordering a payload enum bumps the registrar version (`UiPayloads` "14" → "15" in 0.21).

16. **Crash-unsafe cross-file transactions** (chunk + SavedData) cannot be made atomic.
    → Prefer derived state over stored state; keep transient features (rallies, trials) out of SavedData and make
      them self-cleaning after restart (trial members are refused on load).

17. **Per-tick world scans are the main performance risk.**
    → New behavior hooks existing ticks (profession 20/40-tick work, 10-tick run checks over tracked UUIDs only).

18. **`ItemAttributeModifiers.compute(base, slot)` sums every attribute in the slot** (attack damage *and* attack
    speed), so a 0.21 test read a diamond sword as 4.6 damage instead of 7.
    → Filter modifiers by attribute when asserting a single stat.

19. **Tests hard-coded the old founding order** (`BuildingKind.TOWN_HALL`, iron in the first project) and failed
    when the order became configurable.
    → Fixtures derive kinds/costs from the project under test (`source.project().building()`, `required()`),
      never from an assumed order.

## Adding an entry

Append to the right section with the next number. Keep it to three lines. If the rule changes how features are
built, also update `AGENTS.md` → *Design rules*.

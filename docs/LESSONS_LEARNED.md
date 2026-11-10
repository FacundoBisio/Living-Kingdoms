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

20. **The contract streak decayed twice** (once when the day refreshed, again when the bonus was claimed), so
    missing two days cost three. Caught by `ProgressionLoopTest` before release.
    → Apply time-based decay in exactly one transition; later steps read the stored value.

21. **Finishing a building required the Builder to stand within 0.75 blocks of one exact spot**; when pathing
    could not reach it the project sat at 99–100% showing "Path blocked" forever (playtest, 0.22).
    → Never gate completion on precise NPC positioning. Clear the site (evacuate entities) and finish from the
      normal work range.

22. **Any foreign block in a reserved site permanently failed the project** ("Construction site changed"), and
    grass, snow or a dropped item could stall it with no visible reason.
    → Tolerate natural noise, report real obstacles with position and name, highlight them, and let Builders clear
      them. Every stall must show its reason and a way forward in the UI.

23. **Builder-less (timed) projects had no visual stages**, so the first House popped into existence at the end.
    → Every construction path must reuse the same visible progression (stages + gradual blocks).

24. **The Mayor's crown was a layer on the vanilla VillagerRenderer**, but citizens render through the humanoid
    renderer, so nobody ever saw it.
    → Visual layers go on the renderer that actually draws the entity; check with the in-game model toggle.

25. **Citizen name tags used only the NORMAL text pass**, so they vanished behind blocks and other entities.
    → Follow vanilla: a faint SEE_THROUGH pass plus the NORMAL pass.

26. **Reward items land in the slot the player just emptied**, so tests that checked "main hand is empty" after a
    delivery failed even though the delivery was correct (0.23 equip, 0.24 wishes).
    → Assert on item counts, not on which slot is empty, whenever a reward is granted in the same action.

27. **The first wall cost table charged up to 1.5 stone per ring column**, which exceeded the 256-per-resource
    Kingdom Storage capacity for normal territories, so stone walls could never be paid (0.30 design review).
    → Any cost paid from Kingdom Storage must be clamped below the base capacity; add a unit test for it.
28. **NoAI mobs never call `Entity.move`**, so a GameTest waiting for a Spike Trap's `entityInside` hook on a
    frozen zombie never saw damage (0.30).
    → Drive block entity hooks directly in tests, or let the mob move; don't freeze the entity you test.

29. **A test looked for the caravan with `getTags()`** while the service marks it in persistent data (0.30).
    → Query the same marker the service writes; prefer a public helper over re-deriving the marker in tests.
30. **A retired job keeps its type** (inactive BUILDER), and assignment checked `type == UNASSIGNED`, so a free
    resident could never be hired again and showed a "Remove" button instead (0.31 playtest).
    → "Free" means `!active()`; never gate hiring on the stored type. The UI shows inactive jobs as unassigned.

31. **Grass turns to dirt under a fresh floor**, so construction flagged the ground as an obstacle and Builders
    "cleared" it into an air hole in the finished house (0.31 playtest).
    → Natural ground blocks are interchangeable for site checks; clearing restores the planned block, not air.

32. **Citizen name tags used a fixed offset** while the label grew to three lines and the Mayor got a crown, so
    the text overlapped the head (0.31 playtest).
    → Anchor the lowest line above the head (+ headgear) and stack the other lines upward.
33. **The Mayor took one of the first house's two beds**, so the third founder stayed homeless and could not be
    hired as a Builder or Farmer (0.31 playtest, found by a GameTest reproducing the report).
    → Workers are housed first; the Mayor takes a spare bed or gives theirs up. Reproduce reports in a GameTest first.
34. **A GameTest's "unloaded" remote kingdom sat 120 blocks from another test's fixture** (952048+5000 vs 957168),
    so it failed whenever batching ran both together (0.31).
    → Pick "far away" offsets that clear every fixture coordinate plus its 72-block radius and region ticket.
35. **The HUD counted only assistant Builders**, so a site with one lead Builder said "0 builders" (0.34 playtest).
    → Count every worker a player sees: lead + helpers.

36. **Unaccepted defense offers turned into EXPIRED rows** that could not be removed, and fighting without
    accepting gave no credit (0.34 playtest).
    → Participants are credited automatically; offers nobody took are deleted, never parked in a terminal state.

37. **Residents shared a home's beds without an owner**, so housemates oscillated between beds and the camp
    (0.34 playtest).
    → Give each resident one bed (stable order by citizen id) and point the vanilla HOME memory at it.

## Adding an entry

Append to the right section with the next number. Keep it to three lines. If the rule changes how features are
built, also update `AGENTS.md` → *Design rules*.

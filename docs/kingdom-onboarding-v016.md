# Living Kingdoms 0.16.0

Minecraft 1.21.1, NeoForge 21.1.252, Java 21. Development branch:
`codex/kingdom-onboarding-v016`. No commit, merge or push is part of this update.

## Audit and reuse

The existing settlement UUID and dimension-scoped circular Territory remain the
identity and boundary of a kingdom. Naming replaces only the stored display name;
it does not generate another village or move buildings, citizens or reputation.
The implementation reuses the registered Mayor, server UI sessions, quest
catalog and receipts, construction projects, functional building registry,
regional encounter queries, defense events and existing K/J/H controls.

Two concrete problems were found before implementation:

- Opening the Mayor dialogue previously completed first contact immediately;
  boards could offer resource requests before the player agreed to help.
- The HUD reserved the configured height of closed chat above the hotbar. At
  common GUI scales this made its rectangle invalid and hid it entirely.
  An equal-target opacity update could also alternate its fade direction.

The client also ignored the initial construction-marker screen snapshot. Initial
construction, immigration and citizen views now use the existing screen router.
Closed session UUIDs are retained in a bounded client history so delayed replies
cannot reopen explicitly closed screens.

## Mayor and kingdom registration

The personal conversation is persisted as WELCOME → PEOPLE → NEEDS →
RESPONSIBILITIES → NAMING → JOINED. The player explicitly chooses to help,
confirms responsibilities, then names the kingdom. Closing/reopening resumes the
same step. Joined players receive the ordinary Mayor conversation.

Names are normalized Unicode, 1–32 characters, accepting letters, digits, spaces,
apostrophes and hyphens; formatting codes and controls are rejected. Names of
newly registered kingdoms must be distinct without regard to case. Registration
and personal progress use `livingkingdoms_onboarding.dat` in the Overworld's
SavedData, including kingdoms in other dimensions. Registration is shared;
other players complete their own conversation and join the existing name. The
first valid server submission wins. Repeated packets cannot register twice.

Every change checks the server thread, session owner/token, recorded living Mayor,
dimension, territory, reach and current step. Formal quest and management actions
are gated on personal membership. The remote journal continues to allow viewing
and tracking, with local acceptance/claim checks unchanged. Trusted operator/debug
APIs remain available for development.

Existing players with accepted/completed quests or completed first contact are
enrolled lazily without renaming their settlement or replaying old rewards.
Reputation alone does not skip onboarding. Quest schema 6 preserves old quest,
tracking and reward receipts; a migration receipt skips only the three new
prerequisite steps for an existing chapter. Existing unfinished projects retain
their complete saved native blueprint instead of adopting a new template.
An old resource-only abstract settlement without a Mayor keeps its receipts and
membership without choosing the main chapter; a later Mayor-backed realm can
still become its anchor.

## Meaningful main progression

The established global main chapter remains anchored to the first Mayor-backed
kingdom for each player. Later kingdoms retain independent membership, regional
requests and rewards, using skills already learned in that chapter.

| Step | Requirement and purpose |
| --- | --- |
| A promise to the kingdom | Complete the Mayor introduction and registration. |
| Meet our neighbors | Accept, speak to an active registered resident, then claim locally. Ordinary unregistered villagers do not count. |
| Tools for a growing kingdom | Existing iron delivery and its original one-time reward. |
| Room for our future | Complete a new persisted construction project, then return to the board. A project completed before this offer appeared does not count. |
| Eyes on the horizon | Accept, right-click a non-air block inside a registered active Watchtower, then return to the board. |
| Protect our roads | Defeat the actual nearby Pillager Patrol bound to the quest, with meaningful contribution. |
| A safer kingdom | Return to the chapter kingdom and claim the final reward once. |

Resource requests unlock after the iron step; building requests after development;
combat requests and Local Defense after the Watchtower briefing. Existing active
quests remain actionable. Pillager and Undead combat/reward rules are reused.
A patrol quest stays locked until a real eligible nearby party exists; it does
not create enemies to fill a quest slot.

AVAILABLE, ACTIVE and COMPLETED instances persist in the existing quest store.
LOCKED rows are stable projections of persisted chapter prerequisites, not extra
reward-bearing instances. Each row explains its prerequisite; only authoritative
available/active instances can be accepted or claimed. Development readiness is
refreshed on local Mayor/board interactions against bounded project receipts,
without adding a construction scan to each HUD tick.

## HUD and territory display

The HUD uses the upper left of the GUI, with local settlement information above
the tracked quest. Leaving the territory removes only the local section. Entering
again, reconnecting, respawning or changing dimension refreshes server snapshots;
quest tracking remains persisted. Current resource/member progress comes from
server snapshots. Lines are cached when snapshots change and clipped to fit.
Actual boss bars shift the HUD when needed; open screens and F3 suppress it.
H toggles the HUD; K opens the local kingdom UI; J opens the remote journal. All
three controls remain remappable.

`/kingdom chunks` selects the containing allied kingdom, or the nearest allied
center within 512 blocks in the same dimension. It reports name, center and the
existing territory radius, then sends a personal 60-second overlay.
`/kingdom chunks off` clears it immediately.

- Cyan boxes show chunks intersecting the territory; edge chunks may be partial.
- Gold lines show the actual circle, distinct from chunk boundaries.
- Rendering is a player-height slice of nearby already-loaded chunks, capped at
  121 chunk boxes and 256 circle segments. Geometry updates on a chunk transition
  or a new snapshot. It creates no blocks, entities or chunk tickets.
- The overlay expires and clears on logout or dimension change. It is per player
  in singleplayer and multiplayer.

Containing-allied lookups now reuse the settlement metadata index. The overlay
uses explicit command queries; onboarding uses interactions; no worldwide
entity scans or new global per-tick progression loop were introduced.

## Structures, assets and validation

The core and standalone Town Hall keep their old footprints/door contracts while
adding paired tall windows, porch depth, stone sills, furniture and a clear aisle.
Only newly placed templates change. The Quest Board has wooden feet, posts,
frame/canopy and three inked paper textures, plus its native 1.21.1 item model.
The screen shows readable state marks, objectives, prerequisite explanations,
rewards and bounded progress. See the separate
[structure/board graphical checklist](qa-v016-structures-board.md).

Validation on 12 October 2026:

| Check | Result |
| --- | --- |
| JUnit | 364 tests passed; zero failures, errors or skips. |
| NeoForge GameTests | All 143 required tests passed with the final migration guard. |
| Gradle build | Produced `build/libs/livingkingdoms-0.16.0.jar`. |
| Packaged assets | All nine board/civic assets match their source bytes; all ten modules reproduce deterministically. |
| Metadata/package | Version 0.16.0, Minecraft 1.21.1 and NeoForge 21.1.252 pins verified; current locale files included; test mod excluded. |
| Whitespace | `git diff --check` passed. |

The complete suite used an isolated directory:

```powershell
.\gradlew.bat -PgameTestDirectory=runs/gametest-v016-validation test build runGameTestServer --console=plain
python tools/audit_v016_assets.py --jar build/libs/livingkingdoms-0.16.0.jar
python tools/generate_quest_board_assets.py --check
python tools/generate_settlement_modules.py --check
git diff --check
```

The default remains `runs/gametest`. Repeated runs there had accumulated 302
encounter records, exceeding the real 256-party natural-spawn budget; two old
spawning tests correctly refused more parties. The optional `gameTestDirectory`
property lets validation use a new world while keeping prior worlds and all
production limits/assertions. Choose an unused directory for another isolated run.
The previous 0.15.1 JAR, normal development worlds and installed skills remain.

Compilation and tests do not certify Minecraft lighting, GPU rendering, screen
appearance or visual readability. Those checks remain pending on the target
gaming PC.

## Gaming-PC QA

Use matching 0.16.0 clients/server and a fresh world plus a copy of a 0.15.1 world.

1. Before talking to the Mayor, open the board or a construction marker. Viewing
   is allowed; accepting, depositing, planning, assigning and immigration actions
   must require formal help. K/J must not bypass this requirement.
2. Read each introduction screen in Spanish and English. Close midway, save and
   reopen: the same personal step should resume. Test mouse and Tab/Enter focus.
3. Confirm responsibilities before naming. Try empty, formatting-code and invalid
   punctuation names, then a valid accented name. Verify the welcome message and
   identical name in Mayor, board, journal, HUD and `/kingdom chunks`.
4. Reopen normal conversations without replaying the introduction. In multiplayer,
   progress two players simultaneously: one shared name, separate introductions,
   no duplicate village, reward or ability to reuse another player's session.
5. Complete each new main step. Unregistered villagers and old completed buildings
   must not satisfy it. Supply/assign a Builder to complete a new project; refresh
   the board. Inspect an actual Watchtower; verify defense instructions and unlock.
6. Track a quest. Walk out and back: only the local HUD section disappears. Save
   and reopen, reconnect, die/respawn and change dimension. Verify tracked identity
   and progress. Use H, remap H/K/J, and compare F3, open screens and real boss bars.
7. Test GUI scales 1/2/3/4/Auto, fullscreen and a small window. Check upper-left HUD,
   wrapped dialogue, naming field, tooltips, scrolling, rewards and button labels.
8. Use `/kingdom chunks` inside and nearby; compare cyan chunks with F3+G and gold
   border with the actual saved radius. Move between loaded chunks; test expiry,
   `off`, dimension change and two independent multiplayer overlays.
9. Fight real Pillager/Undead encounters and Local Defense after their prerequisites.
   Confirm contribution, local claims, repeated-click safety and journal read-only
   restrictions still hold. A Guard-only victory gives no personal combat credit.
10. Load the old-world copy: keep names, citizens, assignments, tracked/active quests,
    rewards and player edits. Resume an unfinished 0.15.1 Town Hall using its saved
    blueprint. Complete the separate house/board checklist in all four rotations.

## Limits

There is one main chapter per player, not a new repeatable chapter per kingdom.
Development recognizes a completed project after the offer appeared and checks
readiness on interaction. The Watchtower briefing checks registered geometry,
not an expensive per-block reconstruction of the tower. The chunk overlay shows
nearby loaded territory, not every distant chunk of a large kingdom at once.
GPU acceptance and a real old-world Save & Quit/reopen remain manual. No new wars,
diplomacy, custom NPC models or natural encounter spawning rules are included.

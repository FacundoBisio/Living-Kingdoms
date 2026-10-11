# Living Kingdoms 0.15.1 — threat approach, tracked quest HUD and Controls

This patch builds on 0.15.0. Builder stages/queues, Guards, Watchtowers, existing factions, encounters, quest rewards and settlement saves remain in place.

## Threat approach

Local defense commands propose at most 24 outside origins. The origin and complete member footprint must fit the configured 70–140 block annulus, stay outside allied territory, and pass the existing loaded-chunk, solid-ground, fluid, slope, world-border and entity-collision checks. Registered allied building footprints are contained in their territory, which is excluded with group clearance. A safe site is required; the command refuses if none exists. No terrain is modified and no chunks are forced to load.

The existing conservative natural encounter spawner also links eligible new parties near an allied settlement to this approach lifecycle. Its cooldowns, capacity limits, player clearance and nighttime rules stay in force. Parties farther away remain ordinary roaming encounters. This patch adds no worldwide spawn loop.

One shared persisted event per settlement progresses through **APPROACHING → DETECTED → ACTIVE → RESOLVED / FAILED**. APPROACHING is unknown danger: the settlement remains SAFE and no defense quest is offered yet. The first loaded member within detection range produces one local warning and permits a Local Defense offer. Arrival inside the existing Guard defense range activates combat. Intercepting and defeating the linked group outside town can also resolve the defense.

Both Pillager patrols and Undead hordes use local native navigation goals. Every 80 entity ticks, an idle member proposes a step of at most 12 blocks toward a small rally area near the center. Existing combat handles nearby opponents. In ACTIVE, target acquisition and continued pursuit are bounded to territory radius plus the objective margin; idle members return toward the rally area. Travel permits immediate self-defense within 12 blocks. Members walk; the mod never teleports them into town.

Unavailable nearby chunks pause movement. Consecutive navigation failures on loaded terrain close the event as ROUTE_BLOCKED, without killing mobs or awarding rewards. Existing event expiry also bounds unavailable routes. Remaining mobs then retain ordinary encounter behavior. Route retry counters are temporary; persisted event expiry survives reload.

Watchtower detection defaults to approximately 100 blocks versus 60 without a tower; existing Security adds up to four blocks. Completed towers contribute once, without stacking. The persisted phase transition prevents repeat warnings after subsequent member checks or reload. Detection may be delayed by the existing bounded interval. `detectedAt` remains the event creation timestamp for compatibility; APPROACHING has no activation timestamp yet.

## Personal tracked quest and HUD

Each player may have several ACTIVE quests and at most one **tracked** quest. Accepting a quest tracks it automatically. TRACK replaces the choice; UNTRACK clears it. The indicator appears in the quest detail and narration. Claiming/completing, failing or expiring the tracked quest clears the pointer; completing another quest keeps the current choice. An objective ready for a local claim remains ACTIVE and tracked until claimed.

The tracked UUID is server-owned and saved with personal quests. It cannot point to another player's quest or an inactive quest. Quest schema 5 loads schemas 1–4; older active quests stay active and may be tracked manually. Defense schema 2 accepts schema 1 events. Existing entity/encounter persistence is reused. Client and server must both run 0.15.1 (UI protocol 8).

Inside an allied territory, the HUD shows its name, personal reputation band/progress and population/housing, with the tracked quest below. Outside, only the tracked quest remains. Outside with no tracked quest, there is no widget. Reputation bands use ten reputation points per displayed level; this is presentation of the existing personal reputation value, not a settlement tier system or a new reward rule.

Objectives use server inventory counts, linked-party remaining members or existing quest completion state. Multi-resource quests show the first unmet resource. Ready objectives use green; local danger uses red. The panel uses the shared parchment, dark text and gold Minecraft theme. Text truncates to fit, using the GUI-scaled left safe area above chat and vanilla bars and away from subtitles. It hides when a screen/F1 is open or the available surface is too small. H has a subtle opacity transition; content changes are deliberately simple.

Server HUD snapshots update at most once per player per second and are sent only when their contents change. Territory lookup uses existing metadata indexes, and personal journal traversal is bounded by the existing quest limit. Rendering does not query settlements, scan entities or recompute world distances.

## Controls and remote permissions

All three entries are registered in Minecraft **Options → Controls → Living Kingdoms** and can be remapped:

| Default | Action | Rule |
| --- | --- | --- |
| K | Kingdom / Settlement | Inside: existing management views. Up to 16 blocks outside territory: information only. Farther away: concise feedback. |
| J | Quest journal | Personal quest list anywhere; TRACK/UNTRACK permitted for own ACTIVE quests. |
| H | Toggle settlement/quest HUD | Independent client preference, persisted in client config. |

Opening the journal does not generate offers. Remote ACCEPT and CLAIM are rejected on the server even if a client forges a request. Accepting and claiming still require the existing reachable physical Quest Board/Mayor interaction and all original inventory, ownership, contribution and one-time reward checks. Journal sessions have individual tokens, dimension validation and a ten-minute expiry; shortcuts have a small server throttle. Other open screens consume their own keys.

Threat progress is shared; quest tracking and HUD visibility are personal. Existing meaningful combat contribution and reward receipts remain authoritative. Debug encounters grant no rewards unless an operator explicitly chooses `reward_test`. Guard-only victories still restore SAFE without passive player rewards.

## Development commands

Run as an operator inside an allied settlement, with safe outside terrain already loaded:

```text
/kingdom defense trigger pillager approach
/kingdom defense trigger undead approach
/kingdom defense info
/kingdom defense resolve
```

The existing `trigger`, `trigger pillager` and `trigger undead` aliases also begin outside. `trigger pillager reward_test` and `trigger undead reward_test` explicitly permit testing the existing reward flow. `resolve` cancels the event; it fabricates neither kills nor victory. Generic `/kingdom encounter spawn ...` remains the independent roaming-encounter debug fixture rather than a settlement-defense shortcut.

## Configuration

Common configuration `[defense]`:

| Key | Default | Meaning |
| --- | --- | --- |
| minimumApproachDistance | 70 | Outside origin/member distance floor |
| maximumApproachDistance | 140 | Outside origin/member distance ceiling |
| approachMovementSpeed | 0.65 | Native navigation movement multiplier |
| normalDetectionRange | 60 | Baseline detection radius |
| watchtowerDetectionBonus | 40 | Completed tower bonus |
| maximumDetectionRange | 160 | Cap after bonuses |
| hostileObjectiveMargin | 16 | Extra active objective radius beyond territory |
| routeFailureLimit | 8 | Consecutive loaded-route failures before safe closure |
| detectionIntervalTicks | 80 | Existing detection/reconciliation cadence |
| eventDurationTicks | 12000 | Existing expiry, including approach |

Client `hudEnabled=true` is toggled by H. Existing common configuration values are respected: an upgraded save with the old Watchtower bonus/cap retains those values until edited. Inverted approach bounds, oversized territory without room in the annulus, or an unloaded/blocked annulus cause refusal rather than unsafe spawning.

## Validation and limitations

Validation on 2026-10-10 with Java 21 and NeoForge 21.1.252:

- `test`: **341 unit tests passed** (all 328 previous tests plus 13 new regressions).
- `runGameTestServer`: **137 required GameTests passed** (all 130 previous tests plus seven new regressions).
- `build` and `compileGameTestJava`: passed; artifact `build/libs/livingkingdoms-0.15.1.jar`.
- `git diff --check`: passed; new files also checked for trailing whitespace.
- English/Spanish locale key parity: 416 keys; all previous translations preserved.

Existing regressions remain; the defense command fixture now supplies outside terrain and checks APPROACHING before exercising its original reward assertions. The complete rerun passed after correcting a new fixture coordinate collision and retaining the existing settlement alert cooldown for approach warnings.

The new coverage includes annulus limits, circular pursuit bounds, persisted phase transitions and blocked routes, personal tracking/reload/completion, native remappable Controls objects, GUI safe areas, actual native approach movement, outside building exclusion, unloaded-site refusal, actual HUD payloads/deduplication, remote permission checks and multiplayer tracking. Existing Watchtower GameTests verify earlier detection using real completed construction.

Graphical gameplay was not run on this PC. Native key remapping is tested as a Controls object, while rendering, visual focus and actual keyboard use need the gaming-PC checks below. On extremely small GUI surfaces or unusually tall chat settings, the widget hides to avoid overlap. Vanilla terrain navigation may refuse difficult slopes, caves or water; this patch does not add formations, terrain alteration or sophisticated tactical routing. No distance arrow, fade-out of removed individual rows, raids, siege, conquest, professions or settlement tiers are added.

## Future gaming-PC QA

Use a copy of a 0.15.0 save with this version on every client/server. Repeat visual checks at 320×240 GUI-scaled minimum, normal and large resolutions, several GUI scales, Spanish/English, chat and subtitles. Also remap K/J/H in Controls and repeat with two players tracking different quests.

1. Enter an allied settlement.
2. Verify its name, reputation progress and population/housing HUD; check vanilla bars/chat/subtitles remain clear.
3. Accept a normal quest through the reachable physical Board or Mayor.
4. Verify the tracked quest appears below settlement information and shows real progress.
5. Leave settlement territory.
6. Verify only the tracked quest remains; settlement information disappears.
7. Press H.
8. Verify the entire widget toggles independently and toggles back; remapped H works.
9. Press J while away from the settlement.
10. Verify the journal opens, TRACK/UNTRACK work and remote ACCEPT/CLAIM are disabled.
11. Return inside/near the settlement, close other screens and press K.
12. Verify settlement UI opens; nearby outside is read-only; farther away gives feedback.
13. Trigger `/kingdom defense trigger pillager approach` inside town; load nearby terrain by ordinary exploration first.
14. Verify all party members begin outside the village in the configured annulus, away from buildings and citizens.
15. Observe native walking and the preparation window; inspect `/kingdom defense info` as phases advance.
16. Verify one Watchtower warning occurs earlier than without a tower; repeat without a completed tower.
17. Verify arriving enemies fight Guards/citizens locally and do not pursue a player indefinitely outside the objective region.
18. Complete a tracked quest and claim locally; for defense rewards use an explicit `reward_test` encounter and meaningful combat participation.
19. Verify HUD progress/clear state updates once, with no duplicate rewards or alerts.
20. Track another ACTIVE quest, Save & Quit, reconnect and verify the same personal choice and H preference persist.

The next milestone should validate and tune these preparation windows, navigation speed and HUD layout in actual survival multiplayer before adding larger combat systems.

# 0.38 manual playtest checklist

Run on a real client (the dev machine cannot render: AMD driver crash). Use a copy of an existing 0.37 world
and one fresh world. Tick each line; note the step number for anything that fails.

## 1. Existing 0.37 world (compatibility)
- [ ] World loads without errors; kingdom, residents, storage and quests are as before.
- [ ] A wall with 3 gates: the 210°/330° gates are closed and east/west gates are open.
- [ ] Lintels do not rise after upgrading the tier or cycling gates twice.
- [ ] Reducing the gate count closes the old openings.

## 2. Walls on real terrain
- [ ] Raise a palisade over flowers, tall grass, saplings and a few trees: the wall sits on the ground, trees on
      the line are felled and Wood is refunded.
- [ ] The ring crosses a river or pond up to 8 blocks deep.
- [ ] A house or lamp post next to the ring is not broken, and is not "repaired" as wall.
- [ ] Gates face north/south/east/west; no tower blocks a gate.
- [ ] Walk away while the ring builds: it still reaches 100%. Walk the ring afterwards: missing columns appear
      and Kingdom Storage is not charged for them.
- [ ] Break three wall blocks, restart the world, wait about a minute near them: they are restored for one
      Wood/Stone each.
- [ ] Order towers/gatehouses where they cannot fit: the order is refused and nothing is charged.
- [ ] Expand the territory, then order walls: the ring moves to the new border, the old ring disappears
      gradually and 50% is refunded. At tier 3 the button reads "Walls: move to the border".

## 3. Finding and understanding walls
- [ ] Mayor main page has a *Walls* button (locked with a reason before the kingdom is established).
- [ ] Mayor main page has Leave, a small "R" (Refresh) and "?" on the last row, none overlapping.
- [ ] The Walls tab shows cost, column progress with a percentage, the tier ladder and how it works without
      hovering.
- [ ] *Show on the ground* draws ring, gates and towers for about 30 seconds before paying.

## 4. Construction
- [ ] New plots are never planned on the wall or in front of a gate; Builders do not break the wall.
- [ ] A site view shows the checklist: plan, supply, assign Builder, wait.
- [ ] The HUD row says the next step ("Needs 12 Wood…", "Assign a Builder", or the real stall reason).
- [ ] Blocking a site (place blocks in the footprint) sends one chat message, not a stream.
- [ ] The Stone notice is visible: Stone is the smelted block; cobblestone is not accepted.
- [ ] Finishing the Town Hall converts the camp into the Founders' Lodge: nobody is stuck inside, items stored
      in the camp lie outside, residents still sleep in the lodge beds.

## 5. Missions and trials
- [ ] Track a hunt/party mission: the HUD shows an arrow and distance that update as you turn and walk.
- [ ] With nothing tracked, the HUD shows the current chapter mission.
- [ ] Before unlocking trials, the board's Trials button opens the picker and each locked tier shows
      "Requires the main quest: …" in the row (text not cut off, rewards still visible).
- [ ] During a trial the bar shows distance against 48 blocks and time left, turning red near the edge.
- [ ] "How do I…?" opens from the Mayor page and the journal and lists four guides.

## 6. Performance and cleanup
- [ ] `/kingdom perf` stays under 1 ms/tick with the wall complete.
- [ ] Leave the world and re-enter in the same session: no stale "blocked" sites, previews or wall repairs.

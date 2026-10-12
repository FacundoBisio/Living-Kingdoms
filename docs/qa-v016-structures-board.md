# 0.16.0: mayor house and Quest Board graphical QA

The automated resource and geometry checks do not confirm Minecraft lighting,
model appearance, or screen readability. Complete this checklist on the target
Minecraft 1.21.1 / NeoForge client, using a disposable world or a saved-world copy.

## New settlements and construction

- Generate a fresh settlement with `/kingdom settlement generate here debug` on
  suitable terrain. Inspect its core hall from the front, sides and rear: paired
  tall front windows, inset door, porch posts, stone sills and coherent roof.
- Walk through the south entrance; confirm headroom and the clear aisle before
  the records desk. Check cartography table, bookshelves, chair and lighting.
- Inspect a newly completed standalone town hall as well as the generated core.
  Their original footprints and door/path contracts are preserved.
- Inspect all four building rotations and a gently uneven terrain site; confirm
  stairs, door halves, panes and doorstep connect to the existing paths.
- Load a copy of a 0.15.1 world where the player modified the mayor house. Confirm
  those placed blocks remain untouched. Existing construction plans persist their
  entire native blueprint, so a template update does not replace an old plan.

## Board model and interaction

- `/give @s livingkingdoms:quest_board`; inspect its inventory item and dropped item.
- Place boards while facing each cardinal direction. Notices should face the
  placing player, with visible wooden feet, posts, frame and small roof cap.
- Inspect close up and from a distance, in sun and lantern light. Confirm the
  three inked notices/wax seals are readable as paper, with no missing-texture
  checkerboards, stretched faces, flicker or incorrect lighting.
- Right-click the board from front/back and while holding an item. Check that
  the outline follows its orientation and the same server-validated UI opens.

## Board screen

- Test English and Spanish, GUI scales 1 through 4 and Auto, a small window and
  fullscreen. Check title, list, footer controls and scroll arrows stay legible.
- Compare locked, available, active and completed quests: each has a textual state
  plus a distinct mark/color. Locked quests show their prerequisite explanation.
- Test a multi-resource task and an active defense task. Their aggregate progress
  should match the server snapshot; each resource row should show its own count.
- Check titles, objectives, emerald/reputation rewards and tracked-quest marker.
  Use long translated titles, tooltips, mouse wheel, Page Up/Down, Tab and Enter.
- Accept locally; confirm the task moves to Active. Complete and claim once.
  Open the remote journal and confirm local accept/claim controls remain disabled.
- Check narrator output and visible keyboard focus. No animation is required.

## Automated packaging evidence

After the release build, run:

```powershell
python tools/generate_quest_board_assets.py --check
python tools/generate_settlement_modules.py --check
python tools/audit_v016_assets.py --jar build/libs/livingkingdoms-0.16.0.jar
```

The JAR audit checks exact bytes for the model, four texture PNGs, blockstate,
legacy item model, core and standalone town hall. Unit tests check packaged asset
references/PNG pixels and snapshot progress; GameTests check real rotated doors,
windows, approach clearance and directional interaction geometry.

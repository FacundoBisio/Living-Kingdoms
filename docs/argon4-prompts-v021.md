# Argon 4 prompts — 0.21 assets

0.21 ships with placeholders: a generated 16×16 Royal Whetstone icon and vanilla Vindicators for the trial bosses.
Paste these into Argon 4 when replacing them. Deliver a ZIP; integration must pass `ArgonAssetCompatibilityTest`
and the warband asset tests before use (see LESSONS_LEARNED #7).

## Royal Whetstone (item icon)

```
Create a 16x16 Minecraft Java item texture (PNG, RGBA, transparent background, no anti-aliasing, vanilla 1.21
palette style). Subject: a royal whetstone — an elongated blue-grey sharpening stone lying diagonally from
bottom-left to top-right, 3 px thick, darker outline on the lower-right edge, two light highlight pixels on the
upper-left, and a 2 px gold band (#E8B840 with #A8761E shade) wrapped around its middle like a crown seal.
Must read clearly at 1x in a hotbar slot next to vanilla flint and an iron ingot. Output file name:
royal_whetstone.png. Do not include text or a frame.
```

## Trial bosses (entity skins on the existing warband soldier geometry)

```
Using the existing Living Kingdoms warband soldier geometry and UV layout (bone names, pivots and UV boxes exactly
as in warband_soldier.geo.json; do not change geometry), produce two 64x64 skin textures:
1) siege_captain.png — illager captain in dented iron plate over a crimson tabard, black-and-gold sash, scarred
   face, iron helmet with a short red plume.
2) the_warlord.png — towering warlord in blackened netherite-style plate with gold trim, a torn black cape,
   horned helm with glowing amber eye slits, crimson banner motif on the chest.
Keep vanilla pixel density, no gradients beyond 3 shades per material, readable silhouettes at 16 blocks.
Self-check: every UV face lies inside its box, no overlapping islands, transparent pixels only where the vanilla
illager template is transparent. Deliver PNGs plus a short note listing the UV boxes you painted.
```

## Mayor face and outfit (0.23)

The Mayor already wears an in-code gold crown (a render layer, no texture needed). The face/outfit texture is
the part to replace:

```
Using the existing Living Kingdoms citizen geometry and UV layout (citizen.geo.json; do not change bones, pivots
or UV boxes), paint one 64x64 skin: mayor.png. A dignified elder leader: warm skin tone, short neat grey beard and
moustache, kind but determined eyes with visible white highlights (2 px eyes, no huge anime eyes), defined brows,
no open mouth. Outfit: deep royal-blue long coat with gold trim and buttons, crimson sash across the chest, white
collar, dark trousers, brown boots. Leave the top 2 rows of the head plain hair (a crown is rendered on top).
Vanilla pixel density, max 3 shades per material, readable at 16 blocks. Self-check: every painted pixel lies
inside its UV box, no overlapping islands, overlay layer transparent except the coat collar and sash.
```

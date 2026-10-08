# Village interface design

Applied with [UI UX Pro Max](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill), installed in the user's Codex skills directory. This is a Minecraft 1.21.1 / NeoForge Java screen, not JavaFX or a web page. The skill's verified `ux` searches for **keyboard focus modal** and **loading button feedback** informed the implementation, along with its accessibility, interaction, layout and typography guidance. No unsupported stack recommendation or generated website design system was imported.

## Shared visual language

- Opaque parchment reading surface, timber frame, blue village accents and warm gold selection marks. Shared semantic colors live in `VillageTheme`.
- Minecraft's native pixel font and item rendering remain the typography and icon system. Controls retain native Button keyboard input, click sounds, tooltips and narration through `VillageButton`.
- Body text uses ink on parchment (9.34:1); secondary text 5.18:1; selected-button text 6.70:1; error text 5.51:1. These are calculated color-pair ratios, not a claim of complete accessibility certification.
- Selected tabs and quest rows have both a persistent inset marker and narrated selected state. Hover/focus adds a separate outline without moving content. Full labels remain in tooltips and narration; native text scrolling handles compact buttons.
- The Mayor dialogue and Quest Board share the same shell and controls. Other ordinary villagers keep their vanilla interactions. Future important NPC roles and interactive settlement blocks can reuse the theme without inventing new gameplay now.

## Interaction and content

- Tab / Shift+Tab traverse native controls; Enter / Space activate; Escape and Leave exit. Page Up / Page Down, mouse wheel and explicit arrow buttons read overflow text, including Mayor dialogue.
- The board reserves a separate footer so its quest reader never runs behind Accept / Claim. Mission titles and states lead the detail pane, followed by objective, supplies and rewards.
- Requests stay visible after acceptance by moving to Active. Claiming shows the completed entry in its category. Server rejection resets the reader to the visible error. Both transitions wait for the authoritative response.
- A request disables repeat actions and displays `Working...` / `Procesando...`. Closing remains available. Focus transfers to replacement controls after a screen rebuild when the corresponding control remains active.
- Quest narration includes title, state, objective, resource progress and rewards. NPC narration includes role/name and dialogue. Icons are paired with visible text.
- Spanish (Argentina) and English have matching keys. Accents are stored as UTF-8; no placeholder question marks remain in the localized interface.

## Verification boundary

The skill's phone target sizes, CSS frameworks, external fonts and SVG recommendations do not apply directly to Minecraft's scaled GUI. Controls use native 20-unit height, and panel dimensions follow the available scaled window. No decorative animation or new runtime dependency was added.

Code compilation, unit tests, server GameTests and calculated contrast checks are complete. **In-game visual acceptance is pending the user's check**, as requested. Do not launch or control Minecraft automatically for the next visual review: provide the built JAR and ask the user to inspect it in their modded profile.

Manual UI pass: test English and Spanish at GUI scales 2 and 3, plus Auto in a small window; open the Mayor and board; navigate entirely with the keyboard; read long details/dialogue to their end; accept, refresh, claim, and inspect a failed claim. Confirm selected markers, visible focus, full-label tooltips, working state, and no overlap with footer controls. Full gameplay steps are in `village-identity-ui-polish.md`.

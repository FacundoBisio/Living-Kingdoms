# Warband Character Assets (Stage 1: warband_soldier)

## Technical Summary
- **Format Version (Geometry):** `1.12.0` (per-face UV, 64x64 texture)
- **Format Version (Animations):** `1.8.0`
- **Visible Bounds:** `3 x 3`, offset `[0, 1.5, 0]`
- **Reused Repo Clips (not duplicated in `warband.animation.json`):**
  - `animation.citizen.idle`, `animation.citizen.walk`, `animation.citizen.run`
  - `animation.citizen.guard_patrol`, `animation.citizen.guard_attack`, `animation.citizen.guard_block`

## Models
### 1. `warband_soldier` (`geometry.warband_soldier`)
- **Total Bones:** 14
- **Total Cubes:** 15

| Bone Name | Parent | Pivot | Cube Count |
| :--- | :--- | :--- | :---: |
| `root` | `-` | `[0, 0, 0]` | 0 |
| `body` | `root` | `[0, 12, 0]` | 0 |
| `torso` | `body` | `[0, 12, 0]` | 2 |
| `head` | `body` | `[0, 24, 0]` | 1 |
| `hair` | `head` | `[0, 24, 0]` | 0 |
| `beard` | `head` | `[0, 24, 0]` | 1 |
| `head_accessories` | `head` | `[0, 24, 0]` | 2 |
| `right_arm` | `body` | `[5, 22, 0]` | 2 |
| `right_hand` | `right_arm` | `[6, 13, 0]` | 0 |
| `left_arm` | `body` | `[-5, 22, 0]` | 2 |
| `left_hand` | `left_arm` | `[-6, 13, 0]` | 0 |
| `right_leg` | `root` | `[2, 12, 0]` | 2 |
| `left_leg` | `root` | `[-2, 12, 0]` | 2 |
| `accessories` | `root` | `[0, 12, 0]` | 1 |

## New Animations (`characters/animations/warband.animation.json`)
| Clip Name | Length (s) | Loop | Animated Bones |
| :--- | :---: | :---: | :--- |
| `animation.warband.axe_attack` | 1.0s | No | `body`, `head`, `right_arm`, `right_hand`, `left_arm` |
| `animation.warband.crossbow_aim` | 1.6s | Yes | `body`, `head`, `right_arm`, `left_arm` |
| `animation.warband.crossbow_reload` | 1.5s | No | `body`, `head`, `right_arm`, `left_arm` |
| `animation.warband.captain_command` | 1.5s | No | `body`, `head`, `right_arm`, `left_arm` |
| `animation.warband.death_stagger` | 1.4s | No | `body`, `head`, `right_arm`, `left_arm`, `right_leg`, `left_leg` |

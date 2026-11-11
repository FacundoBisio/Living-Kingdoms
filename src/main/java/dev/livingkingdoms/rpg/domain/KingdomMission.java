package dev.livingkingdoms.rpg.domain;

/**
 * Chapter 2 of the main story, "A Growing Kingdom": accepted one at a time, in order. Each mission has a goal
 * measured from real kingdom state (or kills counted after accepting) and a reward that grows along the chapter.
 */
public enum KingdomMission {
    CLEAR_OUTSKIRTS(Goal.KILLS, 15, 10, 60, LootRarity.COMMON, 0),
    HALL_LEVEL_2(Goal.HALL_LEVEL, 2, 12, 80, LootRarity.COMMON, 1),
    PALISADE(Goal.WALL_TIER, 1, 14, 90, LootRarity.RARE, 0),
    TAVERN(Goal.TAVERN, 1, 14, 90, LootRarity.RARE, 0),
    EIGHT_RESIDENTS(Goal.RESIDENTS, 8, 16, 100, LootRarity.RARE, 1),
    HUNT(Goal.KILLS, 40, 18, 110, LootRarity.RARE, 1),
    WALL_TOWERS(Goal.TOWERS, 1, 20, 120, LootRarity.EPIC, 1),
    STONE_WALLS(Goal.WALL_TIER, 2, 22, 130, LootRarity.EPIC, 1),
    THREE_UPGRADES(Goal.UPGRADED, 3, 24, 140, LootRarity.EPIC, 2),
    BECOME_TOWN(Goal.RANK, 6, 28, 160, LootRarity.EPIC, 2),
    GATEHOUSES(Goal.GATEHOUSES, 1, 32, 180, LootRarity.LEGENDARY, 2),
    FORTRESS(Goal.WALL_TIER, 3, 40, 200, LootRarity.LEGENDARY, 3);

    public enum Goal { KILLS, HALL_LEVEL, WALL_TIER, TAVERN, RESIDENTS, TOWERS, UPGRADED, RANK, GATEHOUSES }
    private final Goal goal; private final int target, emeralds, kingdomXp, whetstones; private final LootRarity chest;
    KingdomMission(Goal goal, int target, int emeralds, int kingdomXp, LootRarity chest, int whetstones) {
        this.goal = goal; this.target = target; this.emeralds = emeralds; this.kingdomXp = kingdomXp; this.chest = chest; this.whetstones = whetstones;
    }
    public Goal goal() { return goal; }
    public int target() { return target; }
    public int emeralds() { return emeralds; }
    public int kingdomXp() { return kingdomXp; }
    public LootRarity chest() { return chest; }
    public int whetstones() { return whetstones; }
    public String id() { return name().toLowerCase(java.util.Locale.ROOT); }
}

package dev.livingkingdoms.rpg.domain;

/**
 * Player-chosen trial difficulty. Tables are deliberately explicit so balance changes are reviewable
 * diffs, and so tests can assert the escalation contract (more waves, more enemies, better rewards).
 */
public enum ChallengeTier {
    SKIRMISH(1, 2, 4, false, 0.0, 0, 6000, 8, 2, 1, 12000, Reward.NONE),
    SIEGE(2, 3, 5, true, 0.25, 1, 7200, 16, 4, 1, 18000, Reward.CHAMPION_AXE),
    WARLORD(3, 4, 6, true, 0.5, 2, 9600, 32, 6, 2, 24000, Reward.CHAMPION_SWORD);

    /** Unique item granted on victory, in addition to the base payout. */
    public enum Reward { NONE, CHAMPION_AXE, CHAMPION_SWORD }

    private final int rank, waves, enemiesPerWave, boss, armorTier, emeralds, whetstones, tomes;
    private final double healthBonus;
    private final long timeLimitTicks, cooldownTicks;
    private final Reward reward;

    ChallengeTier(int rank, int waves, int enemiesPerWave, boolean boss, double healthBonus, int armorTier,
                  long timeLimitTicks, int emeralds, int whetstones, int tomes, long cooldownTicks, Reward reward) {
        this.rank = rank; this.waves = waves; this.enemiesPerWave = enemiesPerWave; this.boss = boss ? 1 : 0;
        this.healthBonus = healthBonus; this.armorTier = armorTier; this.timeLimitTicks = timeLimitTicks;
        this.emeralds = emeralds; this.whetstones = whetstones; this.tomes = tomes;
        this.cooldownTicks = cooldownTicks; this.reward = reward;
    }

    public int rank() { return rank; }
    public int waves() { return waves; }
    /** Enemies in a wave grow by one per wave so the last wave is the climax. */
    public int enemies(int wave) {
        if (wave < 0 || wave >= waves) throw new IllegalArgumentException("Wave out of range");
        return enemiesPerWave + wave;
    }
    public boolean boss() { return boss == 1; }
    public double healthBonus() { return healthBonus; }
    /** 0 none, 1 iron, 2 diamond helmet/chest for elite members. */
    public int armorTier() { return armorTier; }
    public long timeLimitTicks() { return timeLimitTicks; }
    public int emeralds() { return emeralds; }
    public int whetstones() { return whetstones; }
    public int tomes() { return tomes; }
    public long cooldownTicks() { return cooldownTicks; }
    public Reward reward() { return reward; }
    public int totalEnemies() {
        int total = boss() ? 1 : 0;
        for (int i = 0; i < waves; i++) total += enemies(i);
        return total;
    }
    public String id() { return name().toLowerCase(java.util.Locale.ROOT); }
    public static ChallengeTier fromId(String id) {
        for (var tier : values()) if (tier.id().equals(id)) return tier;
        throw new IllegalArgumentException("Unknown challenge tier: " + id);
    }
}

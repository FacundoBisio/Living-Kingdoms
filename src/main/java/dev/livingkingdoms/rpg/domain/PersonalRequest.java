package dev.livingkingdoms.rpg.domain;

import dev.livingkingdoms.profession.domain.ProfessionType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One daily wish per citizen, themed by profession and deterministic per citizen and calendar day. */
public record PersonalRequest(String item, int count) {
    public PersonalRequest {
        Objects.requireNonNull(item, "item");
        if (count < 1 || count > 64 || !item.matches("[a-z0-9_]+:[a-z0-9_/]+")) throw new IllegalArgumentException("Invalid personal request");
    }

    private static final Map<ProfessionType, List<PersonalRequest>> POOLS = Map.of(
            ProfessionType.FARMER, List.of(r("bread", 6), r("bone_meal", 12), r("pumpkin", 4), r("carrot", 12)),
            ProfessionType.BUILDER, List.of(r("oak_planks", 24), r("glass", 8), r("torch", 16), r("cobblestone", 32)),
            ProfessionType.GUARD, List.of(r("arrow", 16), r("cooked_beef", 6), r("iron_ingot", 4), r("shield", 1)),
            ProfessionType.LUMBERJACK, List.of(r("apple", 4), r("bread", 6), r("oak_sapling", 8)),
            ProfessionType.MINER, List.of(r("torch", 24), r("coal", 12), r("cooked_porkchop", 6)),
            ProfessionType.BLACKSMITH, List.of(r("coal", 16), r("iron_ingot", 6), r("lava_bucket", 1)),
            ProfessionType.MERCHANT, List.of(r("book", 2), r("paper", 12), r("emerald", 4)),
            ProfessionType.UNASSIGNED, List.of(r("bread", 4), r("dandelion", 3), r("cake", 1), r("cookie", 8), r("honey_bottle", 2)));

    private static PersonalRequest r(String item, int count) { return new PersonalRequest("minecraft:" + item, count); }

    public static PersonalRequest of(UUID citizen, long day, ProfessionType profession) {
        var pool = POOLS.getOrDefault(profession, POOLS.get(ProfessionType.UNASSIGNED));
        long mix = citizen.getMostSignificantBits() ^ citizen.getLeastSignificantBits() ^ (day * 0x9E3779B97F4A7C15L);
        return pool.get((int) Math.floorMod(mix ^ (mix >>> 31), pool.size()));
    }
}

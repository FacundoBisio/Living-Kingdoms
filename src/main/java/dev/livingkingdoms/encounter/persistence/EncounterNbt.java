package dev.livingkingdoms.encounter.persistence;

import dev.livingkingdoms.encounter.domain.HostileParty;
import dev.livingkingdoms.encounter.domain.OriginRegion;
import dev.livingkingdoms.encounter.domain.PartyState;
import dev.livingkingdoms.encounter.domain.PartyType;
import dev.livingkingdoms.faction.Faction;
import dev.livingkingdoms.progression.domain.LevelSummary;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Strict boundary: malformed encounter records cannot become rewardable empty parties. */
final class EncounterNbt {
    private EncounterNbt() {}

    static CompoundTag write(HostileParty party) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", party.id());
        tag.putString("faction", party.faction().id());
        tag.putString("type", party.type().id());
        tag.putString("state", party.state().name());
        if (party.associatedSettlementId() != null) {
            tag.putUUID("associated_settlement", party.associatedSettlementId());
        }
        CompoundTag origin = new CompoundTag();
        origin.putString("dimension", party.origin().dimension());
        origin.putInt("x", party.origin().x());
        origin.putInt("y", party.origin().y());
        origin.putInt("z", party.origin().z());
        origin.putInt("radius", party.origin().radius());
        tag.put("origin", origin);
        tag.put("members", uuidList(party.memberIds()));
        tag.put("remaining_members", uuidList(party.remainingMembers()));
        tag.putInt("threat_rating", party.threatRating());
        tag.putInt("reputation_reward", party.reputationReward());
        tag.putBoolean("debug", party.debug());
        tag.putBoolean("reward_eligible", party.rewardEligible());
        CompoundTag levels = new CompoundTag();
        levels.putInt("count", party.levels().count()); levels.putInt("sum", party.levels().sum());
        levels.putInt("minimum", party.levels().minimum()); levels.putInt("maximum", party.levels().maximum());
        tag.put("levels", levels);
        return tag;
    }

    static HostileParty read(CompoundTag tag) {
        UUID id = uuid(tag, "id");
        for (String field : new String[]{"faction", "type", "state"}) require(tag, field, Tag.TAG_STRING);
        require(tag, "origin", Tag.TAG_COMPOUND);
        for (String field : new String[]{"threat_rating", "reputation_reward"}) require(tag, field, Tag.TAG_INT);
        for (String field : new String[]{"debug", "reward_eligible"}) {
            require(tag, field, Tag.TAG_BYTE);
            if (tag.getByte(field) != 0 && tag.getByte(field) != 1) {
                throw new IllegalArgumentException("Encounter boolean must be 0 or 1: " + field);
            }
        }
        UUID settlement = tag.contains("associated_settlement") ? uuid(tag, "associated_settlement") : null;
        CompoundTag location = tag.getCompound("origin");
        require(location, "dimension", Tag.TAG_STRING);
        for (String field : new String[]{"x", "y", "z", "radius"}) require(location, field, Tag.TAG_INT);
        OriginRegion origin = new OriginRegion(location.getString("dimension"), location.getInt("x"),
                location.getInt("y"), location.getInt("z"), location.getInt("radius"));
        Set<UUID> roster = readUuidList(tag, "members");
        LevelSummary levels = LevelSummary.uniform(roster.size(), 1);
        if (tag.contains("levels")) {
            require(tag, "levels", Tag.TAG_COMPOUND);
            CompoundTag levelTag = tag.getCompound("levels");
            for (String field : new String[]{"count", "sum", "minimum", "maximum"}) require(levelTag, field, Tag.TAG_INT);
            levels = new LevelSummary(levelTag.getInt("count"), levelTag.getInt("sum"), levelTag.getInt("minimum"), levelTag.getInt("maximum"));
        }
        return new HostileParty(id, Faction.fromId(tag.getString("faction")),
                PartyType.fromId(tag.getString("type")), origin, settlement,
                PartyState.valueOf(tag.getString("state")), roster,
                readUuidList(tag, "remaining_members"), tag.getInt("threat_rating"),
                tag.getInt("reputation_reward"), tag.getBoolean("debug"), tag.getBoolean("reward_eligible"), levels);
    }

    private static ListTag uuidList(Set<UUID> members) {
        ListTag list = new ListTag();
        // Stable ordering makes unchanged metadata produce stable serialized lists.
        members.stream().sorted().forEach(member -> list.add(NbtUtils.createUUID(member)));
        return list;
    }

    private static Set<UUID> readUuidList(CompoundTag tag, String field) {
        require(tag, field, Tag.TAG_LIST);
        ListTag list = (ListTag) tag.get(field);
        if (list.size() > 16 || (!list.isEmpty() && list.getElementType() != Tag.TAG_INT_ARRAY)) {
            throw new IllegalArgumentException("Encounter members must contain up to 16 UUIDs: " + field);
        }
        Set<UUID> members = new HashSet<>();
        for (Tag memberTag : list) {
            if (!members.add(NbtUtils.loadUUID(memberTag))) {
                throw new IllegalArgumentException("Duplicate encounter member: " + field);
            }
        }
        return members;
    }

    private static UUID uuid(CompoundTag tag, String field) {
        if (!tag.hasUUID(field)) throw new IllegalArgumentException("Missing or invalid encounter UUID: " + field);
        return tag.getUUID(field);
    }

    static void require(CompoundTag tag, String field, int type) {
        if (!tag.contains(field, type)) {
            throw new IllegalArgumentException("Missing or invalid encounter field: " + field);
        }
    }
}

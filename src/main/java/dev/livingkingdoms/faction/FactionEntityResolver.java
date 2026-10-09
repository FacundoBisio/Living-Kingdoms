package dev.livingkingdoms.faction;

import dev.livingkingdoms.encounter.EncounterMember;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import dev.livingkingdoms.npc.NpcIdentity;
import dev.livingkingdoms.npc.NpcRole;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import java.util.Optional;

/** Resolves only Living Kingdoms combatants, never a faction from a vanilla entity's species. */
public final class FactionEntityResolver {
    private FactionEntityResolver() {}

    public static Optional<Faction> combatFaction(Entity entity) {
        if (!(entity instanceof Mob) || !(entity.level() instanceof ServerLevel level)) return Optional.empty();
        var encounter = EncounterMember.read(entity);
        if (encounter.isPresent()) {
            var identity = encounter.orElseThrow();
            return EncounterSavedData.get(level.getServer()).forMember(entity.getUUID())
                    .filter(party -> party.id().equals(identity.partyId()) && party.faction() == identity.faction()
                            && party.remainingMembers().contains(entity.getUUID()))
                    .map(party -> party.faction());
        }
        if(entity instanceof net.minecraft.world.entity.npc.Villager v && dev.livingkingdoms.profession.GuardWork.assigned(v)) return Optional.of(Faction.ALLIED_KINGDOM);
        return NpcIdentity.read(entity).filter(identity -> identity.role() == NpcRole.GUARD)
                .flatMap(identity -> SettlementSavedData.get(level.getServer()).get(identity.settlementId()))
                .filter(settlement -> settlement.faction().isAllied())
                .map(settlement -> settlement.faction());
    }
}

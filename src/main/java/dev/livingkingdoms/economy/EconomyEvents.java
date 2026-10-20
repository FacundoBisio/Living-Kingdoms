package dev.livingkingdoms.economy;

import dev.livingkingdoms.npc.NpcIdentity;
import dev.livingkingdoms.npc.NpcRole;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** Loaded registered citizens share one bounded check per realm/second; no global or offline simulation. */
public final class EconomyEvents {
    private static final java.util.Map<net.minecraft.server.MinecraftServer,java.util.LinkedHashMap<java.util.UUID,Long>> CHECKS=new java.util.WeakHashMap<>();
    private EconomyEvents() {}
    public static void onTick(EntityTickEvent.Post event) {
        if(!(event.getEntity() instanceof Villager mayor) || !(mayor.level() instanceof ServerLevel level)
                || Math.floorMod(mayor.tickCount+mayor.getId(),20)!=0) return;
        if(!mayor.isAlive()) return;
        java.util.UUID id;
        var identity=NpcIdentity.read(mayor).filter(i->i.role()==NpcRole.MAYOR);
        if(identity.isPresent()) {
            id=identity.get().settlementId();
            if(QuestSavedData.get(level.getServer()).mayor(id).filter(mayor.getUUID()::equals).isEmpty()) return;
        } else {
            if(!mayor.getPersistentData().hasUUID(dev.livingkingdoms.citizen.CitizenService.CITIZEN_ID_KEY)) return;
            var citizen=dev.livingkingdoms.citizen.persistence.CitizenSavedData.get(level.getServer()).byEntity(mayor.getUUID()).orElse(null);
            if(citizen==null || citizen.state()!=dev.livingkingdoms.citizen.domain.CitizenState.ACTIVE || !dev.livingkingdoms.citizen.CitizenService.canApply(citizen,mayor)) return;
            id=citizen.settlementId();
        }
        if(dev.livingkingdoms.npckingdom.NpcKingdoms.isNpc(level.getServer(),id)) return; // NPC kingdoms are exempt from consumption.
        long now=level.getServer().overworld().getGameTime();var recent=CHECKS.computeIfAbsent(level.getServer(),ignored->new java.util.LinkedHashMap<>());
        if(recent.getOrDefault(id,-20L)>now-20) return;
        if(!recent.containsKey(id) && recent.size()>=2048) recent.remove(recent.keySet().iterator().next());recent.put(id,now);
        SettlementSavedData.get(level.getServer()).get(id).filter(s->s.faction().isAllied()
                && s.territory().dimension().equals(level.dimension().location().toString())).ifPresent(s->KingdomEconomy.consume(level.getServer(),s));
    }
}

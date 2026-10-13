package dev.livingkingdoms.economy;

import dev.livingkingdoms.npc.NpcIdentity;
import dev.livingkingdoms.npc.NpcRole;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** One loaded registered mayor per realm. Staggered local checks, no global or offline simulation. */
public final class EconomyEvents {
    private EconomyEvents() {}
    public static void onTick(EntityTickEvent.Post event) {
        if(!(event.getEntity() instanceof Villager mayor) || !(mayor.level() instanceof ServerLevel level)
                || Math.floorMod(mayor.tickCount+mayor.getId(),20)!=0) return;
        var identity=NpcIdentity.read(mayor).filter(i->i.role()==NpcRole.MAYOR);if(identity.isEmpty()) return;
        var id=identity.get().settlementId();
        if(QuestSavedData.get(level.getServer()).mayor(id).filter(mayor.getUUID()::equals).isEmpty()) return;
        SettlementSavedData.get(level.getServer()).get(id).filter(s->s.faction().isAllied()
                && s.territory().dimension().equals(level.dimension().location().toString())).ifPresent(s->KingdomEconomy.consume(level.getServer(),s));
    }
}

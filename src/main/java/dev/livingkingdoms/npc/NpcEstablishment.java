package dev.livingkingdoms.npc;

import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;

import java.util.List;

/** Short-lived transaction: restores promoted villagers or removes newly spawned NPCs on failure. */
public final class NpcEstablishment implements AutoCloseable {
    private final ServerLevel level;
    private final Settlement settlement;
    private Villager mayor;
    private CompoundTag original;
    private boolean committed;

    private NpcEstablishment(ServerLevel level, Settlement settlement) {
        this.level = level;
        this.settlement = settlement;
    }

    public static NpcEstablishment open(ServerLevel level, Settlement settlement, List<Villager> candidates) {
        var data = SettlementSavedData.get(level.getServer());
        if (!data.get(settlement.id()).filter(settlement::equals).isPresent()
                || QuestSavedData.get(level.getServer()).mayor(settlement.id()).isPresent())
            throw new IllegalStateException("NPC establishment requires a new stored settlement");
        var transaction = new NpcEstablishment(level, settlement);
        try { transaction.prepare(candidates); return transaction; }
        catch (RuntimeException failure) { transaction.close(); throw failure; }
    }

    private void prepare(List<Villager> candidates) {
        BlockPos marker = new BlockPos(settlement.territory().x(), settlement.territory().y(), settlement.territory().z());
        for (Villager candidate : candidates) {
            // Keep named villagers, traders, children and other settlements' NPCs intact.
            if (!candidate.isAlive() || candidate.level() != level || candidate.isBaby() || candidate.hasCustomName()
                    || candidate.isPassenger() || candidate.isVehicle() || candidate.isLeashed()
                    || !NpcIdentity.isUnassigned(candidate)
                    || candidate.getVillagerData().getProfession() != VillagerProfession.NONE) continue;
            CompoundTag snapshot = candidate.saveWithoutId(new CompoundTag());
            // Reading getOffers() lazily initializes trades. Inspect saved offers without changing a candidate.
            if (!snapshot.getCompound("Offers").getList("Recipes",10).isEmpty()) continue;
            mayor = candidate;
            original = snapshot;
            candidate.stopSleeping();
            for (BlockPos feet : List.of(marker.north(3), marker.west(3), marker.south(3), marker.offset(-2,0,-2))) {
                candidate.moveTo(feet.getX()+0.5, feet.getY(), feet.getZ()+0.5, 0, 0);
                if (!NpcService.safePosition(level, candidate, feet)) continue;
                candidate.getNavigation().stop();
                candidate.setInvulnerable(true);
                candidate.setCanPickUpLoot(false);
                NpcIdentity.attach(candidate, settlement.id(), NpcRole.MAYOR);
                MayorPresentation.apply(candidate);
                if (!QuestSavedData.get(level.getServer()).associateMayor(settlement.id(), candidate.getUUID()))
                    throw new IllegalStateException("Mayor association refused");
                return;
            }
            restoreOriginal();
            mayor = null;
            original = null;
        }
        mayor = NpcService.ensureMayor(level, settlement).orElseThrow(() -> new IllegalStateException("No safe Mayor location"));
    }

    public Villager mayor() { return mayor; }

    public void requireInitialResidents() {
        if (NpcService.spawnInitialResidents(level, settlement, mayor) != 2)
            throw new IllegalStateException("No safe initial resident locations");
    }

    public void commit() {
        // Free the promoted villager's vanilla POIs only after every reversible step has succeeded.
        if (original != null) {
            for (var memory : List.of(MemoryModuleType.HOME, MemoryModuleType.JOB_SITE,
                    MemoryModuleType.POTENTIAL_JOB_SITE, MemoryModuleType.MEETING_POINT)) {
                mayor.releasePoi(memory);
                mayor.getBrain().eraseMemory(memory);
            }
        }
        committed = true;
    }

    @Override public void close() {
        if (committed || mayor == null) return;
        for (int index = 0; index < 2; index++) {
            String receipt = "livingkingdoms:founding_resident_" + index;
            if (mayor.getPersistentData().hasUUID(receipt)) {
                var entity = level.getEntity(mayor.getPersistentData().getUUID(receipt));
                if (entity != null && NpcIdentity.read(entity).filter(id -> id.settlementId().equals(settlement.id())
                        && id.role() == NpcRole.RESIDENT).isPresent()) entity.discard();
            }
        }
        QuestSavedData.get(level.getServer()).rollbackMayorAssociation(settlement.id(), mayor.getUUID());
        if (original == null) mayor.discard();
        else restoreOriginal();
    }

    private void restoreOriginal() {
        // Entity.load applies optional names when present, but does not clear a newly assigned absent name.
        mayor.setCustomName(null);
        mayor.load(original.copy());
    }
}

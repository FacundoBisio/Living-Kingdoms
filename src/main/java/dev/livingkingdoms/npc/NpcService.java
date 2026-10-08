package dev.livingkingdoms.npc;

import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.AABB;

import java.util.Optional;
import java.util.UUID;

/** Small server service around vanilla villagers; it never loads chunks or recreates missing NPCs. */
public final class NpcService {
    private static final int[][] PLAZA_OFFSETS = {
            {0, -3}, {-3, 0}, {0, 3}, {-2, -2}, {2, -2}, {-2, 2}, {2, 2}
    };

    private NpcService() {}

    public static Optional<Villager> ensureMayor(ServerLevel level, Settlement settlement) {
        // Accessing storage also enforces the server thread before inspecting entities or chunks.
        SettlementSavedData settlements = SettlementSavedData.get(level.getServer());
        if (!settlement.faction().isAllied()
                || !settlement.territory().dimension().equals(level.dimension().location().toString())
                || !settlements.settlements().contains(settlement)) return Optional.empty();
        QuestSavedData quests = QuestSavedData.get(level.getServer());
        Optional<UUID> associated = quests.mayor(settlement.id());
        if (associated.isPresent()) {
            Entity existing = level.getEntity(associated.get());
            // A missing entity may simply be in an unloaded chunk. Replacing its UUID would duplicate it.
            return existing instanceof Villager villager && isMayorOf(villager, settlement.id())
                    ? Optional.of(villager) : Optional.empty();
        }

        // Recover a loaded tagged Mayor if a previous entity save preceded its association save.
        for (Entity existing : level.getAllEntities()) {
            if (existing instanceof Villager villager && isMayorOf(villager, settlement.id())) {
                return quests.associateMayor(settlement.id(), villager.getUUID())
                        ? Optional.of(villager) : Optional.empty();
            }
        }

        Villager mayor = EntityType.VILLAGER.create(level);
        if (mayor == null) return Optional.empty();
        mayor.setNoAi(true);
        mayor.setPersistenceRequired();
        // Protect this stationary dialogue NPC from ordinary combat; creative removal still works.
        mayor.setInvulnerable(true);
        mayor.setCanPickUpLoot(false);
        MayorPresentation.apply(mayor);
        mayor.setCustomNameVisible(true);
        mayor.setOffers(new MerchantOffers());

        BlockPos marker = new BlockPos(settlement.territory().x(), settlement.territory().y(), settlement.territory().z());
        for (int[] offset : PLAZA_OFFSETS) {
            BlockPos feet = marker.offset(offset[0], 0, offset[1]);
            mayor.moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0, 0);
            if (!safePosition(level, mayor, feet)) continue;
            mayor.setVillagerData(new VillagerData(VillagerType.byBiome(level.getBiome(feet)), VillagerProfession.NONE, 1));
            MayorPresentation.apply(mayor);
            NpcIdentity.attach(mayor, settlement.id(), NpcRole.MAYOR);
            if (!level.addFreshEntity(mayor)) return Optional.empty();
            try {
                if (quests.associateMayor(settlement.id(), mayor.getUUID())) return Optional.of(mayor);
            } catch (RuntimeException failure) {
                mayor.discard();
                throw failure;
            }
            mayor.discard();
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** Called only at founding. Receipts live with the persistent Mayor, never respawn missing/unloaded residents. */
    public static int spawnInitialResidents(ServerLevel level, Settlement settlement, Villager mayor) {
        SettlementSavedData.get(level.getServer());
        var saved = mayor.getPersistentData();
        java.util.List<BlockPos> placed = new java.util.ArrayList<>();
        int count = 0;
        for (int index = 0; index < 2; index++) {
            String receipt = "livingkingdoms:founding_resident_" + index;
            if (saved.hasUUID(receipt)) { count++; continue; }
            Villager resident = EntityType.VILLAGER.create(level);
            if (resident == null) continue;
            resident.setPersistenceRequired();
            resident.setCanPickUpLoot(true);
            var territory = settlement.territory();
            for (int[] offset : PLAZA_OFFSETS) {
                BlockPos feet = new BlockPos(territory.x()+offset[0], territory.y(), territory.z()+offset[1]);
                resident.moveTo(feet.getX()+0.5, feet.getY(), feet.getZ()+0.5, 0, 0);
                if (mayor.distanceToSqr(resident) < 1 || placed.contains(feet) || !safePosition(level, resident, feet)) continue;
                resident.setVillagerData(new VillagerData(VillagerType.byBiome(level.getBiome(feet)), VillagerProfession.NONE, 1));
                NpcIdentity.attach(resident, settlement.id(), NpcRole.RESIDENT);
                if (level.addFreshEntity(resident)) { saved.putUUID(receipt, resident.getUUID()); placed.add(feet); count++; }
                break;
            }
        }
        return count;
    }

    private static boolean isMayorOf(Villager villager, UUID settlementId) {
        return villager.isAlive() && NpcIdentity.read(villager)
                .filter(identity -> identity.role() == NpcRole.MAYOR && identity.settlementId().equals(settlementId)).isPresent();
    }

    static boolean safePosition(ServerLevel level, Villager mayor, BlockPos feet) {
        if (feet.getY() <= level.getMinBuildHeight() || feet.getY() + 2 >= level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(feet)) return false;
        AABB bounds = mayor.getBoundingBox();
        for (int x = ((int) Math.floor(bounds.minX)) >> 4; x <= ((int) Math.floor(bounds.maxX)) >> 4; x++) {
            for (int z = ((int) Math.floor(bounds.minZ)) >> 4; z <= ((int) Math.floor(bounds.maxZ)) >> 4; z++) {
                if (!level.getChunkSource().hasChunk(x, z)) return false;
            }
        }
        var ground = level.getBlockState(feet.below());
        if (!ground.getFluidState().isEmpty() || !ground.isFaceSturdy(level, feet.below(), Direction.UP)) return false;
        for (int y = 0; y <= 2; y++) {
            if (!level.getFluidState(feet.above(y)).isEmpty()) return false;
        }
        return level.noCollision(mayor)
                && level.getEntities(mayor, bounds, entity -> entity.isAlive() && !entity.isSpectator()).isEmpty();
    }
}

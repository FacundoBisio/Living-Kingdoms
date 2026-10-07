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
        mayor.setCustomName(Component.translatable("npc.livingkingdoms.mayor.name", settlement.name()));
        mayor.setCustomNameVisible(true);
        mayor.setOffers(new MerchantOffers());

        BlockPos marker = new BlockPos(settlement.territory().x(), settlement.territory().y(), settlement.territory().z());
        for (int[] offset : PLAZA_OFFSETS) {
            BlockPos feet = marker.offset(offset[0], 0, offset[1]);
            mayor.moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0, 0);
            if (!safePosition(level, mayor, feet)) continue;
            mayor.setVillagerData(new VillagerData(VillagerType.byBiome(level.getBiome(feet)), VillagerProfession.NONE, 1));
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

    private static boolean isMayorOf(Villager villager, UUID settlementId) {
        return villager.isAlive() && NpcIdentity.read(villager)
                .filter(identity -> identity.role() == NpcRole.MAYOR && identity.settlementId().equals(settlementId)).isPresent();
    }

    private static boolean safePosition(ServerLevel level, Villager mayor, BlockPos feet) {
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
                && level.getEntities((Entity) null, bounds, entity -> entity.isAlive() && !entity.isSpectator()).isEmpty();
    }
}

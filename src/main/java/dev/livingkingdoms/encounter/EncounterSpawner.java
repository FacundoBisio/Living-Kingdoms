package dev.livingkingdoms.encounter;

import com.mojang.logging.LogUtils;
import dev.livingkingdoms.config.KingdomConfig;
import dev.livingkingdoms.encounter.domain.HostileParty;
import dev.livingkingdoms.encounter.domain.OriginRegion;
import dev.livingkingdoms.encounter.domain.PartyState;
import dev.livingkingdoms.encounter.domain.PartyType;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.NearestAlliedSettlementService;
import dev.livingkingdoms.settlement.domain.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.EventHooks;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Controlled server spawning. No terrain writes, chunk loading or background spawning. */
public final class EncounterSpawner {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int[][] MEMBER_OFFSETS = {
            {-3, -3}, {0, -3}, {3, -3}, {-3, 0}, {3, 0}, {-3, 3}, {0, 3}, {3, 3}
    };
    private static final int[][] DIRECTIONS = {
            {1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, 1}, {-1, -1}, {1, -1}
    };

    private EncounterSpawner() {}

    /** Command encounters are always debug, and rewards require an explicit operator test option. */
    public static Result spawnNear(ServerLevel level, BlockPos playerPosition, PartyType type, boolean rewardTest) {
        EncounterSavedData data = EncounterSavedData.get(level.getServer());
        if (level.getDifficulty() == Difficulty.PEACEFUL) return Result.failed(Failure.PEACEFUL);
        EncounterDefinition definition = EncounterDefinition.fromConfig(type);
        for (int radius : new int[]{16, 24, 32}) {
            for (int[] direction : DIRECTIONS) {
                double scale = direction[0] != 0 && direction[1] != 0 ? radius / Math.sqrt(2) : radius;
                BlockPos center = playerPosition.offset((int) Math.round(direction[0] * scale), 0,
                        (int) Math.round(direction[1] * scale));
                Optional<List<Mob>> plan = plan(level, center, definition);
                if (plan.isPresent()) return place(level, data, center, definition, plan.get(), true, rewardTest);
            }
        }
        return Result.failed(Failure.NO_SAFE_SITE);
    }

    /** Future natural rules can dispatch this service after the proposed region's chunks are loaded. */
    public static Result spawnAt(ServerLevel level, BlockPos origin, PartyType type, boolean debug, boolean rewardEligible) {
        Objects.requireNonNull(origin, "origin");
        EncounterSavedData data = EncounterSavedData.get(level.getServer());
        if (level.getDifficulty() == Difficulty.PEACEFUL) return Result.failed(Failure.PEACEFUL);
        EncounterDefinition definition = EncounterDefinition.fromConfig(type);
        Optional<List<Mob>> plan = plan(level, origin, definition);
        return plan.map(mobs -> place(level, data, origin, definition, mobs, debug, rewardEligible))
                .orElseGet(() -> Result.failed(Failure.NO_SAFE_SITE));
    }

    private static Optional<List<Mob>> plan(ServerLevel level, BlockPos center, EncounterDefinition definition) {
        // Check the complete group footprint before asking for any height or block information.
        if (!loaded(level, new AABB(center.getX() - 4, level.getMinBuildHeight(), center.getZ() - 4,
                center.getX() + 5, level.getMaxBuildHeight(), center.getZ() + 5))) return Optional.empty();
        List<Mob> mobs = new ArrayList<>();
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int i = 0; i < definition.memberCount(); i++) {
            int[] offset = MEMBER_OFFSETS[i];
            int x = center.getX() + offset[0];
            int z = center.getZ() + offset[1];
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos feet = new BlockPos(x, y, z);
            Mob mob = create(level, definition.type(), i);
            if (mob == null) return Optional.empty();
            mob.moveTo(x + 0.5, y, z + 0.5, 0, 0);
            if (!safePosition(level, mob, feet)) return Optional.empty();
            lowest = Math.min(lowest, y);
            highest = Math.max(highest, y);
            if (highest - lowest > 3) return Optional.empty();
            mobs.add(mob);
        }
        return Optional.of(List.copyOf(mobs));
    }

    private static Mob create(ServerLevel level, PartyType type, int index) {
        return switch (type) {
            case PILLAGER_PATROL -> EntityType.PILLAGER.create(level);
            case UNDEAD_HORDE -> index % 2 == 0 ? EntityType.ZOMBIE.create(level) : EntityType.SKELETON.create(level);
        };
    }

    private static Result place(ServerLevel level, EncounterSavedData data, BlockPos proposed,
                                EncounterDefinition definition, List<Mob> mobs, boolean debug, boolean rewardEligible) {
        UUID partyId = UUID.randomUUID();
        List<Mob> added = new ArrayList<>();
        try {
            // Load reward receipts before changing the world; corrupt saves cannot produce a fresh reward store.
            if (rewardEligible) QuestSavedData.get(level.getServer());
            Set<UUID> roster = new HashSet<>();
            for (int i = 0; i < mobs.size(); i++) {
                Mob mob = mobs.get(i);
                if (mob instanceof Pillager pillager) {
                    pillager.setPatrolLeader(definition.captain() && i == 0);
                    pillager.setPatrolTarget(proposed.offset(48, 0, 0));
                }
                // The initial horde uses adult zombies and no jockeys, keeping its roster exact.
                EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(mob.blockPosition()),
                        definition.type() == PartyType.PILLAGER_PATROL ? MobSpawnType.PATROL : MobSpawnType.EVENT,
                        mob instanceof Zombie ? new Zombie.ZombieGroupData(false, false) : null);
                mob.setPersistenceRequired();
                EncounterMember.attach(mob, partyId, definition.faction());
                if (!roster.add(mob.getUUID())) throw new IllegalStateException("Duplicate encounter member UUID");
            }
            BlockPos origin = new BlockPos(proposed.getX(), mobs.getFirst().blockPosition().getY(), proposed.getZ());
            UUID settlementId = NearestAlliedSettlementService.findNearest(level, origin, KingdomConfig.ENCOUNTER_REPUTATION_RANGE.get())
                    .map(Settlement::id).orElse(null);
            HostileParty party = new HostileParty(partyId, definition.faction(), definition.type(),
                    new OriginRegion(level.dimension().location().toString(), origin.getX(), origin.getY(), origin.getZ(), 8),
                    settlementId, PartyState.ALIVE, roster, roster, definition.threatRating(), definition.reputationReward(),
                    debug, rewardEligible);
            for (Mob mob : mobs) {
                if (!level.addFreshEntity(mob)) throw new IllegalStateException("Encounter member spawn was rejected");
                added.add(mob);
            }
            // All entity UUIDs and the record are installed in the same server call, before a tick.
            data.add(party);
            return new Result(party, null);
        } catch (RuntimeException failedSpawn) {
            added.forEach(Entity::discard);
            LOGGER.error("Could not spawn Living Kingdoms {} party {}", definition.type().id(), partyId, failedSpawn);
            return Result.failed(Failure.SPAWN_FAILED);
        }
    }

    private static boolean safePosition(ServerLevel level, Mob mob, BlockPos feet) {
        if (feet.getY() <= level.getMinBuildHeight() || feet.getY() + 3 > level.getMaxBuildHeight()) return false;
        AABB bounds = mob.getBoundingBox();
        if (!loaded(level, bounds) || !level.getWorldBorder().isWithinBounds(bounds)) return false;
        BlockPos groundPosition = feet.below();
        var ground = level.getBlockState(groundPosition);
        if (!ground.getFluidState().isEmpty() || !ground.isFaceSturdy(level, groundPosition, Direction.UP)
                || ground.is(Blocks.MAGMA_BLOCK) || ground.is(Blocks.CAMPFIRE) || ground.is(Blocks.SOUL_CAMPFIRE)) return false;
        for (int y = 0; y <= 2; y++) if (!level.getFluidState(feet.above(y)).isEmpty()) return false;
        return level.noCollision(mob)
                && level.getEntities((Entity) null, bounds, entity -> entity.isAlive() && !entity.isSpectator()).isEmpty();
    }

    private static boolean loaded(ServerLevel level, AABB bounds) {
        for (int x = ((int) Math.floor(bounds.minX)) >> 4; x <= ((int) Math.floor(bounds.maxX)) >> 4; x++) {
            for (int z = ((int) Math.floor(bounds.minZ)) >> 4; z <= ((int) Math.floor(bounds.maxZ)) >> 4; z++) {
                if (!level.getChunkSource().hasChunk(x, z)) return false;
            }
        }
        return true;
    }

    public enum Failure { NO_SAFE_SITE, SPAWN_FAILED, PEACEFUL }

    public record Result(HostileParty party, Failure failure) {
        public boolean successful() { return party != null; }
        private static Result failed(Failure failure) { return new Result(null, failure); }
    }
}

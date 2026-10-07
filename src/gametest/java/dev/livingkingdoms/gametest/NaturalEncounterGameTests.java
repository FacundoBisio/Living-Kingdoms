package dev.livingkingdoms.gametest;

import com.mojang.authlib.GameProfile;
import dev.livingkingdoms.config.KingdomConfig;
import dev.livingkingdoms.encounter.NaturalEncounterSpawner;
import dev.livingkingdoms.encounter.domain.HostileParty;
import dev.livingkingdoms.encounter.domain.OriginRegion;
import dev.livingkingdoms.encounter.domain.PartyState;
import dev.livingkingdoms.encounter.domain.PartyType;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Coordinated fixtures use separate ticks for the intentionally dimension-wide natural cooldown. */
@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NaturalEncounterGameTests {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    @GameTest(template = "empty", timeoutTicks = 850)
    public static void periodicTickSelectsAPlayerAndNaturallySpawnsWithoutCommands(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(32000, 0, 32000));
        Set<ChunkPos> tickets = new HashSet<>();
        prepareNaturalRegion(level, center, KingdomConfig.NATURAL_MAX_PLAYER_DISTANCE.get() + 8, tickets);
        boolean[] finished = {false};
        int[] attempts = {0};
        helper.onEachTick(() -> {
            // The other natural fixture resolves at tick20. Leave it and ticket propagation time to finish.
            if (finished[0] || helper.getTick() < 40
                    || level.getGameTime() % KingdomConfig.NATURAL_CHECK_INTERVAL.get() != 0) return;
            attempts[0]++;
            boolean originalSpawning = level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(true, level.getServer());
            FakePlayer player = player(level, center);
            player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
            level.addNewPlayer(player);
            try {
                EncounterSavedData data = EncounterSavedData.get(level.getServer());
                String dimension = level.dimension().location().toString();
                helper.assertTrue(level.isPositionEntityTicking(center), "Natural region must be active before the real tick entrypoint runs");
                resetCooldown(data, level);
                int before = data.trackedPartyCount();
                var event = new LevelTickEvent.Post(() -> true, level);
                NaturalEncounterSpawner.onLevelTick(event);
                List<HostileParty> spawned = data.activeNearby(dimension, center.getX(), center.getZ(),
                        KingdomConfig.NATURAL_MAX_PLAYER_DISTANCE.get());
                if (spawned.isEmpty()) {
                    // Rounded random proposals may all land just outside the configured radius boundary.
                    // This bounded retry uses subsequent real check times without changing world time or RNG.
                    helper.assertTrue(attempts[0] < 3, "Periodic natural checks must eventually find the prepared active wilderness");
                    return;
                }
                helper.assertTrue(spawned.size() == 1 && data.trackedPartyCount() == before + 1,
                        "One periodic event selects one player and creates at most one party");
                HostileParty party = spawned.getFirst();
                helper.assertTrue(!party.debug() && party.rewardEligible(), "Periodic spawning creates a reward-eligible natural encounter");
                double dx = party.origin().x() - center.getX();
                double dz = party.origin().z() - center.getZ();
                helper.assertTrue(dx * dx + dz * dz >= Math.pow(KingdomConfig.NATURAL_MIN_PLAYER_DISTANCE.get(), 2)
                        && dx * dx + dz * dz <= Math.pow(KingdomConfig.NATURAL_MAX_PLAYER_DISTANCE.get(), 2),
                        "The scheduled encounter respects the configured player distance band");
                long next = data.nextNaturalAttempt(dimension);
                helper.assertTrue(next == level.getGameTime() + KingdomConfig.NATURAL_COOLDOWN.get(),
                        "The scheduled path records its persistent dimension cooldown");
                NaturalEncounterSpawner.onLevelTick(event);
                helper.assertTrue(data.trackedPartyCount() == before + 1 && data.nextNaturalAttempt(dimension) == next,
                        "A repeated tick notification cannot create another encounter during its cooldown");
                for (Mob mob : members(level, party)) {
                    mob.setNoAi(true);
                    helper.assertTrue(mob.hurt(level.damageSources().generic(), 1000), "Test cleanup resolves real natural member deaths");
                }
                finished[0] = true;
                helper.succeed();
            } catch (RuntimeException | Error failure) {
                finished[0] = true;
                throw failure;
            } finally {
                level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
                level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(originalSpawning, level.getServer());
                if (finished[0]) for (ChunkPos chunk : tickets) {
                    level.getChunkSource().removeRegionTicket(TicketType.FORCED, chunk, 2, chunk);
                }
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void naturalSpawningUsesActiveWildernessBudgetsAndPersistentCooldown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos first = helper.absolutePos(new BlockPos(24576, 0, 24576));
        BlockPos second = first.offset(512, 0, 0);
        BlockPos alliedCenter = first.offset(1024, 0, 0);
        Set<ChunkPos> tickets = new HashSet<>();
        preparePlot(level, first, tickets);
        preparePlot(level, second, tickets);
        preparePlot(level, alliedCenter, tickets);
        Settlement relevant = allied(level, first.offset(100, 0, 0), 24);
        allied(level, alliedCenter, 24);
        FakePlayer player = player(level, first.offset(-64, 0, 0));
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);

        helper.runAtTickTime(20, () -> {
            long originalDay = level.getDayTime();
            boolean originalSpawning = level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);
            EncounterSavedData data = EncounterSavedData.get(level.getServer());
            String dimension = level.dimension().location().toString();
            try {
                level.setDayTime(6000);
                level.updateSkyBrightness();
                helper.assertTrue(level.isPositionEntityTicking(first), "Test-only tickets activate the candidate before testing production gates");
                data.scheduleNaturalAttempt(dimension, level.getGameTime());
                long beforeDisabled = data.nextNaturalAttempt(dimension);
                level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
                helper.assertTrue(NaturalEncounterSpawner.attemptAt(player, first, PartyType.PILLAGER_PATROL).isEmpty()
                        && data.nextNaturalAttempt(dimension) == beforeDisabled,
                        "doMobSpawning=false suppresses natural encounters without spending an attempt");
                level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(true, level.getServer());
                player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
                helper.assertTrue(NaturalEncounterSpawner.attemptAt(player, first, PartyType.PILLAGER_PATROL).isEmpty(),
                        "Creative players do not trigger encounters");
                player.gameMode.changeGameModeForPlayer(GameType.SPECTATOR);
                helper.assertTrue(NaturalEncounterSpawner.attemptAt(player, first, PartyType.PILLAGER_PATROL).isEmpty(),
                        "Spectators do not trigger encounters");
                player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);

                BlockPos missing = helper.absolutePos(new BlockPos(46080, 0, 46080));
                player.setPos(missing.getX() - 63.5, missing.getY(), missing.getZ() + 0.5);
                helper.assertTrue(!level.getChunkSource().hasChunk(missing.getX() >> 4, missing.getZ() >> 4), "Missing fixture starts unloaded");
                int before = data.trackedPartyCount();
                helper.assertTrue(NaturalEncounterSpawner.attemptAt(player, missing, PartyType.PILLAGER_PATROL).isEmpty()
                        && data.trackedPartyCount() == before
                        && !level.getChunkSource().hasChunk(missing.getX() >> 4, missing.getZ() >> 4)
                        && data.nextNaturalAttempt(dimension) == level.getGameTime() + KingdomConfig.NATURAL_COOLDOWN.get(),
                        "Rejected unloaded candidates do not load chunks, create parties or bypass the cooldown");

                player.setPos(first.getX() - 63.5, first.getY(), first.getZ() + 0.5);
                resetCooldown(data, level);
                helper.assertTrue(NaturalEncounterSpawner.attemptAt(player, first, PartyType.UNDEAD_HORDE).isEmpty(),
                        "Natural Undead refuse daylight even on a safe active plot");
                resetCooldown(data, level);
                HostileParty spacingBlocker = metadataParty(level, first.offset(0, 0, 127));
                data.add(spacingBlocker);
                helper.assertTrue(NaturalEncounterSpawner.attemptAt(player, first, PartyType.PILLAGER_PATROL).isEmpty(),
                        "Active debug origins also enforce minimum encounter spacing");
                spacingBlocker.memberIds().forEach(data::recordDeath);

                resetCooldown(data, level);
                HostileParty north = metadataParty(level, first.offset(0, 0, 160));
                HostileParty south = metadataParty(level, first.offset(0, 0, -160));
                data.add(north);
                data.add(south);
                helper.assertTrue(NaturalEncounterSpawner.attemptAt(player, first, PartyType.PILLAGER_PATROL).isEmpty(),
                        "Regional group cap rejects a proposal even when individual origins are adequately separated");
                north.memberIds().forEach(data::recordDeath);
                south.memberIds().forEach(data::recordDeath);

                resetCooldown(data, level);
                FakePlayer nearbyObserver = player(level, first);
                nearbyObserver.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
                level.addNewPlayer(nearbyObserver);
                try {
                    helper.assertTrue(NaturalEncounterSpawner.attemptAt(player, first, PartyType.PILLAGER_PATROL).isEmpty(),
                            "A different nearby player prevents spawning even when the selected survival player is far enough away");
                } finally {
                    level.removePlayerImmediately(nearbyObserver, Entity.RemovalReason.DISCARDED);
                }

                resetCooldown(data, level);
                player.setPos(alliedCenter.getX() - 63.5, alliedCenter.getY(), alliedCenter.getZ() + 0.5);
                helper.assertTrue(NaturalEncounterSpawner.attemptAt(player, alliedCenter, PartyType.PILLAGER_PATROL).isEmpty(),
                        "Natural parties cannot appear inside allied territory or its safety buffer");

                resetCooldown(data, level);
                player.setPos(first.getX() - 63.5, first.getY(), first.getZ() + 0.5);
                HostileParty patrol = NaturalEncounterSpawner.attemptAt(player, first, PartyType.PILLAGER_PATROL).orElseThrow();
                helper.assertTrue(!patrol.debug() && patrol.rewardEligible() && patrol.associatedSettlementId().equals(relevant.id()),
                        "Natural patrol uses the existing entity path and enables regional reputation");
                helper.assertTrue(NaturalEncounterSpawner.attemptAt(player, second, PartyType.PILLAGER_PATROL).isEmpty(),
                        "Another call in the same dimension cannot bypass the persisted spawn cooldown");
                List<Mob> patrolMembers = members(level, patrol);
                patrolMembers.forEach(mob -> mob.setNoAi(true));

                level.getDataStorage().save();
                IOUtilities.waitUntilIOWorkerComplete();
                var directory = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data");
                var fresh = new DimensionDataStorage(directory.toFile(), null, level.getServer().registryAccess());
                EncounterSavedData reopened = fresh.get(new SavedData.Factory<>(EncounterSavedData::new, EncounterSavedData::load), EncounterSavedData.DATA_NAME);
                IOUtilities.waitUntilIOWorkerComplete();
                helper.assertTrue(reopened != null && reopened.nextNaturalAttempt(dimension) == data.nextNaturalAttempt(dimension)
                        && reopened.get(patrol.id()).orElseThrow().rewardEligible() && !reopened.get(patrol.id()).orElseThrow().debug(),
                        "Disk reload retains natural eligibility and dimension cooldown");
                for (Mob mob : patrolMembers) helper.assertTrue(mob.hurt(level.damageSources().playerAttack(player), 1000), "Natural member receives real server damage");
                helper.assertTrue(QuestSavedData.get(level.getServer()).reputation(player.getUUID(), relevant.id()) == patrol.reputationReward(),
                        "Defeating a natural encounter awards the configured regional reward");

                resetCooldown(data, level);
                level.setDayTime(18000);
                level.updateSkyBrightness();
                player.setPos(second.getX() - 63.5, second.getY(), second.getZ() + 0.5);
                helper.assertTrue(level.isNight(), "Night fixture must be dark before natural Undead validation");
                HostileParty horde = NaturalEncounterSpawner.attemptAt(player, second, PartyType.UNDEAD_HORDE).orElseThrow();
                helper.assertTrue(horde.rewardEligible() && !horde.debug(), "Dark nighttime wilderness supports a natural Undead Horde");
                for (Mob mob : members(level, horde)) {
                    mob.setNoAi(true);
                    helper.assertTrue(mob.hurt(level.damageSources().generic(), 1000), "Environmental test cleanup triggers actual member deaths");
                }
                helper.succeed();
            } finally {
                level.setDayTime(originalDay);
                level.updateSkyBrightness();
                level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(originalSpawning, level.getServer());
                for (ChunkPos chunk : tickets) level.getChunkSource().removeRegionTicket(TicketType.FORCED, chunk, 2, chunk);
            }
        });
    }

    private static void resetCooldown(EncounterSavedData data, ServerLevel level) {
        data.scheduleNaturalAttempt(level.dimension().location().toString(), level.getGameTime());
    }

    private static HostileParty metadataParty(ServerLevel level, BlockPos origin) {
        Set<UUID> members = Set.of(UUID.randomUUID());
        return new HostileParty(UUID.randomUUID(), PartyType.PILLAGER_PATROL.faction(), PartyType.PILLAGER_PATROL,
                new OriginRegion(level.dimension().location().toString(), origin.getX(), origin.getY(), origin.getZ(), 8),
                null, PartyState.ALIVE, members, members, 2, 0, true, false);
    }

    private static List<Mob> members(ServerLevel level, HostileParty party) {
        return party.remainingMembers().stream().map(id -> (Mob) level.getEntity(id)).toList();
    }

    private static FakePlayer player(ServerLevel level, BlockPos position) {
        FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "NaturalTest"));
        player.setPos(position.getX() + 0.5, position.getY(), position.getZ() + 0.5);
        return player;
    }

    private static Settlement allied(ServerLevel level, BlockPos center, int radius) {
        Settlement settlement = Settlement.founding(UUID.randomUUID(), new Territory(level.dimension().location().toString(),
                center.getX(), center.getY(), center.getZ(), radius), 5);
        SettlementSavedData.get(level.getServer()).add(settlement);
        return settlement;
    }

    private static void preparePlot(ServerLevel level, BlockPos center, Set<ChunkPos> tickets) {
        for (int x = (center.getX() - 8) >> 4; x <= (center.getX() + 8) >> 4; x++) {
            for (int z = (center.getZ() - 8) >> 4; z <= (center.getZ() + 8) >> 4; z++) {
                level.getChunk(x, z); // Fixture only: production code must not request missing chunks.
                ChunkPos chunk = new ChunkPos(x, z);
                if (tickets.add(chunk)) level.getChunkSource().addRegionTicket(TicketType.FORCED, chunk, 2, chunk);
            }
        }
        for (int x = -7; x <= 7; x++) {
            for (int z = -7; z <= 7; z++) {
                level.setBlock(center.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
                for (int y = 0; y < 8; y++) level.setBlock(center.offset(x, y, z), Blocks.AIR.defaultBlockState(), FLAGS);
            }
        }
    }

    private static void prepareNaturalRegion(ServerLevel level, BlockPos center, int radius, Set<ChunkPos> tickets) {
        // Test-only full distance band: every random proposal has safe terrain without controlling RNG.
        for (int x = (center.getX() - radius) >> 4; x <= (center.getX() + radius) >> 4; x++) {
            for (int z = (center.getZ() - radius) >> 4; z <= (center.getZ() + radius) >> 4; z++) {
                level.getChunk(x, z);
                ChunkPos chunk = new ChunkPos(x, z);
                if (tickets.add(chunk)) level.getChunkSource().addRegionTicket(TicketType.FORCED, chunk, 2, chunk);
            }
        }
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (x * x + z * z > radius * radius) continue;
                level.setBlock(center.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
                for (int y = 0; y < 3; y++) level.setBlock(center.offset(x, y, z), Blocks.AIR.defaultBlockState(), FLAGS);
            }
        }
    }
}

package dev.livingkingdoms.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.livingkingdoms.config.KingdomConfig;
import dev.livingkingdoms.encounter.EncounterDefinition;
import dev.livingkingdoms.encounter.EncounterEvents;
import dev.livingkingdoms.encounter.EncounterMember;
import dev.livingkingdoms.encounter.EncounterSpawner;
import dev.livingkingdoms.encounter.domain.HostileParty;
import dev.livingkingdoms.encounter.domain.PartyState;
import dev.livingkingdoms.encounter.domain.PartyType;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import dev.livingkingdoms.faction.Faction;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Real vanilla entities/events and disk persistence; terrain/chunk preparation is test-only. */
@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EncounterGameTests {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void commandsCreateDifferentFactionsWithPersistentUniqueMembers(GameTestHelper helper) throws CommandSyntaxException {
        ServerLevel level = helper.getLevel();
        BlockPos first = helper.absolutePos(new BlockPos(12288, 0, 12288));
        BlockPos second = first.offset(160, 0, 0);
        preparePlot(level, first);
        preparePlot(level, second);
        EncounterSavedData data = EncounterSavedData.get(level.getServer());
        int before = data.parties().size();
        RecordingPlayer player = player(level, first);
        var dispatcher = level.getServer().getCommands().getDispatcher();
        boolean denied = false;
        try {
            dispatcher.execute("kingdom encounter spawn pillager_patrol", player.createCommandSourceStack().withPermission(0));
        } catch (CommandSyntaxException expected) { denied = true; }
        helper.assertTrue(denied && data.parties().size() == before, "Encounter commands require operator permission");
        helper.assertTrue(dispatcher.execute("kingdom encounter spawn pillager_patrol", player.createCommandSourceStack().withPermission(2)) == 1,
                "Pillager patrol command must spawn on a loaded safe plot");
        HostileParty patrol = data.parties().getLast();
        helper.assertTrue(patrol.faction() == Faction.PILLAGER && patrol.type() == PartyType.PILLAGER_PATROL
                && patrol.memberIds().size() == KingdomConfig.ENCOUNTER_PILLAGER_SIZE.get()
                && patrol.debug() && !patrol.rewardEligible(), "Default command patrol is debug and cannot grant reputation");
        List<Mob> pillagers = members(level, patrol);
        helper.assertTrue(pillagers.stream().allMatch(mob -> mob instanceof Pillager && mob.isPersistenceRequired()
                && !mob.isNoAi() && mob.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.CROSSBOW)),
                "Patrol members use persistent equipped vanilla Pillagers with ordinary AI");
        helper.assertTrue(pillagers.stream().filter(mob -> ((Pillager) mob).isPatrolLeader()).count()
                == (KingdomConfig.ENCOUNTER_PATROL_CAPTAIN.get() ? 1 : 0), "Patrol has only the configured Captain");
        for (Mob mob : pillagers) {
            helper.assertTrue(EncounterMember.read(mob).orElseThrow().equals(new EncounterMember(patrol.id(), Faction.PILLAGER)),
                    "Every member must retain faction and party UUID");
            mob.setNoAi(true); // Isolate subsequent assertions from combat and navigation during this test.
        }
        CompoundTag savedMember = pillagers.getFirst().saveWithoutId(new CompoundTag());
        Pillager restoredMember = EntityType.PILLAGER.create(level);
        restoredMember.load(savedMember);
        helper.assertTrue(restoredMember.getUUID().equals(pillagers.getFirst().getUUID()) && restoredMember.isPersistenceRequired()
                && EncounterMember.read(restoredMember).equals(EncounterMember.read(pillagers.getFirst())),
                "Vanilla entity persistence must preserve roster UUID and namespaced identity");

        player.setPos(second.getX() + 0.5, second.getY(), second.getZ() + 0.5);
        helper.assertTrue(dispatcher.execute("kingdom encounter spawn undead_horde reward_test", player.createCommandSourceStack().withPermission(2)) == 1,
                "Undead command accepts an explicit reward test option");
        HostileParty horde = data.parties().getLast();
        List<Mob> undead = members(level, horde);
        helper.assertTrue(horde.faction() == Faction.UNDEAD && horde.type() == PartyType.UNDEAD_HORDE
                && horde.memberIds().size() == KingdomConfig.ENCOUNTER_UNDEAD_SIZE.get() && horde.debug() && horde.rewardEligible(),
                "Reward-test horde is explicitly eligible and has the configured faction and size");
        helper.assertTrue(undead.stream().anyMatch(mob -> mob instanceof Zombie) && undead.stream().anyMatch(mob -> mob instanceof Skeleton)
                && undead.stream().allMatch(Mob::isPersistenceRequired), "Undead horde contains persistent vanilla Zombies and Skeletons");
        undead.forEach(mob -> mob.setNoAi(true));
        helper.assertTrue(!patrol.id().equals(horde.id()) && new HashSet<>(data.parties().stream().map(HostileParty::id).toList()).size() == data.parties().size(),
                "Each encounter UUID must be unique");
        HashSet<UUID> allMembers = new HashSet<>(patrol.memberIds());
        allMembers.addAll(horde.memberIds());
        helper.assertTrue(allMembers.size() == patrol.memberIds().size() + horde.memberIds().size(), "Member UUIDs cannot belong to two parties");
        helper.assertTrue(level.getBlockState(first.below()).is(Blocks.GRASS_BLOCK), "Spawning must preserve terrain");

        level.getDataStorage().save();
        IOUtilities.waitUntilIOWorkerComplete();
        var directory = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data");
        var fresh = new DimensionDataStorage(directory.toFile(), null, level.getServer().registryAccess());
        EncounterSavedData reopened = fresh.get(new SavedData.Factory<>(EncounterSavedData::new, EncounterSavedData::load), EncounterSavedData.DATA_NAME);
        IOUtilities.waitUntilIOWorkerComplete();
        helper.assertTrue(reopened != null && reopened.get(patrol.id()).orElseThrow().equals(patrol)
                && reopened.get(horde.id()).orElseThrow().equals(horde)
                && reopened.forMember(pillagers.getFirst().getUUID()).orElseThrow().equals(patrol),
                "Disk reload must retain both factions, unique rosters and eligibility snapshots");
        pillagers.forEach(Entity::discard);
        undead.forEach(Entity::discard);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void actualMemberDeathsRewardParticipantsAndNearestAlliedOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(12800, 0, 12800));
        preparePlot(level, center);
        Settlement near = allied(level, center.offset(-40, 0, 0));
        Settlement farther = allied(level, center.offset(100, 0, 0));
        HostileParty party = spawn(helper, center, PartyType.PILLAGER_PATROL, false, true);
        helper.assertTrue(party.associatedSettlementId().equals(near.id()), "Party must associate only the nearest allied settlement");
        List<Mob> members = members(level, party);
        RecordingPlayer contributor = player(level, center);
        RecordingPlayer killer = player(level, center);
        QuestSavedData reputation = QuestSavedData.get(level.getServer());
        for (int i = 0; i < members.size() - 1; i++) {
            kill(helper, members.get(i), level.damageSources().playerAttack(contributor));
            helper.assertTrue(reputation.reputation(contributor.getUUID(), near.id()) == 0
                    && reputation.reputation(killer.getUUID(), near.id()) == 0, "Individual member deaths must not grant a group reward");
        }
        Mob last = members.getLast();
        DamageSource source = level.damageSources().playerAttack(killer);
        kill(helper, last, source);
        EncounterSavedData encounters = EncounterSavedData.get(level.getServer());
        helper.assertTrue(encounters.get(party.id()).orElseThrow().state() == PartyState.DEFEATED
                && encounters.get(party.id()).orElseThrow().remainingMembers().isEmpty(), "Final real death must defeat the party");
        helper.assertTrue(reputation.reputation(killer.getUUID(), near.id()) == party.reputationReward()
                && reputation.reputation(contributor.getUUID(), near.id()) == party.reputationReward()
                && reputation.reputation(killer.getUUID(), farther.id()) == 0
                && reputation.reputation(contributor.getUUID(), farther.id()) == 0,
                "Both meaningful damage participants receive one relevant allied settlement reward");
        EncounterEvents.onDeath(new LivingDeathEvent(last, source));
        EncounterEvents.onDeath(new LivingDeathEvent(last, level.damageSources().playerAttack(contributor)));
        helper.assertTrue(reputation.reputation(killer.getUUID(), near.id()) == party.reputationReward()
                && reputation.reputation(contributor.getUUID(), near.id()) == party.reputationReward() && reputation.hasEncounterReward(party.id()),
                "Repeated death notifications and another player cannot obtain another reward");
        level.getDataStorage().save();
        IOUtilities.waitUntilIOWorkerComplete();
        var directory = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data");
        var fresh = new DimensionDataStorage(directory.toFile(), null, level.getServer().registryAccess());
        EncounterSavedData reopened = fresh.get(new SavedData.Factory<>(EncounterSavedData::new, EncounterSavedData::load), EncounterSavedData.DATA_NAME);
        QuestSavedData reopenedReputation = fresh.get(new SavedData.Factory<>(QuestSavedData::new, QuestSavedData::load), QuestSavedData.DATA_NAME);
        IOUtilities.waitUntilIOWorkerComplete();
        helper.assertTrue(reopened != null && reopened.get(party.id()).orElseThrow().state() == PartyState.DEFEATED
                && reopened.recordDeath(last.getUUID()).isEmpty(), "Reload cannot reset party defeat");
        helper.assertTrue(reopenedReputation != null && reopenedReputation.hasEncounterReward(party.id())
                && reopenedReputation.reputation(killer.getUUID(), near.id()) == party.reputationReward()
                && !reopenedReputation.awardEncounterReputationOnce(party.id(), contributor.getUUID(), near.id(), party.reputationReward()),
                "Persisted receipt protects reputation across reconnect/reload and multiplayer duplicate attempts");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void debugAndUnrelatedDeathsNeverRewardButEnvironmentalFinishKeepsParticipation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(13312, 0, 13312));
        preparePlot(level, center);
        Settlement settlement = allied(level, center.offset(40, 0, 0));
        RecordingPlayer player = player(level, center);
        QuestSavedData reputation = QuestSavedData.get(level.getServer());
        HostileParty debug = spawn(helper, center, PartyType.PILLAGER_PATROL, true, false);
        for (Mob mob : members(level, debug)) kill(helper, mob, level.damageSources().playerAttack(player));
        helper.assertTrue(EncounterSavedData.get(level.getServer()).get(debug.id()).orElseThrow().state() == PartyState.DEFEATED
                && reputation.reputation(player.getUUID(), settlement.id()) == 0 && !reputation.hasEncounterReward(debug.id()),
                "Ordinary debug parties are tracked but never award reputation");

        Pillager unrelated = EntityType.PILLAGER.create(level);
        unrelated.moveTo(center.getX() + 0.5, center.getY(), center.getZ() + 0.5, 0, 0);
        unrelated.setNoAi(true);
        level.addFreshEntity(unrelated);
        kill(helper, unrelated, level.damageSources().playerAttack(player));
        helper.assertTrue(reputation.reputation(player.getUUID(), settlement.id()) == 0, "Unrelated vanilla Pillager death must not grant encounter reputation");

        HostileParty environmental = spawn(helper, center.offset(0, 0, 16), PartyType.UNDEAD_HORDE, false, true);
        List<Mob> members = members(level, environmental);
        for (int i = 0; i < members.size() - 1; i++) kill(helper, members.get(i), level.damageSources().playerAttack(player));
        kill(helper, members.getLast(), level.damageSources().generic());
        helper.assertTrue(EncounterSavedData.get(level.getServer()).get(environmental.id()).orElseThrow().state() == PartyState.DEFEATED
                && reputation.reputation(player.getUUID(), settlement.id()) == environmental.reputationReward() && reputation.hasEncounterReward(environmental.id()),
                "Environmental final kill preserves meaningful earlier player contribution");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void undeadConversionKeepsThePartyRosterAndFinalDefeat(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(13824, 0, 13824));
        preparePlot(level, center);
        Settlement settlement = allied(level, center.offset(40, 0, 0));
        HostileParty party = spawn(helper, center, PartyType.UNDEAD_HORDE, false, true);
        List<Mob> initial = members(level, party);
        Zombie zombie = (Zombie) initial.stream().filter(mob -> mob instanceof Zombie).findFirst().orElseThrow();
        UUID oldId = zombie.getUUID();
        CompoundTag converting = zombie.saveWithoutId(new CompoundTag());
        converting.putInt("DrownedConversionTime", 0);
        zombie.load(converting);
        zombie.setNoAi(false);
        zombie.tick(); // Trigger vanilla conversion and its real NeoForge Post event immediately.
        HostileParty converted = EncounterSavedData.get(level.getServer()).get(party.id()).orElseThrow();
        UUID newId = converted.remainingMembers().stream().filter(id -> !party.memberIds().contains(id)).findFirst().orElseThrow();
        Entity replacement = level.getEntity(newId);
        helper.assertTrue(replacement instanceof Drowned && ((Mob) replacement).isPersistenceRequired()
                && EncounterMember.read(replacement).orElseThrow().equals(new EncounterMember(party.id(), Faction.UNDEAD))
                && !converted.memberIds().contains(oldId) && converted.remainingMembers().size() == party.remainingMembers().size()
                && converted.state() == PartyState.ALIVE, "Vanilla conversion replaces one member without counting a death or losing its identity");
        RecordingPlayer player = player(level, center);
        for (Mob member : members(level, converted)) kill(helper, member, level.damageSources().playerAttack(player));
        helper.assertTrue(EncounterSavedData.get(level.getServer()).get(party.id()).orElseThrow().state() == PartyState.DEFEATED
                && QuestSavedData.get(level.getServer()).reputation(player.getUUID(), settlement.id()) == party.reputationReward(),
                "Converted member participates in normal one-time Undead party defeat");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void safeSpawningNeverLoadsChunksOrWritesTerrain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EncounterSavedData data = EncounterSavedData.get(level.getServer());
        int count = data.parties().size();
        BlockPos missing = helper.absolutePos(new BlockPos(40000, 0, 40000));
        helper.assertTrue(!level.getChunkSource().hasChunk(missing.getX() >> 4, missing.getZ() >> 4), "Fixture begins in an unloaded region");
        helper.assertTrue(EncounterSpawner.spawnAt(level, missing, PartyType.PILLAGER_PATROL, true, false).failure() == EncounterSpawner.Failure.NO_SAFE_SITE
                && !level.getChunkSource().hasChunk(missing.getX() >> 4, missing.getZ() >> 4), "Failed planning cannot load or generate chunks");
        BlockPos center = helper.absolutePos(new BlockPos(14336, 0, 14336));
        preparePlot(level, center);
        BlockPos firstMember = center.offset(-3, 0, -3);
        level.setBlock(firstMember, Blocks.WATER.defaultBlockState(), FLAGS);
        helper.assertTrue(!EncounterSpawner.spawnAt(level, center, PartyType.PILLAGER_PATROL, true, false).successful()
                && level.getBlockState(firstMember).is(Blocks.WATER), "Wet placement must fail without draining water");
        level.setBlock(firstMember, Blocks.AIR.defaultBlockState(), FLAGS);
        var pig = EntityType.PIG.create(level);
        pig.moveTo(firstMember.getX() + 0.5, firstMember.getY(), firstMember.getZ() + 0.5, 0, 0);
        pig.setNoAi(true);
        level.addFreshEntity(pig);
        helper.assertTrue(!EncounterSpawner.spawnAt(level, center, PartyType.UNDEAD_HORDE, true, false).successful()
                && pig.isAlive(), "All planned members must avoid existing entities before any spawning");
        pig.discard();
        helper.assertTrue(!EncounterSpawner.spawnAt(level, new BlockPos(30_000_000, center.getY(), 30_000_000),
                PartyType.PILLAGER_PATROL, true, false).successful() && data.parties().size() == count
                && level.getBlockState(center.below()).is(Blocks.GRASS_BLOCK), "Refused placements must preserve roster storage and terrain");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void defeatingAGroupWithoutAnAlliedRegionCannotGrantGlobalReputation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(16384, 0, 16384));
        preparePlot(level, center);
        HostileParty party = spawn(helper, center, PartyType.UNDEAD_HORDE, false, true);
        helper.assertTrue(party.associatedSettlementId() == null, "Remote fixture has no allied settlement within reward range");
        RecordingPlayer player = player(level, center);
        for (Mob member : members(level, party)) kill(helper, member, level.damageSources().playerAttack(player));
        QuestSavedData data = QuestSavedData.get(level.getServer());
        helper.assertTrue(EncounterSavedData.get(level.getServer()).get(party.id()).orElseThrow().state() == PartyState.DEFEATED
                && !data.hasEncounterReward(party.id())
                && SettlementSavedData.get(level.getServer()).settlements().stream().allMatch(settlement -> data.reputation(player.getUUID(), settlement.id()) == 0),
                "Remote group defeat cannot award global settlement reputation");
        helper.succeed();
    }

    private static HostileParty spawn(GameTestHelper helper, BlockPos center, PartyType type, boolean debug, boolean rewardEligible) {
        var result = EncounterSpawner.spawnAt(helper.getLevel(), center, type, debug, rewardEligible);
        helper.assertTrue(result.successful(), "Controlled " + type.id() + " fixture must spawn: " + result.failure());
        members(helper.getLevel(), result.party()).forEach(mob -> mob.setNoAi(true));
        helper.assertTrue(result.party().threatRating() == EncounterDefinition.fromConfig(type).threatRating(), "Threat is snapshotted from configuration");
        return result.party();
    }

    private static List<Mob> members(ServerLevel level, HostileParty party) {
        return party.remainingMembers().stream().map(id -> (Mob) level.getEntity(id)).toList();
    }

    private static void kill(GameTestHelper helper, Mob mob, DamageSource source) {
        helper.assertTrue(mob.hurt(source, 1000) && !mob.isAlive(), "Real server damage must kill the encounter member");
    }

    private static Settlement allied(ServerLevel level, BlockPos center) {
        Settlement settlement = Settlement.founding(UUID.randomUUID(), new Territory(level.dimension().location().toString(),
                center.getX(), center.getY(), center.getZ(), 24), 5);
        SettlementSavedData.get(level.getServer()).add(settlement);
        return settlement;
    }

    private static void preparePlot(ServerLevel level, BlockPos center) {
        for (int x = (center.getX() - 25) >> 4; x <= (center.getX() + 25) >> 4; x++) {
            for (int z = (center.getZ() - 25) >> 4; z <= (center.getZ() + 25) >> 4; z++) level.getChunk(x, z);
        }
        for (int x = -24; x <= 24; x++) {
            for (int z = -24; z <= 24; z++) {
                level.setBlock(center.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
                for (int y = 0; y < 8; y++) level.setBlock(center.offset(x, y, z), Blocks.AIR.defaultBlockState(), FLAGS);
            }
        }
    }

    private static RecordingPlayer player(ServerLevel level, BlockPos position) {
        RecordingPlayer player = new RecordingPlayer(level);
        player.setPos(position.getX() + 0.5, position.getY(), position.getZ() + 0.5);
        return player;
    }

    private static final class RecordingPlayer extends FakePlayer {
        final List<Component> messages = new ArrayList<>();
        RecordingPlayer(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "EncounterTest")); }
        @Override public void displayClientMessage(Component message, boolean overlay) { messages.add(message); }
        @Override public void sendSystemMessage(Component message) { messages.add(message); }
    }
}

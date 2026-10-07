package dev.livingkingdoms.gametest;

import com.mojang.authlib.GameProfile;
import dev.livingkingdoms.encounter.EncounterEvents;
import dev.livingkingdoms.encounter.EncounterQueries;
import dev.livingkingdoms.encounter.EncounterSpawner;
import dev.livingkingdoms.encounter.domain.HostileParty;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@GameTestHolder("livingkingdoms_tests")
@PrefixGameTestTemplate(false)
public final class DynamicOutcomeGameTests {
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void factionsDefeatingEachOtherNeverRewardAnObserver(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(28672, 0, 28672));
        prepare(level, center);
        Settlement allied = allied(level, center.offset(-50, 0, 0));
        FakePlayer observer = player(level, center);
        HostileParty patrol = spawn(helper, center, PartyType.PILLAGER_PATROL);
        HostileParty horde = spawn(helper, center.offset(0, 0, 16), PartyType.UNDEAD_HORDE);
        Mob undead = members(level, horde).getFirst();
        for (Mob pillager : members(level, patrol)) helper.assertTrue(pillager.hurt(level.damageSources().mobAttack(undead), 1000), "Actual faction damage kills Pillagers");
        EncounterSavedData data = EncounterSavedData.get(level.getServer());
        QuestSavedData reputation = QuestSavedData.get(level.getServer());
        helper.assertTrue(data.get(patrol.id()).orElseThrow().state() == PartyState.DEFEATED
                && data.get(horde.id()).orElseThrow().state() == PartyState.ALIVE
                && reputation.reputation(observer.getUUID(), allied.id()) == 0 && !reputation.hasEncounterReward(patrol.id()),
                "An Undead victory defeats only its victims; watching earns no reputation");
        for (Mob member : members(level, horde)) member.hurt(level.damageSources().generic(), 1000);
        helper.assertTrue(data.get(horde.id()).orElseThrow().state() == PartyState.DEFEATED
                && !reputation.hasEncounterReward(horde.id()), "Both states resolve without assigning passive credit");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void meaningfulParticipantsReceiveCreditEvenWhenAnotherFactionFinishes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(29696, 0, 29696));
        prepare(level, center);
        Settlement allied = allied(level, center.offset(-50, 0, 0));
        HostileParty patrol = spawn(helper, center, PartyType.PILLAGER_PATROL);
        HostileParty horde = spawn(helper, center.offset(0, 0, 16), PartyType.UNDEAD_HORDE);
        FakePlayer a = player(level, center), b = player(level, center), minor = player(level, center), observer = player(level, center);
        List<Mob> pillagers = members(level, patrol);
        pillagers.get(0).hurt(level.damageSources().playerAttack(a), 8);
        pillagers.get(1).hurt(level.damageSources().playerAttack(b), 8);
        pillagers.get(2).hurt(level.damageSources().playerAttack(minor), 1);
        EncounterSavedData data = EncounterSavedData.get(level.getServer());
        long now = level.getServer().overworld().getGameTime();
        EncounterSavedData reopened = EncounterSavedData.load(data.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
        helper.assertTrue(reopened.eligibleParticipants(patrol.id(), now, 4, 6000).equals(Set.of(a.getUUID(), b.getUUID())),
                "Damage participation survives reload and excludes observers and trivial damage");
        var reference = EncounterQueries.find(level, patrol.id()).orElseThrow();
        helper.assertTrue(reference.encounterId().equals(patrol.id()) && reference.faction() == patrol.faction()
                && reference.region().equals(patrol.origin()) && reference.threatRating() == patrol.threatRating()
                && reference.hostileSettlementId() == null
                && EncounterQueries.activeNear(level, center, 32).size() == 2,
                "Future quests can query regional UUID/faction/threat metadata without reading entities");
        Mob undead = members(level, horde).getFirst();
        // Ignore invulnerability windows so these synchronous assertions use genuine additional lethal damage.
        for (Mob pillager : pillagers) {
            pillager.invulnerableTime = 0;
            pillager.hurt(level.damageSources().mobAttack(undead), 1000);
        }
        QuestSavedData reputation = QuestSavedData.get(level.getServer());
        helper.assertTrue(reputation.reputation(a.getUUID(), allied.id()) == patrol.reputationReward()
                && reputation.reputation(b.getUUID(), allied.id()) == patrol.reputationReward()
                && reputation.reputation(minor.getUUID(), allied.id()) == 0
                && reputation.reputation(observer.getUUID(), allied.id()) == 0,
                "Meaningful contributors earn a regional reward even when Undead deal the final blow");
        EncounterEvents.onDeath(new LivingDeathEvent(pillagers.getLast(), level.damageSources().playerAttack(observer)));
        helper.assertTrue(reputation.reputation(observer.getUUID(), allied.id()) == 0
                && reputation.reputation(a.getUUID(), allied.id()) == patrol.reputationReward(), "Late/duplicate death notifications cannot claim or repeat a reward");
        members(level, horde).forEach(Mob::discard);
        helper.succeed();
    }

    private static HostileParty spawn(GameTestHelper helper, BlockPos center, PartyType type) {
        var result = EncounterSpawner.spawnAt(helper.getLevel(), center, type, false, true);
        helper.assertTrue(result.successful(), "Outcome fixture must spawn " + type);
        members(helper.getLevel(), result.party()).forEach(mob -> mob.setNoAi(true));
        return result.party();
    }

    private static List<Mob> members(ServerLevel level, HostileParty party) {
        return party.remainingMembers().stream().map(id -> (Mob) level.getEntity(id)).toList();
    }

    private static FakePlayer player(ServerLevel level, BlockPos center) {
        FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "OutcomeTest"));
        player.setPos(center.getX(), center.getY(), center.getZ());
        return player;
    }

    private static Settlement allied(ServerLevel level, BlockPos center) {
        Settlement settlement = Settlement.founding(UUID.randomUUID(), new Territory(level.dimension().location().toString(),
                center.getX(), center.getY(), center.getZ(), 24), 5);
        SettlementSavedData.get(level.getServer()).add(settlement);
        return settlement;
    }

    private static void prepare(ServerLevel level, BlockPos center) {
        for (int x = (center.getX() - 24) >> 4; x <= (center.getX() + 24) >> 4; x++) {
            for (int z = (center.getZ() - 24) >> 4; z <= (center.getZ() + 24) >> 4; z++) level.getChunk(x, z);
        }
        for (int x = -24; x <= 24; x++) for (int z = -24; z <= 24; z++) {
            level.setBlock(center.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            for (int y = 0; y < 8; y++) level.setBlock(center.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
    }
}

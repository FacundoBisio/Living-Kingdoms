package dev.livingkingdoms.gametest;

import dev.livingkingdoms.encounter.EncounterDefinition;
import dev.livingkingdoms.encounter.EncounterMember;
import dev.livingkingdoms.encounter.EncounterSpawner;
import dev.livingkingdoms.encounter.domain.HostileParty;
import dev.livingkingdoms.encounter.domain.PartyType;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import dev.livingkingdoms.faction.Faction;
import dev.livingkingdoms.faction.FactionEntityResolver;
import dev.livingkingdoms.npckingdom.persistence.NpcKingdomSavedData;
import dev.livingkingdoms.profession.GuardWork;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.war.WarService;
import dev.livingkingdoms.war.WarStandards;
import dev.livingkingdoms.war.domain.KingdomStance;
import dev.livingkingdoms.war.domain.RenownLedger;
import dev.livingkingdoms.war.domain.WarFront;
import dev.livingkingdoms.war.persistence.WarSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Hostile kingdoms: warband roster, war-front settlement on defeat, bounty, renown and war standards. */
@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WarGameTests {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void warbandsAreArmoredCaptainLedSoldiersOfTheEnemyKingdom(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(17408, 0, 17408));
        preparePlot(level, center);
        HostileParty party = spawn(helper, center, 5, false, true);
        helper.assertTrue(party.faction() == Faction.ENEMY_KINGDOM && party.type() == PartyType.WARBAND && party.memberIds().size() == 5,
                "Warband has the enemy kingdom faction, its own type and the requested size");
        List<Mob> members = members(level, party);
        helper.assertTrue(members.stream().filter(mob -> mob.getItemBySlot(EquipmentSlot.HEAD).is(Items.BLACK_BANNER)).count() == 1,
                "Exactly one member, the captain, carries the black standard");
        helper.assertTrue(members.stream().filter(mob -> mob instanceof Pillager).count() == 2
                && members.stream().filter(mob -> mob instanceof Vindicator).count() == 3, "Captain plus alternating crossbowmen and axemen");
        for (Mob mob : members) {
            Raider raider = (Raider) mob;
            helper.assertTrue(!raider.isPatrolLeader() && !raider.canJoinRaid(), "Warbands never lead or join vanilla raids");
            helper.assertTrue(!mob.getItemBySlot(EquipmentSlot.CHEST).isEmpty(), "Kingdom soldiers are always armored");
            helper.assertTrue(EncounterMember.read(mob).orElseThrow().faction() == Faction.ENEMY_KINGDOM
                    && FactionEntityResolver.combatFaction(mob).orElseThrow() == Faction.ENEMY_KINGDOM, "Soldiers resolve to the enemy kingdom");
            helper.assertTrue(GuardWork.hostile(mob), "Player kingdom guards treat every soldier as hostile");
        }
        members.forEach(Mob::discard);
        EncounterSavedData.get(level.getServer()).remove(party.id());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void defeatingAWarbandPaysBountyRenownAndWeakensItsKingdom(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        BlockPos center = helper.absolutePos(new BlockPos(17920, 0, 17920));
        preparePlot(level, center);
        HostileParty party = spawn(helper, center, 4, false, true);
        UUID kingdom = UUID.randomUUID(), target = UUID.randomUUID(), player = UUID.randomUUID();
        helper.assertTrue(NpcKingdomSavedData.get(server).register(kingdom, "Aldric Ashford", 0), "Fixture NPC kingdom registers");
        WarSavedData war = WarSavedData.get(server);
        WarFront front = WarFront.initial(KingdomStance.HOSTILE).raidLaunched(0, 100).raidUnanswered().raidUnanswered();
        war.putFront(kingdom, front);
        war.addCampaign(party.id(), new WarSavedData.Campaign(kingdom, target, 0));
        int before = war.ledger(player).points();

        WarService.onPartyDefeated(level, party, Set.of(player), center);

        WarFront after = war.front(kingdom).orElseThrow();
        helper.assertTrue(after.strength() == front.strength() - 1 && after.raidsRepelled() == 1, "A repelled raid weakens its kingdom");
        helper.assertTrue(war.campaign(party.id()).isEmpty() && !war.hasCampaignAgainst(target), "The campaign is closed exactly once");
        int expected = RenownLedger.pointsForDefeat(PartyType.WARBAND, 4, party.levels().average());
        helper.assertTrue(war.ledger(player).points() == before + expected, "Each participant earns the party's renown");
        var loot = level.getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(3));
        helper.assertTrue(loot.stream().anyMatch(item -> item.getItem().is(Items.EMERALD))
                && loot.stream().anyMatch(item -> item.getItem().is(Items.IRON_INGOT)), "Warbands drop emeralds and iron");

        WarService.onPartyDefeated(level, party, Set.of(player), center);
        helper.assertTrue(war.front(kingdom).orElseThrow().equals(after), "A repeated defeat call cannot weaken the kingdom twice");

        level.getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(3)).forEach(ItemEntity::discard);
        members(level, party).forEach(Mob::discard);
        EncounterSavedData.get(server).remove(party.id());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void unrewardedFixturesPayNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(18432, 0, 18432));
        preparePlot(level, center);
        HostileParty party = spawn(helper, center, 3, true, false);
        UUID player = UUID.randomUUID();
        WarService.onPartyDefeated(level, party, Set.of(player), center);
        helper.assertTrue(WarSavedData.get(level.getServer()).ledger(player).points() == 0, "Debug fixtures earn no renown");
        helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(3)).isEmpty(), "Debug fixtures drop no bounty");
        members(level, party).forEach(Mob::discard);
        EncounterSavedData.get(level.getServer()).remove(party.id());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void warStandardsMarkTheEdgesOnlyOnFreeGround(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(18944, 0, 18944));
        preparePlot(level, center);
        // One diagonal holds a half slab (no sturdy top face): the standard there is skipped rather than forced.
        BlockPos blocked = center.offset(-14, 0, -14);
        level.setBlock(blocked, Blocks.STONE_SLAB.defaultBlockState(), FLAGS);
        Settlement kingdom = Settlement.founding(UUID.randomUUID(), new Territory(level.dimension().location().toString(),
                center.getX(), center.getY(), center.getZ(), 24), 5);
        int placed = WarStandards.place(level, kingdom);
        helper.assertTrue(placed == 3, "Three free diagonals receive a standard, the blocked one none: " + placed);
        helper.assertTrue(level.getBlockState(center.offset(14, 2, 14)).is(Blocks.BLACK_BANNER)
                && level.getBlockState(center.offset(14, 0, 14)).is(Blocks.DARK_OAK_FENCE), "Fence post topped by a black banner");
        helper.assertTrue(level.getBlockState(blocked).is(Blocks.STONE_SLAB) && level.getBlockState(blocked.above()).isAir(),
                "Existing blocks are never replaced");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void strongKingdomsSendVeteransAboveTheRegionalLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(19456, 0, 19456));
        preparePlot(level, center);
        var rules = dev.livingkingdoms.config.ProgressionConfig.regionalRules();
        var region = dev.livingkingdoms.progression.RegionalDifficultyService.at(level, center);
        var result = EncounterSpawner.spawnAt(level, center, EncounterDefinition.warband(4, 3), false, false);
        helper.assertTrue(result.successful(), "Veteran warband fixture must spawn: " + result.failure());
        var party = result.party();
        members(level, party).forEach(mob -> mob.setNoAi(true));
        int floor = Math.min(rules.maximumLevel(), Math.max(1, region.level().value() - rules.memberVariance()) + 3);
        helper.assertTrue(party.levels().minimum() >= floor, "Every veteran is at least three levels above the regional window");
        helper.assertTrue(members(level, party).stream().allMatch(mob -> dev.livingkingdoms.progression.EntityProgression.read(mob)
                .orElseThrow().level().value() >= floor), "Entity levels carry the bonus too");
        members(level, party).forEach(Mob::discard);
        EncounterSavedData.get(level.getServer()).remove(party.id());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void breakingTheGarrisonOfAWeakestKingdomForcesItsCapitulation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        BlockPos center = helper.absolutePos(new BlockPos(19968, 0, 19968));
        preparePlot(level, center);
        HostileParty garrison = spawn(helper, center, 3, false, true);
        UUID kingdom = UUID.randomUUID(), raid = UUID.randomUUID(), player = UUID.randomUUID();
        helper.assertTrue(NpcKingdomSavedData.get(server).register(kingdom, "Osric Greymoor", 0), "Fixture NPC kingdom registers");
        WarSavedData war = WarSavedData.get(server);
        war.putFront(kingdom, WarFront.initial(KingdomStance.HOSTILE).garrisonSpawned(garrison.id()));
        war.addCampaign(raid, new WarSavedData.Campaign(kingdom, UUID.randomUUID(), 0));

        WarService.onPartyDefeated(level, garrison, Set.of(player), center);

        var front = war.front(kingdom).orElseThrow();
        helper.assertTrue(front.stance() == KingdomStance.INDEPENDENT && front.garrisonParty() == null, "The kingdom sues for peace");
        helper.assertTrue(war.campaign(raid).isEmpty(), "Its raids in the field are recalled");
        int expected = RenownLedger.pointsForDefeat(PartyType.WARBAND, 3, garrison.levels().average())
                + dev.livingkingdoms.war.RewardService.CAPITULATION_RENOWN;
        helper.assertTrue(war.ledger(player).points() == expected, "Liberation earns bonus renown");
        helper.assertTrue(!WarService.isHostile(server, kingdom), "Combat and HUD stop treating it as an enemy");
        level.getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(3)).forEach(ItemEntity::discard);
        members(level, garrison).forEach(Mob::discard);
        EncounterSavedData.get(server).remove(garrison.id());
        helper.succeed();
    }

    private static HostileParty spawn(GameTestHelper helper, BlockPos center, int size, boolean debug, boolean reward) {
        var result = EncounterSpawner.spawnAt(helper.getLevel(), center, EncounterDefinition.warband(size), debug, reward);
        helper.assertTrue(result.successful(), "Warband fixture must spawn: " + result.failure());
        members(helper.getLevel(), result.party()).forEach(mob -> mob.setNoAi(true));
        return result.party();
    }

    private static List<Mob> members(ServerLevel level, HostileParty party) {
        return party.memberIds().stream().map(level::getEntity).filter(Mob.class::isInstance).map(Mob.class::cast).toList();
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
}

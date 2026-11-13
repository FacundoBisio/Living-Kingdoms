package dev.livingkingdoms.gametest;

import dev.livingkingdoms.construction.ConstructionService;
import dev.livingkingdoms.defense.DefenseService;
import dev.livingkingdoms.defense.persistence.DefenseSavedData;
import dev.livingkingdoms.economy.KingdomEconomy;
import dev.livingkingdoms.economy.StockResource;
import dev.livingkingdoms.encounter.EncounterMember;
import dev.livingkingdoms.encounter.domain.HostileParty;
import dev.livingkingdoms.encounter.domain.OriginRegion;
import dev.livingkingdoms.encounter.domain.PartyState;
import dev.livingkingdoms.encounter.domain.PartyType;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import dev.livingkingdoms.rpg.WallService;
import dev.livingkingdoms.rpg.domain.Fortification;
import dev.livingkingdoms.rpg.domain.WallDesign;
import dev.livingkingdoms.rpg.persistence.RpgSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.Set;
import java.util.UUID;

/** 0.36: no raids on a camp, patrols can be called in, walls with chosen gates, towers and gatehouses. */
@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FortressGameTests {
    private static Settlement establish(GameTestHelper h, ConstructionGameTests.Founded f) {
        var server = h.getLevel().getServer();
        for (int i = 0; i < 2; i++) {
            var entry = ConstructionService.current(server, f.settlement().id()).orElseThrow();
            h.assertTrue(ConstructionService.debugAdvance(server, entry.project().id(), 4, f.player()), "Founding project " + i + " completes");
        }
        return SettlementSavedData.get(server).get(f.settlement().id()).orElseThrow();
    }
    private static HostileParty party(GameTestHelper h, Settlement s, Mob mob, BlockPos at) {
        var level = h.getLevel(); var ids = Set.of(mob.getUUID());
        var party = new HostileParty(UUID.randomUUID(), PartyType.PILLAGER_PATROL.faction(), PartyType.PILLAGER_PATROL,
                new OriginRegion(level.dimension().location().toString(), at.getX(), at.getY(), at.getZ(), 8), s.id(), PartyState.ALIVE, ids, ids, 1, 2, false, true);
        EncounterSavedData.get(level.getServer()).add(party, level.getServer().overworld().getGameTime());
        EncounterMember.attach(mob, party.id(), party.faction());
        return party;
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void campsAreNotRaidedAndPatrolsCanBeCalledIn(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 451024);
        var level = h.getLevel(); var server = level.getServer(); var camp = f.settlement();
        var t = camp.territory();
        var near = new BlockPos(t.x() + 6, t.y(), t.z());
        var raider = EntityType.PILLAGER.create(level); raider.moveTo(near.getX() + .5, near.getY(), near.getZ() + .5); raider.setNoAi(true);
        var origin = new BlockPos(t.x() + 75, t.y(), t.z()); // only the record's origin: approach distance rules
        var party = party(h, camp, raider, origin); level.addFreshEntity(raider);
        h.assertTrue(DefenseService.detect(level, party, near).isEmpty() && DefenseSavedData.get(server).forParty(party.id()).isEmpty(), "A founding camp is never raided");
        var s = establish(h, f);
        h.assertTrue(DefenseService.beginApproach(level, s, party).isPresent(), "An established kingdom can be raided");
        h.assertTrue(DefenseService.summon(level, s.id()) == 1, "The patrol is called in");
        double distance = Math.hypot(raider.getX() - t.x(), raider.getZ() - t.z());
        h.assertTrue(distance <= t.radius() + 20 && distance >= t.radius(), "The patrol now waits at the kingdom's edge: " + distance);
        raider.discard();
        h.assertTrue(DefenseService.summon(level, s.id()) == 0 && DefenseService.current(server, s.id()).filter(e -> e.threatened()).isEmpty(),
                "A patrol with nobody left around ends the alert instead of hanging");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void wallsGetChosenGatesTowersAndGatehouses(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 452048);
        var s = establish(h, f); var level = h.getLevel(); var server = level.getServer();
        var data = RpgSavedData.get(server);
        data.putWall(s.id(), Fortification.none(WallService.radiusFor(s)).upgrade());
        WallService.buildAll(level, s.id());
        data.putWall(s.id(), new Fortification(1, WallService.radiusFor(s), 0, true).upgrade());
        WallService.buildAll(level, s.id());
        var wall = data.wall(s.id()).orElseThrow();
        h.assertTrue(wall.complete() && wall.tier() == 2, "Stone walls stand");
        var ring = WallService.ring(wall.radius()); var t = s.territory();
        var south = ring.get(WallDesign.nearest(ring, Math.PI / 2));
        var gateCol = new BlockPos(t.x() + south[0], t.y(), t.z() + south[1]);
        int pieces = 0;
        for (int y = t.y() - 6; y <= t.y() + 10; y++) if (WallService.wallBlock(level.getBlockState(new BlockPos(gateCol.getX(), y, gateCol.getZ())))) pieces++;
        h.assertTrue(pieces == 1, "The south gate is an opening under a single lintel: " + pieces);
        int ground = h.absolutePos(new BlockPos(452048, 1, 452048)).getY();
        boolean open = true;
        for (int dy = 0; dy <= 2; dy++) open &= level.getBlockState(new BlockPos(gateCol.getX(), ground + dy, gateCol.getZ())).isAir();
        h.assertTrue(open && WallService.wallBlock(level.getBlockState(new BlockPos(gateCol.getX(), ground + 3, gateCol.getZ()))),
                "The south gate lintel sits exactly at ground+3 over a three-high opening (open=" + open + ")");
        var player = f.player();
        for (int i = 0; i < 4; i++) { player.getInventory().setItem(0, new ItemStack(StockResource.STONE.item(), 64)); KingdomEconomy.deposit(player, s.id(), StockResource.STONE, 64); }
        for (int i = 0; i < 2; i++) { player.getInventory().setItem(0, new ItemStack(StockResource.WOOD.item(), 64)); KingdomEconomy.deposit(player, s.id(), StockResource.WOOD, 64); }
        player.getInventory().setItem(0, new ItemStack(StockResource.IRON.item(), 32)); KingdomEconomy.deposit(player, s.id(), StockResource.IRON, 32);
        player.getInventory().clearContent();
        h.assertTrue(WallService.cycleGates(player, s.id()) && data.design(s.id()).gates() == 3, "Gates cycle to three");
        h.assertTrue(WallService.orderTowers(player, s.id()) && data.design(s.id()).towers(), "Towers ordered and paid");
        h.assertTrue(!WallService.orderTowers(player, s.id()), "Towers are built once");
        h.assertTrue(WallService.posts(level, s).size() >= 4, "Tower tops are archer posts: " + WallService.posts(level, s).size());
        for (int i = 0; i < 3; i++) { player.getInventory().setItem(0, new ItemStack(StockResource.STONE.item(), 64)); KingdomEconomy.deposit(player, s.id(), StockResource.STONE, 64); }
        player.getInventory().clearContent();
        h.assertTrue(WallService.orderGatehouses(player, s.id()) && data.design(s.id()).gatehouses(), "Gatehouses built on stone walls");
        RpgSavedData.get(server).putWall(s.id(), Fortification.none(wall.radius()));
        WallService.clear();
        h.succeed();
    }
}

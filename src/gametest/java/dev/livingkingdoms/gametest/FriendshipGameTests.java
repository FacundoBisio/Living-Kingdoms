package dev.livingkingdoms.gametest;

import dev.livingkingdoms.citizen.domain.CitizenRole;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.npc.NpcService;
import dev.livingkingdoms.rpg.BondService;
import dev.livingkingdoms.rpg.ExpeditionService;
import dev.livingkingdoms.rpg.RpgClock;
import dev.livingkingdoms.rpg.domain.Bond;
import dev.livingkingdoms.rpg.domain.Expedition;
import dev.livingkingdoms.rpg.domain.PersonalRequest;
import dev.livingkingdoms.rpg.persistence.RpgSavedData;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FriendshipGameTests {
    private static dev.livingkingdoms.citizen.domain.Citizen resident(GameTestHelper h, ConstructionGameTests.Founded f) {
        return CitizenSavedData.get(h.getLevel().getServer()).citizens(f.settlement().id()).stream()
                .filter(c -> c.role() != CitizenRole.MAYOR).findFirst().orElseThrow();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void talkingAndFulfillingAWishRaisesTheBondOncePerDay(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 202048);
        var server = h.getLevel().getServer(); var citizen = resident(h, f);
        var villager = (Villager) h.getLevel().getEntity(citizen.entityId());
        var player = f.player(); player.getInventory().clearContent();
        h.assertTrue(BondService.talk(player, villager, citizen), "Members can talk to residents");
        h.assertTrue(RpgSavedData.get(server).bond(citizen.id()).value() == Bond.TALK, "Daily talk raises the bond");
        var wish = BondService.request(server, citizen);
        var item = BondService.item(wish);
        h.assertTrue(item.isPresent(), "Wish item exists in the registry: " + wish.item());
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item.orElseThrow(), wish.count()));
        h.assertTrue(BondService.talk(player, villager, citizen), "Delivery conversation");
        h.assertTrue(player.getInventory().countItem(item.orElseThrow()) == 0, "Wished items consumed");
        var bond = RpgSavedData.get(server).bond(citizen.id());
        h.assertTrue(bond.value() == Bond.TALK + Bond.REQUEST && !bond.canFulfill(RpgClock.today()), "Wish fulfilled once today");
        h.assertTrue(player.getInventory().countItem(Items.EMERALD) >= 3, "Wish reward paid");
        player.getInventory().selected = 8;
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item.orElseThrow(), wish.count()));
        BondService.talk(player, villager, citizen);
        h.assertTrue(player.getMainHandItem().getCount() == wish.count() && RpgSavedData.get(server).bond(citizen.id()).equals(bond), "No second delivery the same day");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void allWishItemsExistInTheRegistry(GameTestHelper h) {
        var citizen = java.util.UUID.randomUUID();
        for (var type : dev.livingkingdoms.profession.domain.ProfessionType.values())
            for (int day = 0; day < 60; day++) {
                var wish = PersonalRequest.of(citizen, day, type);
                h.assertTrue(BondService.item(wish).isPresent(), "Unknown wish item " + wish.item());
            }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void expeditionHidesTheResidentAndReturnsLoot(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 203072);
        var server = h.getLevel().getServer(); var citizen = resident(h, f);
        var villager = (Villager) h.getLevel().getEntity(citizen.entityId());
        h.assertTrue(ExpeditionService.eligible(server, citizen.id()), "Unassigned resident may travel");
        h.assertTrue(ExpeditionService.start(f.player(), f.settlement().id(), citizen.id(), Expedition.Kind.EXPLORE), "Expedition departs");
        h.assertTrue(villager.isInvisible() && villager.isNoAi() && villager.isInvulnerable() && ExpeditionService.away(villager), "Resident hidden while away");
        h.assertTrue(!ExpeditionService.start(f.player(), f.settlement().id(), citizen.id(), Expedition.Kind.FORAGE), "Cannot send twice");
        var trip = RpgSavedData.get(server).expedition(citizen.id()).orElseThrow();
        h.assertTrue(ExpeditionService.finish(server, trip), "Expedition returns");
        h.assertTrue(!villager.isInvisible() && !villager.isNoAi() && !villager.isInvulnerable() && !ExpeditionService.away(villager), "Resident visible again");
        // The test player is not in the server player list, so the loot waits for their next login.
        h.assertTrue(!RpgSavedData.get(server).pending(f.player().getUUID()).isEmpty(), "Offline sender's loot is kept for login");
        h.assertTrue(RpgSavedData.get(server).bond(citizen.id()).value() >= Bond.EXPEDITION, "Travel strengthens the bond");
        h.assertTrue(!ExpeditionService.finish(server, trip), "A trip returns once");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void theMayorNeverLeavesOnExpeditions(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 204096);
        var server = h.getLevel().getServer();
        var mayor = CitizenSavedData.get(server).citizens(f.settlement().id()).stream().filter(c -> c.role() == CitizenRole.MAYOR).findFirst();
        mayor.ifPresent(m -> h.assertTrue(!ExpeditionService.eligible(server, m.id()), "Mayor stays"));
        NpcService.ensureMayor(h.getLevel(), f.settlement()).orElseThrow();
        h.succeed();
    }
}

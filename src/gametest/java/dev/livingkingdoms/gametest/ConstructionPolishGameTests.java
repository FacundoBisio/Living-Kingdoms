package dev.livingkingdoms.gametest;

import dev.livingkingdoms.citizen.domain.CitizenRole;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.config.ConstructionConfig;
import dev.livingkingdoms.construction.ConstructionService;
import dev.livingkingdoms.construction.domain.ConstructionState;
import dev.livingkingdoms.construction.persistence.ConstructionSavedData;
import dev.livingkingdoms.item.KingdomItems;
import dev.livingkingdoms.npc.CitizenEquipment;
import dev.livingkingdoms.npc.NpcService;
import dev.livingkingdoms.profession.domain.ProfessionType;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.quest.expansion.domain.ResourceKind;
import dev.livingkingdoms.rpg.RefinementService;
import dev.livingkingdoms.settlement.domain.SettlementLifecycle;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ConstructionPolishGameTests {
    private static void freezeResidents(GameTestHelper h, ConstructionGameTests.Founded f) {
        var mayor = NpcService.ensureMayor(h.getLevel(), f.settlement()).orElseThrow();
        for (int i = 0; i < 2; i++) if (h.getLevel().getEntity(mayor.getPersistentData().getUUID("livingkingdoms:founding_resident_" + i)) instanceof Villager v) v.setNoAi(true);
    }
    private static void fund(ConstructionGameTests.Founded f, ConstructionSavedData.Entry entry) {
        var inventory = f.player().getInventory(); inventory.clearContent(); int slot = 0;
        for (var cost : entry.project().required().entrySet()) {
            var item = switch (cost.getKey()) { case LOGS -> Items.OAK_LOG; case STONE -> Items.STONE; case IRON_INGOT -> Items.IRON_INGOT; case WHEAT -> Items.WHEAT; };
            int remaining = cost.getValue();
            while (remaining > 0) { int n = Math.min(64, remaining); inventory.items.set(slot++, new ItemStack(item, n)); remaining -= n; }
        }
    }
    private static int solidBlocks(GameTestHelper h, ConstructionSavedData.Entry entry) {
        var box = entry.plan().buildings().getFirst().volume(); int count = 0;
        for (var pos : BlockPos.betweenClosed(box.minX(), box.minY() + 1, box.minZ(), box.maxX(), box.maxY(), box.maxZ()))
            if (!h.getLevel().getBlockState(pos).isAir() && !h.getLevel().getBlockState(pos).canBeReplaced()) count++;
        return count;
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void timedFoundingHouseRisesGraduallyAndClearsObstacles(GameTestHelper h) {
        long original = ConstructionConfig.TEST_DURATION.get(); ConstructionConfig.TEST_DURATION.set(400);
        final ConstructionGameTests.Founded f;
        try { f = ConstructionGameTests.found(h, 200000); } finally { ConstructionConfig.TEST_DURATION.set((int) original); }
        freezeResidents(h, f);
        var server = h.getLevel().getServer();
        var entry = ConstructionService.current(server, f.settlement().id()).orElseThrow();
        // A foreign block dropped into the reserved site used to fail the project permanently.
        var intruder = entry.plan().buildings().getFirst().origin().offset(2, 2, 2);
        h.getLevel().setBlock(intruder, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        fund(f, entry);
        h.assertTrue(ConstructionService.deposit(f.player(), f.settlement().id(), entry.project().id()), "First House funded");
        int[] samples = new int[3];
        h.runAfterDelay(160, () -> samples[0] = solidBlocks(h, entry));
        h.runAfterDelay(300, () -> samples[1] = solidBlocks(h, entry));
        h.runAfterDelay(320, () -> {
            var project = ConstructionSavedData.get(server).get(entry.project().id()).orElseThrow().project();
            h.assertTrue(project.state() != ConstructionState.FAILED, "An obstacle never fails the project");
            h.assertTrue(!h.getLevel().getBlockState(intruder).is(Blocks.GOLD_BLOCK), "The foreign block was cleared by the crew");
            h.assertTrue(samples[0] > 0 && samples[1] > samples[0], "Blocks appear gradually: " + samples[0] + " then " + samples[1]);
        });
        h.succeedWhen(() -> {
            var p = ConstructionSavedData.get(server).get(entry.project().id()).orElseThrow().project();
            h.assertTrue(p.state() == ConstructionState.COMPLETED, "Timed house completes after gradual construction: " + p.state() + " stage " + p.visualStage()
                    + " reason " + ConstructionService.reason(p.id()) + " obstacles " + ConstructionService.obstacles(p.id()));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void establishmentGivesTheKingdomItsFirstGuard(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 201024);
        freezeResidents(h, f);
        var server = h.getLevel().getServer();
        for (int i = 0; i < 2; i++) {
            var entry = ConstructionService.current(server, f.settlement().id()).orElseThrow();
            h.assertTrue(ConstructionService.debugAdvance(server, entry.project().id(), 4, f.player()), "Founding project " + i + " completes");
        }
        h.assertTrue(SettlementSavedData.get(server).get(f.settlement().id()).orElseThrow().lifecycle() == SettlementLifecycle.ESTABLISHED, "Established");
        var guards = ProfessionSavedData.get(server).professions(f.settlement().id()).stream().filter(p -> p.active() && p.type() == ProfessionType.GUARD).toList();
        h.assertTrue(guards.size() == 1, "Exactly one starting Guard, got " + guards.size());
        var guard = CitizenSavedData.get(server).citizen(guards.getFirst().citizenId()).orElseThrow();
        h.assertTrue(guard.role() != CitizenRole.MAYOR, "The Mayor never becomes the Guard");
        // Arming the Guard is a one-time kingdom task.
        var villager = (Villager) h.getLevel().getEntity(guard.entityId());
        var player = f.player(); player.getInventory().clearContent();
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        h.assertTrue(CitizenEquipment.equip(player, villager), "Guard accepts the sword");
        h.assertTrue(villager.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.IRON_SWORD) && player.getInventory().countItem(Items.IRON_SWORD) == 0,
                "Sword moved to the Guard: villager " + villager.getItemBySlot(EquipmentSlot.MAINHAND) + ", player " + player.getMainHandItem() + ", messages " + player.clientMessages);
        h.assertTrue(player.getInventory().countItem(KingdomItems.ROYAL_WHETSTONE.get()) == 1, "First armed Guard pays its task reward once");
        player.getInventory().selected = 8; // Keep the whetstone reward in its slot.
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
        h.assertTrue(CitizenEquipment.equip(player, villager), "Upgrade swap");
        h.assertTrue(player.getInventory().countItem(Items.IRON_SWORD) == 1 && player.getInventory().countItem(KingdomItems.ROYAL_WHETSTONE.get()) == 1,
                "Previous player-given sword returned; no second reward: swords " + player.getInventory().countItem(Items.IRON_SWORD)
                        + ", whetstones " + player.getInventory().countItem(KingdomItems.ROYAL_WHETSTONE.get()) + ", villager " + villager.getMainHandItem());
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void armorRefinementAddsArmorAndToughness(GameTestHelper h) {
        var chest = new ItemStack(Items.DIAMOND_CHESTPLATE);
        RefinementService.apply(chest, 5);
        double armor = 0, toughness = 0;
        for (var e : chest.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).modifiers()) {
            if (e.attribute().is(Attributes.ARMOR)) armor += e.modifier().amount();
            if (e.attribute().is(Attributes.ARMOR_TOUGHNESS)) toughness += e.modifier().amount();
        }
        h.assertTrue(Math.abs(armor - 10.5) < 1e-6 && Math.abs(toughness - 3.25) < 1e-6, "Diamond chestplate +5: armor " + armor + ", toughness " + toughness);
        h.succeed();
    }
}

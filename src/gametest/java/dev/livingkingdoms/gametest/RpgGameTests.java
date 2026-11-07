package dev.livingkingdoms.gametest;

import dev.livingkingdoms.citizen.domain.CitizenRole;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.item.KingdomItems;
import dev.livingkingdoms.rpg.ChallengeService;
import dev.livingkingdoms.rpg.RallyService;
import dev.livingkingdoms.rpg.RefinementService;
import dev.livingkingdoms.rpg.RewardCeremony;
import dev.livingkingdoms.rpg.domain.ChallengeTier;
import dev.livingkingdoms.rpg.domain.QuestBonus;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RpgGameTests {
    private static double damage(ItemStack stack) {
        // Player base attack damage is 1; only ATTACK_DAMAGE modifiers add on top (compute() would also sum attack speed).
        double[] total = {1.0};
        stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).forEach(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.is(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)) total[0] += modifier.amount();
        });
        return total[0];
    }

    @GameTest(template = "empty")
    public static void refinementRaisesDiamondSwordFromSevenToNine(GameTestHelper h) {
        var sword = new ItemStack(Items.DIAMOND_SWORD);
        h.assertTrue(Math.abs(damage(sword) - 7) < 1e-6, "Vanilla diamond sword deals 7");
        RefinementService.apply(sword, 5);
        h.assertTrue(Math.abs(damage(sword) - 9) < 1e-6, "Fully refined diamond sword deals 9, got " + damage(sword));
        RefinementService.apply(sword, 2);
        h.assertTrue(Math.abs(damage(sword) - 7.8) < 1e-6 && RefinementService.level(sword) == 2, "Re-applying replaces instead of stacking");
        h.assertTrue(sword.getOrDefault(DataComponents.LORE, net.minecraft.world.item.component.ItemLore.EMPTY).lines().size() == 1, "One refinement lore line");
        RefinementService.apply(sword, 0);
        h.assertTrue(Math.abs(damage(sword) - 7) < 1e-6, "Level 0 removes the modifier");
        h.assertTrue(!RefinementService.refinable(new ItemStack(Items.BOW)) && !RefinementService.refinable(new ItemStack(Items.DIAMOND_SWORD, 2)),
                "Only single melee weapons can be refined");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void rewardCeremonyGrantsDerivedBonusOnce(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 190000);
        var player = f.player(); player.getInventory().clearContent();
        int xp = player.totalExperience;
        RewardCeremony.questClaimed(player, f.settlement().id(), false, "quest.livingkingdoms.main_return.title", 8, 10, new QuestBonus(80, 2, 1));
        int whetstones = player.getInventory().countItem(KingdomItems.ROYAL_WHETSTONE.get());
        h.assertTrue(whetstones == 2, "Two whetstones granted, got " + whetstones);
        h.assertTrue(player.getInventory().countItem(Items.ENCHANTED_BOOK) == 1, "One kingdom tome granted");
        h.assertTrue(player.totalExperience == xp + 80, "Bonus experience granted");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void lockedTrialIsRefusedOutsideDebug(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 191024);
        h.assertTrue(!ChallengeService.start(f.player(), ChallengeTier.SKIRMISH, false), "A fresh founder has not unlocked trials");
        h.assertTrue(!ChallengeService.running(f.player().getUUID()), "No run recorded after a refusal");
        var status = ChallengeService.status(f.player(), f.settlement());
        h.assertTrue(!status.getBoolean("unlocked") && status.getList("tiers", 10).size() == ChallengeTier.values().length, "Board status lists every locked tier");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void skirmishTrialRunsWavesAndPaysOnVictory(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 192048);
        var player = f.player(); player.getInventory().clearContent(); player.getAbilities().invulnerable = true;
        var center = player.blockPosition();
        h.assertTrue(ChallengeService.start(player, ChallengeTier.SKIRMISH, true), "Operator trial starts inside the kingdom");
        h.assertTrue(ChallengeService.running(player.getUUID()), "Run is tracked");
        var box = new AABB(center).inflate(40);
        var spawned = h.getLevel().getEntitiesOfClass(Mob.class, box, mob -> mob.getPersistentData().hasUUID(ChallengeService.RUN_TAG));
        h.assertTrue(!spawned.isEmpty() && spawned.size() <= ChallengeTier.SKIRMISH.enemies(0), "First wave spawned around the player: " + spawned.size());
        h.assertTrue(spawned.stream().allMatch(mob -> mob.getTarget() == player), "Trial members target their challenger");
        h.onEachTick(() -> {
            if (h.getTick() % 20 != 0) return;
            for (var mob : h.getLevel().getEntitiesOfClass(Mob.class, box, m -> m.getPersistentData().hasUUID(ChallengeService.RUN_TAG))) mob.kill();
        });
        h.succeedWhen(() -> {
            h.assertTrue(!ChallengeService.running(player.getUUID()), "Run finished");
            h.assertTrue(player.getInventory().countItem(KingdomItems.ROYAL_WHETSTONE.get()) == ChallengeTier.SKIRMISH.whetstones(), "Victory whetstones paid once");
            h.assertTrue(player.getInventory().countItem(Items.EMERALD) == ChallengeTier.SKIRMISH.emeralds(), "Victory emeralds paid once");
            h.assertTrue(!ChallengeService.start(player, ChallengeTier.SKIRMISH, false), "Cooldown/lock prevents an immediate repeat");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void abandonedTrialRemovesItsMembers(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 193072);
        var player = f.player(); var box = new AABB(player.blockPosition()).inflate(40);
        h.assertTrue(ChallengeService.start(player, ChallengeTier.SIEGE, true), "Siege starts");
        h.assertTrue(!h.getLevel().getEntitiesOfClass(Mob.class, box, m -> m.getPersistentData().hasUUID(ChallengeService.RUN_TAG)).isEmpty(), "Members exist");
        ChallengeService.abandon(player);
        h.runAfterDelay(2, () -> {
            h.assertTrue(h.getLevel().getEntitiesOfClass(Mob.class, box, m -> m.getPersistentData().hasUUID(ChallengeService.RUN_TAG) && m.isAlive()).isEmpty(),
                    "Abandoning discards every remaining member");
            h.assertTrue(!ChallengeService.running(player.getUUID()), "Run cleared");
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void rallyCallsResidentsButNotTheMayor(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 194096);
        var level = h.getLevel(); var server = level.getServer();
        var people = CitizenSavedData.get(server).citizens(f.settlement().id());
        var resident = people.stream().filter(c -> c.role() != CitizenRole.MAYOR).findFirst().orElseThrow();
        var mayor = people.stream().filter(c -> c.role() == CitizenRole.MAYOR).findFirst();
        h.assertTrue(level.getEntity(resident.entityId()) instanceof Villager, "Resident entity is loaded");
        var villager = (Villager) level.getEntity(resident.entityId());
        h.assertTrue(!RallyService.steer(villager, resident), "No rally, no control");
        h.assertTrue(RallyService.callAll(f.player(), f.settlement().id()), "Founder may call residents inside the territory");
        h.assertTrue(RallyService.active(level, f.settlement().id()), "Rally is active");
        h.assertTrue(RallyService.answers(villager, resident) && RallyService.steer(villager, resident), "Resident answers the call");
        mayor.ifPresent(m -> { if (level.getEntity(m.entityId()) instanceof Villager v) h.assertTrue(!RallyService.steer(v, m), "The Mayor stays at the plaza"); });
        h.assertTrue(RallyService.cancel(f.player(), f.settlement().id()) && !RallyService.steer(villager, resident), "Release returns control to the routine");
        f.player().setPos(f.player().getX() + 4000, f.player().getY(), f.player().getZ());
        h.assertTrue(!RallyService.callAll(f.player(), f.settlement().id()), "Calls require standing in the territory");
        h.succeed();
    }
}

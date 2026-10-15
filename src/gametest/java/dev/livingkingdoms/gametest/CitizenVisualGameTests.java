package dev.livingkingdoms.gametest;

import dev.livingkingdoms.citizen.CitizenService;
import dev.livingkingdoms.citizen.domain.*;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.citizenvisual.CitizenVisualService;
import dev.livingkingdoms.npc.NpcIdentity;
import dev.livingkingdoms.npc.NpcRole;
import dev.livingkingdoms.profession.domain.ProfessionType;
import dev.livingkingdoms.profession.domain.Profession;
import dev.livingkingdoms.profession.domain.WorkState;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.BuildingKind;
import dev.livingkingdoms.structure.ArchitectureStyle;
import dev.livingkingdoms.structure.PlotBounds;
import dev.livingkingdoms.structure.SettlementLayoutMetadata;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;
import java.util.Set;
import java.util.List;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CitizenVisualGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void presentationRequiresRealRegistrationAndPreservesNativeEntity(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(710656, 2, 710656));
        level.getChunkAt(pos);
        var settlement = Settlement.established(UUID.randomUUID(), new Territory(level.dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(), 4), 1, SettlementOrigin.FOUNDED, UUID.randomUUID());
        SettlementSavedData.get(level.getServer()).add(settlement);
        var villager = EntityType.VILLAGER.create(level);
        villager.moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, 0, 0);
        helper.assertTrue(level.addFreshEntity(villager), "Native fixture spawned");
        var uuid = villager.getUUID(); var career = villager.getVillagerData(); boolean ai = villager.isNoAi();
        helper.assertTrue(CitizenVisualService.snapshot(villager) == null, "Ordinary villagers keep vanilla rendering");
        NpcIdentity.attach(villager, settlement.id(), NpcRole.RESIDENT);
        helper.assertTrue(CitizenVisualService.snapshot(villager) == null, "NPC tags alone cannot impersonate registered citizens");
        var citizen = new Citizen(UUID.randomUUID(), uuid, settlement.id(), "Mara Willow", new LevelValue(4),
                CitizenRole.UNASSIGNED, null, CitizenState.ACTIVE, 0);
        var citizens = CitizenSavedData.get(level.getServer());
        helper.assertTrue(citizens.register(citizen), "Registered real identity");
        var jobs = ProfessionSavedData.get(level.getServer()); jobs.initialize(citizen);
        var beforeNbt = villager.saveWithoutId(new CompoundTag());
        var presentation = CitizenVisualService.snapshot(villager);
        helper.assertTrue(presentation != null && presentation.entityUuid().equals(uuid) && presentation.level() == 4
                && presentation.profession() == ProfessionType.UNASSIGNED && presentation.state() == WorkState.IDLE,
                "Projection comes from citizen/profession authority");
        helper.assertTrue(villager.getUUID().equals(uuid) && villager.getVillagerData().equals(career) && villager.isNoAi() == ai
                && beforeNbt.equals(villager.saveWithoutId(new CompoundTag())), "Snapshot never changes UUID, AI, inventory or native state");
        var initial = jobs.profession(citizen.id()).orElseThrow();
        // GameTests share the server's SavedData. A random nonexistent workplace
        // poisons other tests' whole-store reloads depending on their execution order.
        var barracks = new SettlementLayoutMetadata.Building(BuildingKind.BARRACKS,
                ResourceLocation.fromNamespaceAndPath("livingkingdoms", "allied/plains/barracks"), pos,
                Rotation.NONE, new PlotBounds(pos.getX(), pos.getZ(), pos.getX() + 2, pos.getZ() + 2), pos.east());
        jobs.synchronize(settlement, new SettlementLayoutMetadata(ArchitectureStyle.PLAINS,
                List.of(barracks), List.of(pos.below()), List.of()), 0, 1, List.of(citizen));
        var workplace = jobs.buildings(settlement.id()).getFirst();
        var guard = new Profession(citizen.id(), settlement.id(), ProfessionType.GUARD, new LevelValue(3),
                40, workplace.id(), true, WorkState.PATROLLING, 0, 0, 0, Set.of());
        helper.assertTrue(jobs.replace(initial, guard), "Real stored profession changed");
        var reloadedJobs = ProfessionSavedData.load(jobs.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
        helper.assertTrue(reloadedJobs.profession(citizen.id()).orElseThrow().equals(guard)
                && reloadedJobs.building(workplace.id()).isPresent(), "Visual fixture retains a valid persisted workplace");
        villager.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        villager.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        villager.setDropChance(EquipmentSlot.MAINHAND, .1F);
        villager.setDropChance(EquipmentSlot.OFFHAND, .2F);
        var equipped = villager.saveWithoutId(new CompoundTag());
        var guardView = CitizenVisualService.snapshot(villager);
        helper.assertTrue(guardView != null && guardView.profession() == ProfessionType.GUARD
                && guardView.professionLevel() == 3 && guardView.state() == WorkState.PATROLLING
                && !guardView.showsProfessionTool() && equipped.equals(villager.saveWithoutId(new CompoundTag())),
                "Guard projection uses stored progress and leaves actual weapon, shield and drops unchanged");
        var loaded = EntityType.VILLAGER.create(level);
        loaded.load(equipped);
        var loadedView = CitizenVisualService.snapshot(loaded);
        helper.assertTrue(loaded.getUUID().equals(uuid) && loaded.getMainHandItem().is(Items.IRON_SWORD)
                && loaded.getOffhandItem().is(Items.SHIELD) && loaded.isNoAi() == ai
                && loadedView != null && loadedView.skinVariant() == guardView.skinVariant()
                && loadedView.profession() == ProfessionType.GUARD && loadedView.experience() == 40,
                "Native entity NBT reload preserves identity, equipment, AI and stable appearance");
        villager.getPersistentData().putUUID(CitizenService.CITIZEN_ID_KEY, UUID.randomUUID());
        helper.assertTrue(CitizenVisualService.snapshot(villager) == null, "Conflicting citizen metadata is rejected");
        villager.getPersistentData().remove(CitizenService.CITIZEN_ID_KEY);
        citizens.markDead(uuid);
        jobs.retire(citizen.id());
        helper.assertTrue(CitizenVisualService.snapshot(villager) == null, "Retired/dead citizens lose visual authority");
        villager.discard(); helper.succeed();
    }
}

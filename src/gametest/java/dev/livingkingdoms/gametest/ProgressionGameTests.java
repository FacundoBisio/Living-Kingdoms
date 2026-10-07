package dev.livingkingdoms.gametest;

import com.mojang.authlib.GameProfile;
import dev.livingkingdoms.config.ProgressionConfig;
import dev.livingkingdoms.encounter.EncounterMember;
import dev.livingkingdoms.encounter.EncounterSpawner;
import dev.livingkingdoms.encounter.domain.*;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import dev.livingkingdoms.faction.Faction;
import dev.livingkingdoms.progression.*;
import dev.livingkingdoms.progression.domain.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProgressionGameTests {
    private static final double EPS = .001;

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void scaledAttributesPersistWithoutStackingOrReloadHealing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel(); BlockPos center = helper.absolutePos(new BlockPos(51200, 0, 51200)); prepare(level, center);
        Zombie zombie = EntityType.ZOMBIE.create(level); position(zombie, center);
        double baseHealth = zombie.getMaxHealth(), baseDamage = zombie.getAttributeValue(Attributes.ATTACK_DAMAGE),
                baseArmor = zombie.getAttributeValue(Attributes.ARMOR), baseSpeed = zombie.getAttributeValue(Attributes.MOVEMENT_SPEED);
        register(level, zombie, PartyType.UNDEAD_HORDE, 10, false);
        var profile = EntityProgression.read(zombie).orElseThrow();
        close(helper, zombie.getMaxHealth(), baseHealth * (1 + profile.stats().healthBonus()), "Level scales actual max health");
        close(helper, zombie.getAttributeValue(Attributes.ATTACK_DAMAGE), baseDamage * (1 + profile.stats().damageBonus()), "Level scales actual melee damage");
        close(helper, zombie.getAttributeValue(Attributes.ARMOR), baseArmor + profile.stats().armorBonus(), "Level adds bounded armor");
        close(helper, zombie.getAttributeValue(Attributes.MOVEMENT_SPEED), baseSpeed * (1 + profile.stats().speedBonus()), "Level scales bounded movement");
        var otherId = ResourceLocation.fromNamespaceAndPath("livingkingdoms_tests", "other_modifier");
        zombie.getAttribute(Attributes.ATTACK_DAMAGE).addPermanentModifier(new AttributeModifier(otherId, 1, AttributeModifier.Operation.ADD_VALUE));
        zombie.hurt(level.damageSources().generic(), 5);
        float wounded = zombie.getHealth(); double damage = zombie.getAttributeValue(Attributes.ATTACK_DAMAGE);
        CompoundTag saved = zombie.saveWithoutId(new CompoundTag()); zombie.discard();
        Zombie restored = EntityType.ZOMBIE.create(level); restored.load(saved);
        helper.assertTrue(level.addFreshEntity(restored), "Saved member must rejoin its authoritative party");
        for (int i = 0; i < 3; i++) ProgressionEvents.onJoin(new EntityJoinLevelEvent(restored, level, true));
        helper.assertTrue(EntityProgression.read(restored).orElseThrow().equals(profile), "Level/elite/stat snapshot survives vanilla NBT reload");
        close(helper, restored.getHealth(), wounded, "Reload cannot heal wounded members");
        close(helper, restored.getAttributeValue(Attributes.ATTACK_DAMAGE), damage, "Repeated joins do not stack modifiers");
        helper.assertTrue(restored.getAttribute(Attributes.ATTACK_DAMAGE).getModifier(otherId) != null, "Other modifiers survive restoration");
        restored.discard(); helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void legacyMembersDefaultToVanillaAndUnrelatedMobsStayUnmanaged(GameTestHelper helper) {
        ServerLevel level = helper.getLevel(); BlockPos center = helper.absolutePos(new BlockPos(52224, 0, 52224)); prepare(level, center);
        Zombie legacy = EntityType.ZOMBIE.create(level); position(legacy, center); legacy.setNoAi(true);
        double health = legacy.getMaxHealth(), damage = legacy.getAttributeValue(Attributes.ATTACK_DAMAGE);
        UUID party = UUID.randomUUID(); var roster = Set.of(legacy.getUUID());
        EncounterSavedData.get(level.getServer()).add(new HostileParty(party, Faction.UNDEAD, PartyType.UNDEAD_HORDE,
                new OriginRegion(level.dimension().location().toString(), center.getX(), center.getY(), center.getZ(), 8), null,
                PartyState.ALIVE, roster, roster, 2, 2, true, false));
        EncounterMember.attach(legacy, party, Faction.UNDEAD);
        legacy.setHealth(7); helper.assertTrue(level.addFreshEntity(legacy), "Pre-progression member remains valid");
        helper.assertTrue(EntityProgression.read(legacy).orElseThrow().equals(EntityProgression.Profile.legacy()), "Missing metadata defaults to level 1 with zero bonuses");
        close(helper, legacy.getMaxHealth(), health, "Legacy health base remains vanilla"); close(helper, legacy.getHealth(), 7, "Migration does not heal");
        close(helper, legacy.getAttributeValue(Attributes.ATTACK_DAMAGE), damage, "Legacy damage base remains vanilla");
        Zombie vanilla = EntityType.ZOMBIE.create(level); position(vanilla, center.offset(8, 0, 0)); vanilla.setNoAi(true); level.addFreshEntity(vanilla);
        helper.assertTrue(EntityProgression.read(vanilla).isEmpty(), "Vanilla mobs receive no levels automatically");
        legacy.discard(); vanilla.discard(); helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void bothEncounterFactionsUseSharedRegionalLevelsAndPartySnapshots(GameTestHelper helper) {
        ServerLevel level = helper.getLevel(); BlockPos center = helper.absolutePos(new BlockPos(53248, 0, 53248)); prepare(level, center);
        for (PartyType type : PartyType.values()) {
            BlockPos at = type == PartyType.PILLAGER_PATROL ? center : center.offset(0, 0, 16);
            var region = RegionalDifficultyService.at(level, at); var rules = ProgressionConfig.regionalRules();
            var result = EncounterSpawner.spawnAt(level, at, type, true, false);
            helper.assertTrue(result.successful(), "Both existing spawn paths remain functional");
            HostileParty party = result.party();
            var members = party.memberIds().stream().map(id -> (Mob) level.getEntity(id)).toList();
            var levels = members.stream().map(mob -> EntityProgression.read(mob).orElseThrow().level()).toList();
            helper.assertTrue(LevelSummary.of(levels).equals(party.levels()), "Party summary matches actual individual persistent levels");
            helper.assertTrue(levels.stream().allMatch(value -> value.value() >= Math.max(1, region.level().value() - rules.memberVariance())
                    && value.value() <= Math.min(rules.maximumLevel(), region.level().value() + rules.memberVariance())), "Every faction uses the same regional window");
            helper.assertTrue(levels.stream().distinct().count() > 1, "Member levels vary when the configured range permits");
            var reopened = EncounterSavedData.load(EncounterSavedData.get(level.getServer()).save(new CompoundTag(), level.registryAccess()), level.registryAccess());
            helper.assertTrue(reopened.get(party.id()).orElseThrow().levels().equals(party.levels()), "Party progression survives SavedData round trip");
            members.forEach(mob -> { mob.setNoAi(true); mob.discard(); });
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void equipmentRespectsFactionWeaponsCaptainBannerAndEliteCaps(GameTestHelper helper) {
        ServerLevel level = helper.getLevel(); BlockPos center = helper.absolutePos(new BlockPos(54272, 0, 54272)); prepare(level, center);
        Zombie zombie = EntityType.ZOMBIE.create(level); position(zombie, center); register(level, zombie, PartyType.UNDEAD_HORDE, 20, true);
        EquipmentProgression.apply(zombie, Faction.UNDEAD, new LevelValue(20));
        helper.assertTrue(zombie.getMainHandItem().is(Items.IRON_SWORD) && zombie.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), "High-level Undead melee gains ordinary gear");
        var elite = EntityProgression.read(zombie).orElseThrow(); helper.assertTrue(elite.elite() && elite.stats().healthBonus() <= .75 && elite.stats().damageBonus() <= .5, "Elite stats use the same conservative caps");
        Pillager captain = EntityType.PILLAGER.create(level); position(captain, center.offset(8, 0, 0));
        captain.setPatrolLeader(true); captain.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.WHITE_BANNER)); captain.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
        register(level, captain, PartyType.PILLAGER_PATROL, 20, false); EquipmentProgression.apply(captain, Faction.PILLAGER, new LevelValue(20));
        helper.assertTrue(captain.getMainHandItem().is(Items.CROSSBOW) && captain.getItemBySlot(EquipmentSlot.HEAD).is(Items.WHITE_BANNER)
                && captain.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), "Faction progression preserves Captain identity and ranged weapon");
        Skeleton skeleton = EntityType.SKELETON.create(level); position(skeleton, center.offset(-8, 0, 0)); skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        register(level, skeleton, PartyType.UNDEAD_HORDE, 20, false); EquipmentProgression.apply(skeleton, Faction.UNDEAD, new LevelValue(20));
        helper.assertTrue(skeleton.getMainHandItem().is(Items.BOW), "Undead ranged members retain native bows");
        zombie.discard(); captain.discard(); skeleton.discard(); helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void scaledArrowsHitNormallyAndCannotScaleTwiceAfterReload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel(); BlockPos center = helper.absolutePos(new BlockPos(55296, 0, 55296)); prepare(level, center);
        Pillager shooter = EntityType.PILLAGER.create(level); position(shooter, center); register(level, shooter, PartyType.PILLAGER_PATROL, 30, false);
        var arrow = EntityType.ARROW.create(level); arrow.setOwner(shooter); arrow.setBaseDamage(2);
        arrow.moveTo(center.getX() + 1, center.getY() + .4, center.getZ() + .5, 0, 0); arrow.setDeltaMovement(1, 0, 0);
        helper.assertTrue(level.addFreshEntity(arrow), "A managed ranged projectile spawns normally");
        double expected = 2 * (1 + EntityProgression.read(shooter).orElseThrow().stats().damageBonus());
        close(helper, arrow.getBaseDamage(), expected, "Ranged damage scales through actual arrow base damage");
        CompoundTag saved = arrow.saveWithoutId(new CompoundTag()); var restored = EntityType.ARROW.create(level); restored.load(saved);
        ProgressionEvents.onJoin(new EntityJoinLevelEvent(restored, level, true)); close(helper, restored.getBaseDamage(), expected, "Persisted projectile cannot multiply twice");
        var pig = EntityType.PIG.create(level); position(pig, center.offset(5, 0, 0)); pig.setNoAi(true); level.addFreshEntity(pig); float health = pig.getHealth();
        for (int i = 0; i < 12 && pig.getHealth() == health; i++) level.tickNonPassenger(arrow);
        helper.assertTrue(pig.getHealth() <= health - 3, "The scaled native arrow deals actual increased combat damage");
        shooter.discard(); pig.discard(); arrow.discard(); helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void vanillaUndeadConversionPreservesLevelEliteAndModifiers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel(); BlockPos center = helper.absolutePos(new BlockPos(56320, 0, 56320)); prepare(level, center);
        Zombie zombie = EntityType.ZOMBIE.create(level); position(zombie, center); HostileParty party = register(level, zombie, PartyType.UNDEAD_HORDE, 20, true);
        var profile = EntityProgression.read(zombie).orElseThrow(); CompoundTag nbt = zombie.saveWithoutId(new CompoundTag());
        nbt.putInt("DrownedConversionTime", 0); zombie.load(nbt); zombie.setNoAi(false); zombie.tick();
        UUID replacement = EncounterSavedData.get(level.getServer()).get(party.id()).orElseThrow().remainingMembers().iterator().next();
        var drowned = (Mob) level.getEntity(replacement);
        helper.assertTrue(drowned instanceof Drowned && EntityProgression.read(drowned).orElseThrow().equals(profile), "Vanilla conversion retains individual level/elite/stat snapshot");
        close(helper, drowned.getMaxHealth(), 20 * (1 + profile.stats().healthBonus()), "Converted entity receives scaled health once");
        helper.assertTrue(EncounterSavedData.get(level.getServer()).get(party.id()).orElseThrow().levels().equals(party.levels()), "Conversion keeps party progression snapshot");
        drowned.discard(); helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void progressionCommandsAreReadOnlyAndMutationRequiresServerThread(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel(); BlockPos center = helper.absolutePos(new BlockPos(57344, 0, 57344)); prepare(level, center);
        Zombie zombie = EntityType.ZOMBIE.create(level); position(zombie, center); register(level, zombie, PartyType.UNDEAD_HORDE, 10, false);
        var profile = EntityProgression.read(zombie).orElseThrow();
        boolean denied = CompletableFuture.supplyAsync(() -> {
            try { EntityProgression.restore(zombie); return false; } catch (IllegalStateException expected) { return true; }
        }).join(); helper.assertTrue(denied, "Off-thread mutation must be rejected before changing entity data");
        var dispatcher = level.getServer().getCommands().getDispatcher(); var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "ProgressionTest"));
        player.setPos(center.getX(), center.getY(), center.getZ()); var source = player.createCommandSourceStack();
        boolean permissionDenied = false;
        try { dispatcher.execute("kingdom progression info", source.withPermission(0)); } catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { permissionDenied = true; }
        helper.assertTrue(permissionDenied && dispatcher.execute("kingdom progression info", source.withPermission(2)) == 1
                && dispatcher.execute("kingdom progression info " + zombie.getUUID(), source.withPermission(2)) == 1,
                "Only operators can inspect regional or entity level through debug commands");
        helper.assertTrue(EntityProgression.read(zombie).orElseThrow().equals(profile), "Debug info cannot change or reroll a level");
        zombie.discard(); helper.succeed();
    }

    private static HostileParty register(ServerLevel level, Mob mob, PartyType type, int value, boolean elite) {
        UUID partyId = UUID.randomUUID(); var roster = Set.of(mob.getUUID());
        var party = new HostileParty(partyId, type.faction(), type, new OriginRegion(level.dimension().location().toString(), mob.getBlockX(), mob.getBlockY(), mob.getBlockZ(), 8),
                null, PartyState.ALIVE, roster, roster, 2, 2, true, false, LevelSummary.uniform(1, value));
        EncounterMember.attach(mob, partyId, type.faction()); var memberLevel = new LevelValue(value);
        EntityProgression.initializeSpawn(mob, new EntityProgression.Profile(memberLevel, elite, StatRules.defaults().scaling(memberLevel, elite)));
        EncounterSavedData.get(level.getServer()).add(party); mob.setNoAi(true); mob.setPersistenceRequired(); level.addFreshEntity(mob); return party;
    }
    private static void close(GameTestHelper helper, double actual, double expected, String message) { helper.assertTrue(Math.abs(actual - expected) < EPS, message + ": " + actual + " vs " + expected); }
    private static void position(Mob mob, BlockPos position) { mob.moveTo(position.getX() + .5, position.getY(), position.getZ() + .5, 0, 0); }
    private static void prepare(ServerLevel level, BlockPos center) {
        for (int x = (center.getX() - 24) >> 4; x <= (center.getX() + 24) >> 4; x++) for (int z = (center.getZ() - 24) >> 4; z <= (center.getZ() + 24) >> 4; z++) level.getChunk(x, z);
        for (int x = -24; x <= 24; x++) for (int z = -24; z <= 24; z++) {
            level.setBlock(center.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            for (int y = 0; y < 8; y++) level.setBlock(center.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
    }
}

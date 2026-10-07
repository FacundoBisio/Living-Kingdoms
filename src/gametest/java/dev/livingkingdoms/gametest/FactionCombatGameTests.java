package dev.livingkingdoms.gametest;

import com.mojang.authlib.GameProfile;
import dev.livingkingdoms.encounter.EncounterMember;
import dev.livingkingdoms.encounter.domain.HostileParty;
import dev.livingkingdoms.encounter.domain.OriginRegion;
import dev.livingkingdoms.encounter.domain.PartyState;
import dev.livingkingdoms.encounter.domain.PartyType;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import dev.livingkingdoms.faction.Faction;
import dev.livingkingdoms.faction.FactionCombat;
import dev.livingkingdoms.faction.FactionEntityResolver;
import dev.livingkingdoms.npc.NpcIdentity;
import dev.livingkingdoms.npc.NpcRole;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

/** Real goal selectors and vanilla attacks; remote test chunks are explicitly prepared only by these fixtures. */
@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FactionCombatGameTests {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void controlledPillagerAndUndeadAcquireEachOtherAndUseVanillaCombat(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(20000, 0, 20000));
        preparePlot(level, center);
        Pillager pillager = EntityType.PILLAGER.create(level);
        Zombie zombie = EntityType.ZOMBIE.create(level);
        position(pillager, center);
        position(zombie, center.offset(3, 0, 0));
        pillager.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        register(level, pillager, PartyType.PILLAGER_PATROL);
        register(level, zombie, PartyType.UNDEAD_HORDE);
        helper.assertTrue(level.addFreshEntity(pillager) && level.addFreshEntity(zombie), "Authoritative living members must join");
        for (int i = 0; i < 80; i++) {
            tickTargets(pillager);
            tickTargets(zombie);
        }
        helper.assertTrue(pillager.getTarget() == zombie && zombie.getTarget() == pillager,
                "Controlled Pillager and Undead factions must acquire each other without player aggression");

        // The remote fixture is not a player ticking region, so use the native level tick wrapper locally.
        // It increments tickCount before AI scheduling and emits NeoForge entity tick events.
        // Keep the attack within reach; native melee goal, attack cooldown, damage and death events remain intact.
        zombie.getNavigation().stop();
        position(zombie, pillager.blockPosition().offset(1, 0, 0));
        float initialHealth = pillager.getHealth();
        helper.onEachTick(() -> {
            if (zombie.isAlive() && pillager.isAlive()) {
                level.tickNonPassenger(zombie);
                if (pillager.getHealth() < initialHealth) {
                    pillager.discard();
                    zombie.discard();
                    helper.succeed();
                }
                if (helper.getTick() == 100) {
                    helper.assertTrue(pillager.getHealth() < initialHealth,
                            "Native zombie attack did not damage the faction target: " + describeAi(zombie)
                                    + ", targetHealth=" + pillager.getHealth() + ", distance=" + zombie.distanceTo(pillager));
                }
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void controlledPillagerUsesNativeCrossbowAgainstUndead(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(22048, 0, 22048));
        preparePlot(level, center);
        Set<ChunkPos> tickets = new HashSet<>();
        for (int x = (center.getX() - 20) >> 4; x <= (center.getX() + 20) >> 4; x++) {
            for (int z = (center.getZ() - 20) >> 4; z <= (center.getZ() + 20) >> 4; z++) {
                ChunkPos chunk = new ChunkPos(x, z);
                tickets.add(chunk);
                level.getChunkSource().addRegionTicket(TicketType.FORCED, chunk, 2, chunk);
            }
        }
        // Register the observer before ticking starts: GameTestInfo iterates its callback map directly.
        // Adding onEachTick from inside a delayed callback can resize that map while it is being iterated.
        Pillager pillager = EntityType.PILLAGER.create(level);
        Zombie zombie = EntityType.ZOMBIE.create(level);
        position(pillager, center);
        position(zombie, center.offset(5, 0, 0));
        pillager.setPatrolLeader(false);
        pillager.setPatrolTarget(center.offset(48, 0, 0));
        EventHooks.finalizeMobSpawn(pillager, level, level.getCurrentDifficultyAt(center), MobSpawnType.PATROL, null);
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        zombie.setNoAi(true);
        register(level, pillager, PartyType.PILLAGER_PATROL);
        register(level, zombie, PartyType.UNDEAD_HORDE);
        float initialHealth = zombie.getHealth();
        Set<UUID> firedArrows = new HashSet<>();
        helper.onEachTick(() -> {
            try {
                if (helper.getTick() < 40) return;
                if (helper.getTick() == 40) {
                    helper.assertTrue(level.isPositionEntityTicking(center), "Native crossbow fixture must be active");
                    helper.assertTrue(level.addFreshEntity(pillager) && level.addFreshEntity(zombie), "Both authoritative factions must join");
                    helper.assertTrue(pillager.getMainHandItem().is(Items.CROSSBOW), "Native patrol finalization must equip its crossbow");
                }
                var arrows = level.getEntitiesOfClass(AbstractArrow.class, pillager.getBoundingBox().inflate(20), Entity::isAlive);
                arrows.forEach(arrow -> firedArrows.add(arrow.getUUID()));
                // Observe normal ServerLevel AI, physics and arrow damage, without ticking them manually.
                if (zombie.getHealth() < initialHealth) {
                    helper.assertTrue(zombie.getLastHurtByMob() == pillager, "Native Pillager crossbow must cause the actual damage");
                    pillager.discard(); zombie.discard(); releaseTickets(level, tickets); helper.succeed();
                }
                if (helper.getTick() == 100) helper.assertTrue(pillager.getTarget() == zombie,
                        "Active native AI did not acquire Undead: " + describeCombatPair(level, pillager, zombie));
                if (helper.getTick() == 260) helper.assertTrue(zombie.getHealth() < initialHealth,
                        "Native crossbow did not damage Undead: " + describeCombatPair(level, pillager, zombie) + ", arrows=" + firedArrows.size());
            } catch (RuntimeException failure) {
                pillager.discard(); zombie.discard(); releaseTickets(level, tickets); throw failure;
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nativeFriendlyFireRetaliationCannotOverrideFactionAllies(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(22560, 0, 22560));
        preparePlot(level, center);
        Zombie attacker = EntityType.ZOMBIE.create(level);
        Zombie defender = EntityType.ZOMBIE.create(level);
        Pillager hostile = EntityType.PILLAGER.create(level);
        Zombie unrelated = EntityType.ZOMBIE.create(level);
        position(attacker, center);
        position(defender, center.offset(3, 0, 0));
        position(hostile, center.offset(40, 0, 0)); // Added later after the friendly retaliation check.
        position(unrelated, center.offset(-3, 0, 0));
        register(level, attacker, PartyType.UNDEAD_HORDE);
        register(level, defender, PartyType.UNDEAD_HORDE);
        helper.assertTrue(level.addFreshEntity(attacker) && level.addFreshEntity(defender) && level.addFreshEntity(unrelated), "Friendly and vanilla fixtures must join");
        defender.tickCount = 5; // HurtByTargetGoal's timestamp starts at zero.
        helper.assertTrue(defender.hurt(level.damageSources().mobAttack(attacker), 2), "Native friendly damage should be possible without a faction war");
        for (int i = 0; i < 100; i++) tickTargets(defender);
        helper.assertTrue(defender.getLastHurtByMob() == attacker && defender.getTarget() == null,
                "The real HurtByTargetGoal must not acquire a controlled same-faction attacker");
        defender.setTarget(attacker);
        helper.assertTrue(defender.getTarget() == null, "Every native target assignment must reject controlled allies");

        register(level, hostile, PartyType.PILLAGER_PATROL);
        position(hostile, center.offset(6, 0, 0));
        helper.assertTrue(level.addFreshEntity(hostile), "Hostile controlled fixture must join");
        defender.setTarget(hostile);
        defender.setTarget(attacker);
        helper.assertTrue(defender.getTarget() == hostile, "Refused allied target cannot replace an existing hostile target");
        defender.setTarget(unrelated);
        helper.assertTrue(defender.getTarget() == unrelated, "Unrelated vanilla targets retain their native behavior");
        FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "FactionTargetTest"));
        defender.setTarget(player);
        helper.assertTrue(defender.getTarget() == player, "Faction relationships do not replace player targeting policy");
        defender.setTarget(null);
        helper.assertTrue(defender.getTarget() == null, "Clearing targets must always remain possible");
        attacker.discard();
        defender.discard();
        hostile.discard();
        unrelated.discard();
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void factionTargetsExcludeVanillaMobsAndSameFactionMembers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(20512, 0, 20512));
        preparePlot(level, center);
        Pillager controlled = EntityType.PILLAGER.create(level);
        Pillager ally = EntityType.PILLAGER.create(level);
        Zombie vanillaZombie = EntityType.ZOMBIE.create(level);
        position(controlled, center);
        position(ally, center.offset(3, 0, 0));
        position(vanillaZombie, center.offset(-3, 0, 0));
        register(level, controlled, PartyType.PILLAGER_PATROL);
        register(level, ally, PartyType.PILLAGER_PATROL);
        int vanillaGoals = vanillaZombie.targetSelector.getAvailableGoals().size();
        helper.assertTrue(level.addFreshEntity(controlled) && level.addFreshEntity(ally) && level.addFreshEntity(vanillaZombie),
                "Living Kingdoms and unrelated vanilla entities must join normally");
        FactionCombat.install(vanillaZombie);
        helper.assertTrue(vanillaZombie.targetSelector.getAvailableGoals().size() == vanillaGoals
                && FactionEntityResolver.combatFaction(vanillaZombie).isEmpty(), "Unrelated vanilla mobs retain their exact target goal set");
        for (int i = 0; i < 120; i++) {
            tickTargets(controlled);
            tickTargets(ally);
            tickTargets(vanillaZombie);
        }
        helper.assertTrue(controlled.getTarget() == null && ally.getTarget() == null && vanillaZombie.getTarget() == null,
                "Faction targeting must neither select same-faction allies nor infer membership from vanilla species");
        controlled.discard();
        ally.discard();
        vanillaZombie.discard();
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void savedMemberJoinReinstallsAiOnceAndRejectsOrphanedMembers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(21024, 0, 21024));
        preparePlot(level, center);
        Pillager original = EntityType.PILLAGER.create(level);
        position(original, center);
        var party = register(level, original, PartyType.PILLAGER_PATROL);
        int vanillaGoals = original.targetSelector.getAvailableGoals().size();
        helper.assertTrue(level.addFreshEntity(original), "Valid member must join");
        helper.assertTrue(original.targetSelector.getAvailableGoals().size() == vanillaGoals + 1, "Join adds one faction goal");
        FactionCombat.install(original);
        helper.assertTrue(original.targetSelector.getAvailableGoals().size() == vanillaGoals + 1, "Repeated installation cannot duplicate targeting goals");
        CompoundTag saved = original.saveWithoutId(new CompoundTag());
        original.discard();
        Pillager restored = EntityType.PILLAGER.create(level);
        restored.load(saved);
        EntityJoinLevelEvent loadEvent = new EntityJoinLevelEvent(restored, level, true);
        FactionCombat.onJoin(loadEvent);
        helper.assertTrue(!loadEvent.isCanceled() && restored.targetSelector.getAvailableGoals().size() == vanillaGoals + 1
                && FactionEntityResolver.combatFaction(restored).orElseThrow() == Faction.PILLAGER,
                "Vanilla NBT reload must restore controlled membership and reinstall ephemeral faction AI");
        helper.assertTrue(level.addFreshEntity(restored), "Restored authoritative UUID must join");
        helper.assertTrue(restored.targetSelector.getAvailableGoals().size() == vanillaGoals + 1, "Actual join after loaded event remains idempotent");
        restored.discard();
        EncounterSavedData.get(level.getServer()).recordDeath(restored.getUUID());
        Pillager defeatedReload = EntityType.PILLAGER.create(level);
        defeatedReload.load(saved);
        helper.assertTrue(!level.addFreshEntity(defeatedReload), "An old saved entity cannot resurrect a defeated party member");

        Zombie orphan = EntityType.ZOMBIE.create(level);
        position(orphan, center.offset(6, 0, 0));
        EncounterMember.attach(orphan, UUID.randomUUID(), Faction.UNDEAD);
        helper.assertTrue(!level.addFreshEntity(orphan), "An expired or absent party cannot reload orphaned encounter entities");
        helper.assertTrue(EncounterSavedData.get(level.getServer()).get(party.id()).orElseThrow().remainingMembers().isEmpty(),
                "Refused joins must preserve the authoritative defeated state");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void taggedGuardsCanTargetHostilesButMayorsReceiveNoCombatAi(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(21536, 0, 21536));
        preparePlot(level, center);
        Settlement settlement = Settlement.founding(UUID.randomUUID(), new Territory(level.dimension().location().toString(),
                center.getX(), center.getY(), center.getZ(), 16), 5);
        SettlementSavedData.get(level.getServer()).add(settlement);
        var guard = EntityType.IRON_GOLEM.create(level);
        var mayor = EntityType.VILLAGER.create(level);
        Pillager hostile = EntityType.PILLAGER.create(level);
        position(guard, center);
        position(mayor, center.offset(-4, 0, 0));
        position(hostile, center.offset(4, 0, 0));
        NpcIdentity.attach(guard, settlement.id(), NpcRole.GUARD);
        NpcIdentity.attach(mayor, settlement.id(), NpcRole.MAYOR);
        mayor.setNoAi(true);
        mayor.setInvulnerable(true); // Match the existing Mayor's ordinary-combat protection.
        int mayorGoals = mayor.targetSelector.getAvailableGoals().size();
        register(level, hostile, PartyType.PILLAGER_PATROL);
        helper.assertTrue(level.addFreshEntity(guard) && level.addFreshEntity(mayor) && level.addFreshEntity(hostile), "Controlled identities must join");
        helper.assertTrue(FactionEntityResolver.combatFaction(guard).orElseThrow() == Faction.ALLIED_KINGDOM
                && FactionEntityResolver.combatFaction(mayor).isEmpty()
                && mayor.targetSelector.getAvailableGoals().size() == mayorGoals, "Only the tagged Guard role receives allied combat identity and AI");
        for (int i = 0; i < 100; i++) tickTargets(hostile);
        helper.assertTrue(hostile.getTarget() == guard, "Controlled Pillager must target an allied Guard");
        guard.discard();
        mayor.discard();
        hostile.discard();
        helper.succeed();
    }

    private static HostileParty register(ServerLevel level, Mob mob, PartyType type) {
        UUID id = UUID.randomUUID();
        Set<UUID> members = Set.of(mob.getUUID());
        HostileParty party = new HostileParty(id, type.faction(), type,
                new OriginRegion(level.dimension().location().toString(), mob.getBlockX(), mob.getBlockY(), mob.getBlockZ(), 8),
                null, PartyState.ALIVE, members, members, 2, 2, true, false);
        EncounterSavedData.get(level.getServer()).add(party);
        EncounterMember.attach(mob, id, type.faction());
        mob.setPersistenceRequired();
        return party;
    }

    private static void position(Mob mob, BlockPos position) {
        mob.moveTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5, 0, 0);
    }

    private static void tickTargets(Mob mob) {
        mob.tickCount++;
        mob.getSensing().tick();
        mob.targetSelector.tick();
    }

    private static String describeAi(Mob mob) {
        return "tickCount=" + mob.tickCount + ", target=" + (mob.getTarget() == null ? "none" : mob.getTarget().getUUID())
                + ", pos=" + mob.position() + ", health=" + mob.getHealth() + ", noAi=" + mob.isNoAi()
                + ", effectiveAi=" + mob.isEffectiveAi() + ", removed=" + mob.isRemoved()
                + ", faction=" + FactionEntityResolver.combatFaction(mob)
                + ", installedTargets=" + mob.targetSelector.getAvailableGoals().size()
                + ", runningTargets=" + mob.targetSelector.getAvailableGoals().stream().filter(goal -> goal.isRunning())
                .map(goal -> goal.getGoal().getClass().getSimpleName()).toList()
                + ", runningAttacks=" + mob.goalSelector.getAvailableGoals().stream().filter(goal -> goal.isRunning())
                .map(goal -> goal.getGoal().getClass().getSimpleName()).toList();
    }

    private static String describeCombatPair(ServerLevel level, Mob source, Mob target) {
        return describeAi(source) + ", targetDetails={" + describeAi(target) + "}"
                + ", distance=" + source.distanceTo(target) + ", lineOfSight=" + source.getSensing().hasLineOfSight(target)
                + ", qualifies=" + TargetingConditions.forCombat().range(FactionCombat.SEARCH_RADIUS).test(source, target)
                + ", nearby=" + level.getEntitiesOfClass(Mob.class, source.getBoundingBox().inflate(FactionCombat.SEARCH_RADIUS), Entity::isAlive)
                .stream().map(Entity::getUUID).toList();
    }

    private static void releaseTickets(ServerLevel level, Set<ChunkPos> tickets) {
        tickets.forEach(chunk -> level.getChunkSource().removeRegionTicket(TicketType.FORCED, chunk, 2, chunk));
        tickets.clear();
    }

    private static void preparePlot(ServerLevel level, BlockPos center) {
        for (int x = (center.getX() - 20) >> 4; x <= (center.getX() + 20) >> 4; x++) {
            for (int z = (center.getZ() - 20) >> 4; z <= (center.getZ() + 20) >> 4; z++) level.getChunk(x, z);
        }
        for (int x = -18; x <= 18; x++) {
            for (int z = -18; z <= 18; z++) {
                level.setBlock(center.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
                for (int y = 0; y < 5; y++) level.setBlock(center.offset(x, y, z), Blocks.AIR.defaultBlockState(), FLAGS);
            }
        }
    }
}

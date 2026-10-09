package dev.livingkingdoms.faction;

import dev.livingkingdoms.encounter.EncounterMember;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;

import java.util.EnumSet;

/** A bounded targeting goal on controlled, active mobs. Vanilla movement and attack goals do the fighting. */
public final class FactionCombat {
    public static final int SEARCH_INTERVAL_TICKS = 40;
    public static final double SEARCH_RADIUS = 16.0;

    private FactionCombat() {}

    /** Saved entity identities are present at join, including ordinary chunk reloads. */
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.isCanceled() && event.getLevel() instanceof ServerLevel && event.getEntity() instanceof Mob mob) {
            // Expired/dead rosters cannot be resurrected when an old entity chunk is loaded again.
            if (EncounterMember.read(mob).isPresent() && FactionEntityResolver.combatFaction(mob).isEmpty()) {
                event.setCanceled(true);
                return;
            }
            install(mob);
        }
    }

    /** Native retaliation and any future target goals use the same relationship authority. */
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof Mob mob)
                || !(mob.level() instanceof ServerLevel) || event.getNewAboutToBeSetTarget() == null) return;
        var ownFaction = FactionEntityResolver.combatFaction(mob);
        if (ownFaction.isEmpty()) return;
        var targetFaction = FactionEntityResolver.combatFaction(event.getNewAboutToBeSetTarget());
        if (targetFaction.isPresent() && !FactionRelations.isHostile(ownFaction.orElseThrow(), targetFaction.orElseThrow())) {
            // Cancellation keeps an existing valid target, and never interferes with clearing it.
            event.setCanceled(true);
        }
    }

    /** Also called after a newly spawned roster or converted member is committed to SavedData. */
    public static void install(Mob mob) {
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Faction AI must be installed on the server thread");
        if (mob instanceof net.minecraft.world.entity.npc.Villager v && dev.livingkingdoms.profession.GuardWork.assigned(v)) return;
        if (FactionEntityResolver.combatFaction(mob).isEmpty()) return;
        if (mob.targetSelector.getAvailableGoals().stream().anyMatch(goal -> goal.getGoal() instanceof HostileFactionTargetGoal)) return;
        // Retaliation (1) and ordinary player targets (2) keep their vanilla priorities.
        mob.targetSelector.addGoal(3, new HostileFactionTargetGoal(mob));
    }

    private static final class HostileFactionTargetGoal extends TargetGoal {
        private final TargetingConditions conditions;
        private LivingEntity candidate;
        private int nextSearchTick;

        private HostileFactionTargetGoal(Mob mob) {
            super(mob, true, false);
            setFlags(EnumSet.of(Goal.Flag.TARGET));
            conditions = TargetingConditions.forCombat().range(SEARCH_RADIUS);
            // Spread first searches so the members of a freshly loaded party do not all search at once.
            nextSearchTick = mob.tickCount + Math.floorMod(mob.getUUID().hashCode(), SEARCH_INTERVAL_TICKS);
        }

        @Override
        public boolean canUse() {
            if (mob.getTarget() != null && mob.getTarget().isAlive()) return false;
            if (mob.tickCount < nextSearchTick) return false;
            nextSearchTick = mob.tickCount + SEARCH_INTERVAL_TICKS;
            var ownFaction = FactionEntityResolver.combatFaction(mob);
            if (ownFaction.isEmpty()) return false;
            // Level's section-indexed query includes only entities already present in this loaded area.
            candidate = mob.level().getNearestEntity(mob.level().getEntitiesOfClass(Mob.class,
                            mob.getBoundingBox().inflate(SEARCH_RADIUS, 4.0, SEARCH_RADIUS),
                            other -> other != mob && other.isAlive()
                                    && FactionEntityResolver.combatFaction(other)
                                    .filter(faction -> FactionRelations.isHostile(ownFaction.orElseThrow(), faction)).isPresent()),
                    conditions, mob, mob.getX(), mob.getEyeY(), mob.getZ());
            return candidate != null;
        }

        @Override
        public void start() {
            targetMob = candidate;
            mob.setTarget(candidate);
            super.start();
        }

        @Override
        public boolean canContinueToUse() {
            var ownFaction = FactionEntityResolver.combatFaction(mob);
            LivingEntity target = mob.getTarget();
            return ownFaction.isPresent() && target != null && target.isAlive()
                    && FactionEntityResolver.combatFaction(target)
                    .filter(faction -> FactionRelations.isHostile(ownFaction.orElseThrow(), faction)).isPresent()
                    && super.canContinueToUse();
        }

        @Override
        protected double getFollowDistance() {
            return Math.min(SEARCH_RADIUS, super.getFollowDistance());
        }

        @Override
        public void stop() {
            candidate = null;
            super.stop();
        }
    }
}

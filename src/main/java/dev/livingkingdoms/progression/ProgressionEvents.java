package dev.livingkingdoms.progression;

import com.mojang.logging.LogUtils;
import dev.livingkingdoms.encounter.EncounterMember;
import dev.livingkingdoms.faction.FactionEntityResolver;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/** Spawn/reload hooks only; no progression tick loop or AI changes. */
public final class ProgressionEvents {
    private static final String PROJECTILE_SCALED = "livingkingdoms:progression_projectile";
    private ProgressionEvents() {}
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel)) return;
        if (event.getEntity() instanceof Mob mob && EncounterMember.read(mob).isPresent()
                && FactionEntityResolver.combatFaction(mob).filter(faction -> !faction.isAllied()).isPresent()) {
            try { EntityProgression.restore(mob); }
            catch (IllegalArgumentException malformed) {
                event.setCanceled(true);
                LogUtils.getLogger().error("Refusing managed mob {} with invalid progression data", mob.getUUID(), malformed);
            }
        }
        if (event.getEntity() instanceof AbstractArrow arrow && !arrow.getPersistentData().getBoolean(PROJECTILE_SCALED)
                && arrow.getOwner() instanceof Mob owner && EncounterMember.read(owner).isPresent()
                && FactionEntityResolver.combatFaction(owner).filter(faction -> !faction.isAllied()).isPresent()) {
            var profile = EntityProgression.read(owner);
            if (profile.isPresent()) {
                arrow.setBaseDamage(arrow.getBaseDamage() * (1 + profile.get().stats().damageBonus()));
                arrow.getPersistentData().putBoolean(PROJECTILE_SCALED, true);
            }
        }
    }
}

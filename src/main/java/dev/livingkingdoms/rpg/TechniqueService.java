package dev.livingkingdoms.rpg;

import dev.livingkingdoms.LivingKingdoms;
import dev.livingkingdoms.profession.domain.ProfessionType;
import dev.livingkingdoms.rpg.domain.Technique;
import dev.livingkingdoms.rpg.persistence.RpgSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Player techniques taught by Loyal residents. Attribute techniques are permanent player modifiers re-applied
 * on login and respawn; the others are checked where they matter (refinement cost, contract emeralds).
 */
public final class TechniqueService {
    private TechniqueService() {}

    public static boolean knows(ServerPlayer player, Technique technique) {
        return RpgSavedData.get(player.server).techniques(player.getUUID()).contains(technique);
    }

    /** Teaches the technique of {@code profession} once; returns true when newly learned. */
    public static boolean learnFrom(ServerPlayer player, ProfessionType profession, String teacher) {
        var technique = Technique.taughtBy(profession).orElse(null);
        if (technique == null || !RpgSavedData.get(player.server).learn(player.getUUID(), technique)) return false;
        apply(player);
        player.sendSystemMessage(Component.translatable("technique.livingkingdoms.learned", teacher,
                Component.translatable("technique.livingkingdoms." + technique.id())).withStyle(ChatFormatting.AQUA));
        player.sendSystemMessage(Component.translatable("technique.livingkingdoms." + technique.id() + ".effect").withStyle(ChatFormatting.GRAY));
        return true;
    }

    public static void apply(ServerPlayer player) {
        var known = RpgSavedData.get(player.server).techniques(player.getUUID());
        modifier(player, Attributes.MAX_HEALTH, "hearty_meals", known.contains(Technique.HEARTY_MEALS) ? 4 : 0, AttributeModifier.Operation.ADD_VALUE);
        modifier(player, Attributes.BLOCK_INTERACTION_RANGE, "masonry", known.contains(Technique.MASONRY) ? 1.5 : 0, AttributeModifier.Operation.ADD_VALUE);
        modifier(player, Attributes.ATTACK_DAMAGE, "drill", known.contains(Technique.DRILL) ? 1 : 0, AttributeModifier.Operation.ADD_VALUE);
        modifier(player, Attributes.BLOCK_BREAK_SPEED, "timber", known.contains(Technique.TIMBER) ? 0.15 : 0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        modifier(player, Attributes.LUCK, "prospector", known.contains(Technique.PROSPECTOR) ? 1.5 : 0, AttributeModifier.Operation.ADD_VALUE);
        modifier(player, Attributes.MOVEMENT_SPEED, "shortcuts", known.contains(Technique.SHORTCUTS) ? 0.05 : 0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
    }

    private static void modifier(ServerPlayer player, Holder<Attribute> attribute, String id, double amount, AttributeModifier.Operation operation) {
        var instance = player.getAttribute(attribute);
        if (instance == null) return;
        var key = ResourceLocation.fromNamespaceAndPath(LivingKingdoms.MOD_ID, "technique_" + id);
        if (amount == 0) { instance.removeModifier(key); return; }
        instance.addOrReplacePermanentModifier(new AttributeModifier(key, amount, operation));
    }

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) { if (event.getEntity() instanceof ServerPlayer player) apply(player); }
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) { if (event.getEntity() instanceof ServerPlayer player) apply(player); }
}

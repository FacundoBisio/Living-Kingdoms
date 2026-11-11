package dev.livingkingdoms.economy;

import dev.livingkingdoms.rpg.persistence.RpgSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.SettlementLifecycle;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.BuildingKind;
import dev.livingkingdoms.structure.SettlementTemplate;
import dev.livingkingdoms.ui.VillageNames;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/**
 * Once the Town Hall stands, the open founding camp becomes the Founders' Lodge: a roofed timber hall on the same
 * 9×9 plot, keeping the kingdom stone and the Quest Board exactly where they were (the layout anchor and ports
 * do not change). The lodge doubles as a small inn: it counts as a level-1 Tavern (travellers, hiring) and has its
 * own Building Ledger. Converted once per kingdom; recorded in the RPG data.
 */
public final class FoundersLodge {
    public static final ResourceLocation TEMPLATE = ResourceLocation.fromNamespaceAndPath("livingkingdoms", "allied/plains/upgrade/founders_lodge");
    private FoundersLodge() {}

    public static boolean converted(net.minecraft.server.MinecraftServer server, java.util.UUID settlement) { return RpgSavedData.get(server).lodge(settlement); }

    /** Converts the camp if the kingdom is established and still has its open camp. Returns true when it converted. */
    public static boolean convert(ServerLevel level, Settlement settlement) {
        var server = level.getServer();
        if (settlement.lifecycle() != SettlementLifecycle.ESTABLISHED || converted(server, settlement.id())
                || !settlement.territory().dimension().equals(level.dimension().location().toString())) return false;
        var layout = SettlementSavedData.get(server).layout(settlement.id()).orElse(null);
        var camp = layout == null ? null : layout.buildings().stream().filter(b -> b.kind() == BuildingKind.FOUNDING_CAMP).findFirst().orElse(null);
        if (camp == null) return false;
        var origin = camp.origin(); var far = origin.offset(8, 8, 8);
        if (!level.hasChunkAt(origin) || !level.hasChunkAt(far)) return false;
        var template = level.getStructureManager().get(TEMPLATE).orElse(null);
        if (template == null) return false;
        // Nobody gets stuck inside the new walls: residents standing in the camp step out of the south side.
        for (var e : level.getEntitiesOfClass(LivingEntity.class, new AABB(origin.getX(), origin.getY(), origin.getZ(), far.getX() + 1, far.getY() + 1, far.getZ() + 1)))
            if (!(e instanceof net.minecraft.server.level.ServerPlayer)) e.teleportTo(origin.getX() + 4.5, origin.getY() + 1, origin.getZ() + 10.5);
        template.placeInWorld(level, origin, origin, SettlementTemplate.settings(), level.random, 2 | 16);
        RpgSavedData.get(server).markLodge(settlement.id());
        var center = origin.offset(4, 3, 4);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, center.getX() + .5, center.getY(), center.getZ() + .5, 60, 3, 2, 3, 0);
        level.playSound(null, center, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.BLOCKS, 1F, 1F);
        var t = settlement.territory();
        for (var player : level.players()) if (t.contains(t.dimension(), player.getBlockX(), player.getBlockZ()))
            player.sendSystemMessage(Component.translatable("lodge.livingkingdoms.built", VillageNames.display(settlement)).withStyle(ChatFormatting.GOLD));
        return true;
    }
}

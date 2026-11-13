package dev.livingkingdoms.economy;

import dev.livingkingdoms.rpg.persistence.RpgSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.SettlementLifecycle;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.BuildingKind;
import dev.livingkingdoms.structure.SettlementLayoutMetadata;
import dev.livingkingdoms.structure.SettlementTemplate;
import dev.livingkingdoms.ui.VillageNames;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
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
        // Nobody gets stuck inside the new walls: residents and players in the camp step out onto verified ground.
        for (var e : level.getEntitiesOfClass(LivingEntity.class, new AABB(origin.getX(), origin.getY(), origin.getZ(), far.getX() + 1, far.getY() + 1, far.getZ() + 1)))
            moveToSafeGround(level, layout, origin, e);
        // The template wipes block entities without drops: anything stored in the camp is dropped outside first.
        var rescue = safeSpot(level, layout, origin);
        for (var pos : BlockPos.betweenClosed(origin, far)) if (level.getBlockEntity(pos) instanceof net.minecraft.world.Container c) {
            net.minecraft.world.Containers.dropContents(level, rescue, c); c.clearContent();
        }
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

    /**
     * Moves an entity out of the 9x9 camp footprint to the nearest standable spot (two free blocks, sturdy dry floor,
     * loaded chunk, outside every other building plot), searching rings 1-10 around the footprint without loading
     * chunks. Falls back to the old fixed doorstep point when nothing is found. Players keep their yaw and pitch.
     */
    public static void moveToSafeGround(ServerLevel level, SettlementLayoutMetadata layout, BlockPos origin, Entity entity) {
        var at = safeSpot(level, layout, origin);
        double x = at.getX() + .5, y = at.getY(), z = at.getZ() + .5;
        if (entity instanceof ServerPlayer player) player.teleportTo(level, x, y, z, player.getYRot(), player.getXRot());
        else entity.teleportTo(x, y, z);
    }

    static BlockPos safeSpot(ServerLevel level, SettlementLayoutMetadata layout, BlockPos origin) {
        int cx = origin.getX() + 4, cz = origin.getZ() + 4;
        for (int ring = 1; ring <= 10; ring++) for (int dx = -4 - ring; dx <= 4 + ring; dx++) for (int dz = -4 - ring; dz <= 4 + ring; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != 4 + ring) continue;
            for (int dy = -2; dy <= 2; dy++) {
                var feet = new BlockPos(cx + dx, origin.getY() + 1 + dy, cz + dz);
                if (standable(level, feet) && layout.buildings().stream().noneMatch(b -> b.kind() != BuildingKind.FOUNDING_CAMP && b.bounds().contains(feet.getX(), feet.getZ()))) return feet;
            }
        }
        return origin.offset(4, 1, 10);
    }

    private static boolean standable(ServerLevel level, BlockPos feet) {
        if (!level.hasChunkAt(feet) || !level.hasChunkAt(feet.below())) return false;
        var below = feet.below();
        return free(level, feet) && free(level, feet.above()) && level.getFluidState(below).isEmpty() && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    private static boolean free(ServerLevel level, BlockPos pos) { return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() && level.getFluidState(pos).isEmpty(); }
}

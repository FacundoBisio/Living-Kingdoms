package dev.livingkingdoms.settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;

import java.util.Collection;
import java.util.List;

/** Honors protection mods before committing any settlement, NPC or inventory state. */
public final class EstablishmentPlacementEvents {
    private EstablishmentPlacementEvents() {}
    public static List<BlockSnapshot> capture(ServerLevel level, Collection<BlockPos> positions) {
        return positions.stream().map(pos -> BlockSnapshot.create(level.dimension(),level,pos,18)).toList();
    }
    public static void validate(net.minecraft.world.entity.Entity player, List<BlockSnapshot> before) {
        var changed = before.stream().filter(snapshot -> !snapshot.getState().equals(snapshot.getCurrentState())).toList();
        boolean canceled = changed.size() > 1 ? EventHooks.onMultiBlockPlace(player,changed,Direction.UP)
                : changed.size() == 1 && EventHooks.onBlockPlace(player,changed.getFirst(),Direction.UP);
        if (canceled) throw new Rejected();
    }
    public static final class Rejected extends RuntimeException {}
}

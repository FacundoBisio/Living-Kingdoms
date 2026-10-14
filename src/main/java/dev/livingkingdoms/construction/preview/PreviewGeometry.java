package dev.livingkingdoms.construction.preview;

import dev.livingkingdoms.structure.SettlementLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import java.util.List;

/** Render-only budget; the authoritative plan always retains every original NBT cell. */
public final class PreviewGeometry {
    public static final int MAX_BLOCKS = 1024;
    public record Cell(BlockPos position, BlockState state) {}
    private PreviewGeometry() {}
    public static List<Cell> cells(SettlementLayout.Building building) {
        var visible = building.module().blocks().stream().filter(b -> !b.state().isAir()).toList();
        int stride = Math.max(1, (visible.size() + MAX_BLOCKS - 1) / MAX_BLOCKS);
        var result = new java.util.ArrayList<Cell>();
        for (int i = 0; i < visible.size(); i += stride) {
            var block = visible.get(i);
            result.add(new Cell(building.position(block.pos()), block.state().rotate(building.rotation())));
        }
        return List.copyOf(result);
    }
}

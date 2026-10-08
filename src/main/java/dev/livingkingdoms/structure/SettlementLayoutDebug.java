package dev.livingkingdoms.structure;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;

/** Operator-only, on-demand visual survey. No ticks, entities, writes or chunk loading. */
public final class SettlementLayoutDebug {
    private SettlementLayoutDebug() {}

    public static int show(CommandSourceStack source) throws CommandSyntaxException {
        var player=source.getPlayerOrException();var level=player.serverLevel();
        var data=SettlementSavedData.get(source.getServer());var pos=player.blockPosition();
        var found=data.at(level.dimension().location().toString(),pos.getX(),pos.getZ());
        if(found.isEmpty()) { source.sendFailure(Component.translatable("commands.livingkingdoms.settlement.not_found"));return 0; }
        var metadata=data.layout(found.get().id());
        if(metadata.isEmpty()) { source.sendFailure(Component.translatable("commands.livingkingdoms.settlement.debug.no_layout"));return 0; }
        var catalog=BuildingCatalog.load(level,metadata.get().style());
        // Bound diagnostic work even in settlements expanded through the existing API.
        var buildings=metadata.get().buildings().stream().sorted(java.util.Comparator.comparingDouble(b->b.origin().distSqr(pos))).limit(16).toList();
        for(var building:buildings) {
            var size=catalog.get(building.kind()).rotatedSize(building.rotation());
            BlockPos high=building.origin().offset(size).offset(-1,-1,-1);
            source.sendSuccess(()->Component.literal(building.kind()+" | anchor "+building.origin().toShortString()
                    +" | rotation "+building.rotation()+" | XYZ bounds "+building.origin().toShortString()+" → "+high.toShortString()
                    +" | entrance "+building.entrance().toShortString()),false);
            int[] xs={building.bounds().minX(),building.bounds().maxX()+1};
            int[] zs={building.bounds().minZ(),building.bounds().maxZ()+1};
            int[] ys={building.origin().getY(),high.getY()+1};
            for(int x:xs)for(int z:zs)for(int y:ys) {
                if(level.hasChunkAt(new BlockPos(x,y,z)))level.sendParticles(player,ParticleTypes.END_ROD,true,x,y,z,6,0.06,0.06,0.06,0);
            }
            var entrance=building.entrance();
            if(level.hasChunkAt(entrance))level.sendParticles(player,ParticleTypes.HAPPY_VILLAGER,true,entrance.getX()+0.5,entrance.getY()+1,entrance.getZ()+0.5,12,0.2,0.2,0.2,0);
        }
        return buildings.size();
    }
}

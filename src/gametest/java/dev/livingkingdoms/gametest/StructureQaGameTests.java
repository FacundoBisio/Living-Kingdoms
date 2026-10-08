package dev.livingkingdoms.gametest;

import dev.livingkingdoms.config.SettlementGenerationConfig.Tolerance;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StructureQaGameTests {
    @GameTest(template="empty",timeoutTicks=600)
    public static void everyModuleAtEveryRotationHasExactBoundsAndConnectedRoofs(GameTestHelper helper) {
        var level=helper.getLevel();
        var catalog=BuildingCatalog.load(level,ArchitectureStyle.PLAINS);
        BlockPos base=helper.absolutePos(new BlockPos(116000,1,116000));
        int index=0;
        for (BuildingKind kind:BuildingKind.values()) for (Rotation rotation:Rotation.values()) {
            var module=catalog.get(kind);
            BlockPos minimum=base.offset(index%8*22,0,index/8*22);index++;
            var size=module.rotatedSize(rotation);
            for(int x=-1;x<=size.getX();x++) for(int z=-1;z<=size.getZ();z++) {
                BlockPos at=minimum.offset(x,0,z);level.getChunkAt(at);
                for(int y=-3;y<0;y++)level.setBlock(at.above(y),Blocks.DIRT.defaultBlockState(),18);
                for(int y=0;y<=13;y++)level.setBlock(at.above(y),Blocks.AIR.defaultBlockState(),18);
            }
            var territory=new Territory(level.dimension().location().toString(),minimum.getX(),minimum.getY(),minimum.getZ(),32);
            var plan=new SettlementLayoutPlanner().planPlot(level,territory,module,minimum,rotation,new Tolerance(3,1,3),new GenerationDiagnostics()).orElseThrow();
            var building=plan.buildings().getFirst();
            plan.validateGeometry();
            helper.assertTrue(building.high().equals(building.origin().offset(size).offset(-1,-1,-1)),"Swapped rotated dimensions: "+kind+rotation);
            Set<BlockPos> solid=new HashSet<>();
            for(var info:module.blocks())if(!info.state().isAir())solid.add(building.position(info.pos()));
            assertConnected(helper,solid,building.origin(),kind+"/"+rotation);
            try(var transaction=SettlementPlacement.apply(level,plan)){transaction.commit();}
            // Independent native bounds and transformed exported states, including every roof course and decoration.
            for(var info:module.blocks()) {
                BlockPos pos=building.position(info.pos());
                helper.assertTrue(building.module().worldBounds(minimum,rotation).isInside(pos),"All blocks remain within native bounds");
                helper.assertTrue(level.getBlockState(pos).equals(info.state().rotate(rotation)),"Exact placed block: "+kind+rotation+pos);
            }
            // No writes in a one-block perimeter or above the advertised module.
            for(int x=-1;x<=size.getX();x++)for(int z=-1;z<=size.getZ();z++)for(int y=0;y<=13;y++) {
                BlockPos at=minimum.offset(x,y,z);
                if(!module.worldBounds(minimum,rotation).isInside(at))helper.assertTrue(level.getBlockState(at).isAir(),"No stray roof or duplicated plane at "+at);
            }
            if(kind!=BuildingKind.CORE && kind!=BuildingKind.WATCHTOWER) {
                BlockPos door=building.position(new BlockPos(module.size().getX()/2,1,module.size().getZ()-2));
                BlockPos threshold=building.position(new BlockPos(module.size().getX()/2,0,module.size().getZ()-1));
                helper.assertTrue(level.getBlockState(door).is(net.minecraft.tags.BlockTags.WOODEN_DOORS)
                        &&threshold.distManhattan(building.entrance())==1,"Actual door, reserved doorstep and path entrance are contiguous");
            }
        }
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=400)
    public static void overlapAndPathIntersectionsAreRejectedBeforeAnyWrites(GameTestHelper helper) {
        var level=helper.getLevel();BlockPos origin=helper.absolutePos(new BlockPos(117024,1,117024));
        var module=BuildingCatalog.load(level,ArchitectureStyle.PLAINS).get(BuildingKind.HOUSE_THIRD);
        var territory=new Territory(level.dimension().location().toString(),origin.getX(),origin.getY(),origin.getZ(),32);
        Map<BlockPos,BlockState> before=new HashMap<>();
        var size=module.rotatedSize(Rotation.CLOCKWISE_90);
        for(BlockPos mutable:BlockPos.betweenClosed(origin,origin.offset(size).offset(-1,-1,-1))) {
            BlockPos at=mutable.immutable();level.getChunkAt(at);level.setBlock(at,Blocks.AIR.defaultBlockState(),18);before.put(at,level.getBlockState(at));
        }
        var bounds=new PlotBounds(origin.getX(),origin.getZ(),origin.getX()+size.getX()-1,origin.getZ()+size.getZ()-1);
        var building=new SettlementLayout.Building(module,origin,Rotation.CLOCKWISE_90,bounds,module.worldPosition(module.entrance(),origin,Rotation.CLOCKWISE_90),List.of());
        var overlap=new SettlementLayout(territory,ArchitectureStyle.PLAINS,List.of(building,building),Map.of(),before);
        assertRefused(helper,overlap);
        // Air paths above a foundation still cut clearance, even when they miss walls.
        var path=new SettlementLayout(territory,ArchitectureStyle.PLAINS,List.of(building),Map.of(origin.above(2),Blocks.AIR.defaultBlockState()),before);
        assertRefused(helper,path);
        for(BlockPos pos:before.keySet())helper.assertTrue(level.getBlockState(pos).isAir(),"Invalid layout writes no foundation or roof");
        helper.succeed();
    }

    private static void assertRefused(GameTestHelper helper,SettlementLayout plan) {
        boolean refused=false;
        try(var transaction=SettlementPlacement.apply(helper.getLevel(),plan)){transaction.commit();}
        catch(IllegalStateException expected){refused=true;}
        helper.assertTrue(refused,"Malformed geometry refused before mutation");
    }

    private static void assertConnected(GameTestHelper helper,Set<BlockPos> solid,BlockPos floor,String label) {
        Set<BlockPos> visited=new HashSet<>();ArrayDeque<BlockPos> pending=new ArrayDeque<>();pending.add(floor);
        while(!pending.isEmpty()) {
            BlockPos at=pending.remove();if(!solid.contains(at)||!visited.add(at))continue;
            for(Direction direction:Direction.values())pending.add(at.relative(direction));
        }
        Set<BlockPos> detached=new HashSet<>(solid);detached.removeAll(visited);
        helper.assertTrue(detached.isEmpty(),"Detached blocks in "+label+": "+detached.stream().limit(8).toList());
    }
}

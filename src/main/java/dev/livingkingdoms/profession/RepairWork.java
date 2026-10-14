package dev.livingkingdoms.profession;

import dev.livingkingdoms.config.BuilderConfig;
import dev.livingkingdoms.economy.*;
import dev.livingkingdoms.profession.domain.*;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import java.util.*;

/** Explicit structural repairs only. Never overwrites player blocks, doors, beds or inventory/workstation blocks. */
public final class RepairWork {
    private record Cursor(UUID building,int index) {}
    private static final Map<Villager,Cursor> CURSORS=new WeakHashMap<>();
    private RepairWork() {}
    public static boolean hasOrders(ServerLevel level,UUID settlement) {return EconomySavedData.get(level.getServer()).account(settlement).map(a->!a.repairOrders().isEmpty()).orElse(false);}
    public static boolean tick(Villager builder,Profession p) {
        if(!(builder.level() instanceof ServerLevel level) || p.type()!=ProfessionType.BUILDER || !p.active()) return false;
        long now=KingdomEconomy.now(level.getServer());if(now<p.nextWorkAt() || builder.isNoAi() || builder.isSleeping() || builder.isLeashed() || builder.isPassenger()) return false;
        var economy=EconomySavedData.get(level.getServer());var account=economy.account(p.settlementId()).orElse(null);if(account==null || account.repairOrders().isEmpty()) return false;
        var jobs=ProfessionSavedData.get(level.getServer());var id=account.repairOrders().stream().sorted().findFirst().orElseThrow();
        var building=jobs.building(id).filter(b->b.active() && b.settlementId().equals(p.settlementId())).orElse(null);
        if(building==null) {economy.replace(p.settlementId(),account,account.repair(id,false));return false;}
        if(!WorkSites.loaded(level,building)) {jobs.replace(p,p.work(WorkState.UNLOADED,now+100,0,0));return false;}
        var resolved=WorkSites.resolve(level,building);if(resolved.isEmpty()) {jobs.replace(p,p.work(WorkState.BLOCKED,now+200,0,0));return false;}
        var point=building.entrance().above();
        if(builder.distanceToSqr(point.getX()+.5,point.getY(),point.getZ()+.5)>9) {
            builder.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET,new net.minecraft.world.entity.ai.memory.WalkTarget(point,.65F,1));
            boolean moved=WorkSites.move(builder,point);jobs.replace(p,p.work(moved?WorkState.REPAIRING:WorkState.BLOCKED,now+40,0,0));return false;
        }
        var module=resolved.orElseThrow();var cursor=CURSORS.get(builder);int index=cursor!=null && cursor.building().equals(id)?cursor.index():0;
        for(int checked=0;checked<32 && index<module.blocks().size();checked++,index++) {
            var block=module.blocks().get(index);var desired=block.state().rotate(building.rotation());
            if(!structural(desired)) continue;
            var pos=module.worldPosition(block.pos(),building.origin(),building.rotation());if(!WorkSites.loaded(level,pos) || !level.getBlockState(pos).isAir()) continue;
            CURSORS.put(builder,new Cursor(id,index));
            var material=desired.is(BlockTags.LOGS) || desired.is(BlockTags.PLANKS) || desired.is(BlockTags.WOODEN_STAIRS)
                    || desired.is(BlockTags.WOODEN_SLABS) || desired.is(BlockTags.WOODEN_FENCES)?StockResource.WOOD:StockResource.STONE;
            var transfer=KingdomEconomy.plan(level.getServer(),p.settlementId(),Map.of(material,1),Map.of(),false);
            if(transfer.isEmpty()) {jobs.replace(p,p.work(WorkState.MISSING_INPUTS,now+100,0,0));return false;}
            if(!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING) || !net.neoforged.neoforge.common.CommonHooks.canEntityDestroy(level,pos,builder)) {
                jobs.replace(p,p.work(WorkState.BLOCKED,now+200,0,0));return false;
            }
            var snapshot=BlockSnapshot.create(level.dimension(),level,pos);
            if(!level.setBlock(pos,desired,3)) {jobs.replace(p,p.work(WorkState.BLOCKED,now+100,0,0));return false;}
            // Vanilla first settles fence/stair connections. Event listeners must preserve that actual result.
            var placed=level.getBlockState(pos);
            var event=new BlockEvent.EntityPlaceEvent(snapshot,level.getBlockState(pos.below()),builder);NeoForge.EVENT_BUS.post(event);
            var scheduled=p.work(WorkState.REPAIRING,now+100,0,0);
            if(event.isCanceled() || !level.getBlockState(pos).equals(placed) || !KingdomEconomy.valid(level.getServer(),transfer.get()) || !jobs.replace(p,scheduled)) {
                snapshot.restore(3);jobs.replace(p,p.work(WorkState.BLOCKED,now+200,0,0));return false;
            }
            if(!KingdomEconomy.commit(level.getServer(),transfer.get())) throw new IllegalStateException("Repair changed during atomic commit");
            jobs.builderExperience(scheduled,1,BuilderConfig.LEVEL_CAP.get(),BuilderConfig.XP_STEP.get());builder.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            CURSORS.put(builder,new Cursor(id,index+1));return true;
        }
        CURSORS.put(builder,new Cursor(id,index));
        if(index>=module.blocks().size()) {
            account=economy.account(p.settlementId()).orElseThrow();economy.replace(p.settlementId(),account,account.repair(id,false));CURSORS.remove(builder);
        }
        jobs.replace(p,p.work(WorkState.REPAIRING,now+20,0,0));return false;
    }
    public static boolean structural(BlockState state) {
        return !state.hasBlockEntity() && (state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS) || state.is(BlockTags.STAIRS)
                || state.is(BlockTags.SLABS) || state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS) || state.is(BlockTags.STONE_BRICKS)
                || state.is(Blocks.COBBLESTONE) || state.is(Blocks.STONE) || state.is(Blocks.BRICKS));
    }
}

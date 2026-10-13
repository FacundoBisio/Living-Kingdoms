package dev.livingkingdoms.gametest;

import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.quest.domain.QuestState;
import dev.livingkingdoms.quest.expansion.domain.*;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.ui.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.*;
import java.util.List;
import java.util.UUID;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TerritoryHudGameTests {
    @GameTest(template="empty",timeoutTicks=100)
    public static void chunksCommandUsesRealCircleNearestAndPlayerPrivateOffWithoutLoading(GameTestHelper h) throws Exception {
        var f=fixture(h,620000); var level=h.getLevel(); var p=f.player();
        var other=new UiTestPlayer(level); other.setPos(p.getX(),p.getY(),p.getZ());
        var remote=new ChunkPos(f.center().east(512));
        boolean initiallyLoaded=level.getChunkSource().hasChunk(remote.x,remote.z);
        int count=SettlementSavedData.get(level.getServer()).settlements().size();
        var dispatcher=p.server.getCommands().getDispatcher(); var source=p.createCommandSourceStack().withPermission(0);
        h.assertTrue(dispatcher.execute("kingdom chunks",source)==1,"Ordinary player can inspect their own territory");
        var shown=p.territorySnapshots.getLast();
        h.assertTrue(shown.getUUID("settlement").equals(f.settlement().id()) && shown.getInt("x")==f.center().getX()
                && shown.getInt("radius")==f.settlement().territory().radius() && shown.getInt("duration")==TerritoryOverlayService.DURATION_TICKS,"Native payload reuses exact persisted circular territory");
        h.assertTrue(other.territorySnapshots.isEmpty(),"Visualization does not leak or enable other player overlays");
        p.setPos(f.center().getX()+512.5,f.center().getY(),f.center().getZ()+.5);
        h.assertTrue(dispatcher.execute("kingdom chunks",p.createCommandSourceStack().withPermission(0))==1,"Nearest indexed allied metadata works at bounded distance");
        h.assertTrue(dispatcher.execute("kingdom chunks off",p.createCommandSourceStack().withPermission(0))==1
                && p.territorySnapshots.getLast().isEmpty(),"Off sends one player-local clear");
        p.setPos(f.center().getX()+513.5,f.center().getY(),f.center().getZ()+.5);
        h.assertTrue(dispatcher.execute("kingdom chunks",p.createCommandSourceStack().withPermission(0))==0
                && p.territorySnapshots.getLast().isEmpty(),"Outside lookup limit clears any former visualization");
        h.assertTrue(level.getChunkSource().hasChunk(remote.x,remote.z)==initiallyLoaded
                && SettlementSavedData.get(level.getServer()).settlements().size()==count,"Command does not load chunks or create settlements");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=100)
    public static void hudReconnectAndRespawnResendPersonalPersistedTrackingWithoutDuplicateUpdates(GameTestHelper h) {
        var f=fixture(h,622048); var p=f.player(); var quests=QuestSavedData.get(p.server);
        var level=new LevelValue(1);
        var q=new QuestInstance(UUID.randomUUID(),QuestTemplate.IRON_REQUEST,new QuestSource(f.settlement().id(),null,QuestTemplate.IRON_REQUEST.sourceRole()),
                new QuestObjective.Resource(List.of(new ResourceRequirement(ResourceKind.IRON_INGOT,16))),level,QuestDifficulty.fromLevel(level),new QuestRewards(1,2),QuestState.AVAILABLE,false,0,Long.MAX_VALUE);
        h.assertTrue(quests.offer(p.getUUID(),q) && quests.acceptExpanded(p.getUUID(),q.id()),"Fixture creates tracked active task");
        h.assertTrue(KingdomHudService.update(p) && !KingdomHudService.update(p),"Unchanged snapshots are deduplicated");
        var tag=p.hudSnapshots.getLast();
        h.assertTrue(tag.getCompound("settlement").contains("reputation") && tag.getCompound("settlement").getString("safety").equals("safe")
                && tag.getCompound("quest").getString("settlement").equals(dev.livingkingdoms.ui.VillageNames.display(f.settlement())),
                "HUD receives real reputation/security and the linked reward destination");
        h.assertTrue(tag.getString("dimension").equals(h.getLevel().dimension().location().toString()) && tag.getCompound("quest").getUUID("id").equals(q.id()),"HUD binds persisted tracked identity to current dimension");
        KingdomHudService.onLogout(new PlayerEvent.PlayerLoggedOutEvent(p));
        KingdomHudService.onLogin(new PlayerEvent.PlayerLoggedInEvent(p));
        h.assertTrue(p.hudSnapshots.size()==2 && !KingdomHudService.update(p),"Reconnect forces fresh native snapshot then resumes dedup");
        KingdomHudService.onRespawn(new PlayerEvent.PlayerRespawnEvent(p,false));
        h.assertTrue(p.hudSnapshots.size()==3 && p.hudSnapshots.getLast().getCompound("quest").getUUID("id").equals(q.id()),"Respawn restores same tracked quest");
        KingdomHudService.onDimensionChange(new PlayerEvent.PlayerChangedDimensionEvent(p,h.getLevel().dimension(),h.getLevel().dimension()));
        h.assertTrue(p.hudSnapshots.size()==4,"Dimension event bypasses former snapshot cache");
        p.setPos(f.center().getX()+49.5,f.center().getY(),f.center().getZ()+.5);
        KingdomHudService.update(p);
        h.assertTrue(!p.hudSnapshots.getLast().contains("settlement") && p.hudSnapshots.getLast().contains("quest"),"Outside retains only tracked task");
        p.setPos(f.center().getX()+.5,f.center().getY(),f.center().getZ()+.5); KingdomHudService.update(p);
        h.assertTrue(p.hudSnapshots.getLast().contains("settlement") && p.hudSnapshots.getLast().contains("quest"),"Reentry restores settlement above task");
        var reloaded=QuestSavedData.load(quests.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(reloaded.tracked(p.getUUID()).orElseThrow().id().equals(q.id()),"Tracking survives save/reload");
        h.succeed();
    }
    private record Fixture(BlockPos center,Settlement settlement,UiTestPlayer player) {}
    private static Fixture fixture(GameTestHelper h,int coordinate) {
        var level=h.getLevel(); var center=h.absolutePos(new BlockPos(coordinate,1,coordinate));
        var settlement=Settlement.established(UUID.randomUUID(),new Territory(level.dimension().location().toString(),center.getX(),center.getY(),center.getZ(),48),0,SettlementOrigin.CONVERTED,UUID.randomUUID());
        SettlementSavedData.get(level.getServer()).add(settlement);
        var player=new UiTestPlayer(level); player.setPos(center.getX()+.5,center.getY(),center.getZ()+.5);
        return new Fixture(center,settlement,player);
    }
}

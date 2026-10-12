package dev.livingkingdoms.gametest;

import dev.livingkingdoms.config.DefenseConfig;
import dev.livingkingdoms.defense.*;
import dev.livingkingdoms.defense.domain.*;
import dev.livingkingdoms.encounter.domain.*;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.quest.domain.QuestState;
import dev.livingkingdoms.quest.expansion.domain.*;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.*;
import dev.livingkingdoms.ui.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ThreatApproachHudGameTests {
    private static final TicketType<ChunkPos> FIXTURE=TicketType.create("livingkingdoms_approach_test",Comparator.comparingLong(ChunkPos::toLong),2400);
    private record Fixture(BlockPos center,Settlement settlement,UiTestPlayer player) {}

    @GameTest(template="empty",timeoutTicks=1200)
    public static void outsidePartyWalksThroughDetectionToActiveWithoutTeleportOrChunkLoading(GameTestHelper h) {
        var f=fixture(h,540000); var level=h.getLevel();
        var event=ThreatApproachSpawner.spawn(level,f.settlement(),PartyType.PILLAGER_PATROL,false).orElseThrow();
        var party=EncounterSavedData.get(level.getServer()).get(event.partyId()).orElseThrow();
        h.assertTrue(event.state()==ThreatState.APPROACHING && DefenseService.status(level.getServer(),f.settlement().id())==SettlementSafety.SAFE,"Unknown approaching party does not falsely mark detected danger");
        var mobs=party.remainingMembers().stream().map(id -> (Mob)level.getEntity(id)).toList();
        for(var mob:mobs) {
            double d=Math.hypot(mob.getX()-f.center().getX(),mob.getZ()-f.center().getZ());
            h.assertTrue(d>=DefenseConfig.APPROACH_MIN_DISTANCE.get() && d<=DefenseConfig.APPROACH_MAX_DISTANCE.get(),"Every actual member starts in configured outside annulus");
            h.assertTrue(!f.settlement().territory().contains(event.dimension(),mob.blockPosition().getX(),mob.blockPosition().getZ()),"No member starts inside territory or its registered buildings");
            h.assertTrue(SettlementSavedData.get(level.getServer()).layout(f.settlement().id()).orElseThrow().buildings().stream().noneMatch(b -> b.bounds().contains(mob.blockPosition().getX(),mob.blockPosition().getZ())),"No registered building overlaps spawn");
        }
        var untouched=new ChunkPos(f.center().offset(4096,0,-4096));
        h.assertTrue(!level.getChunkSource().hasChunk(untouched.x,untouched.z),"Remote chunk starts unloaded");
        var initial=mobs.getFirst().position(); var previous=new net.minecraft.world.phys.Vec3[]{initial};
        h.onEachTick(() -> {
            var mob=mobs.getFirst();
            h.assertTrue(mob.position().distanceToSqr(previous[0])<9,"Native movement cannot teleport into town"); previous[0]=mob.position();
        });
        h.succeedWhen(() -> {
            var current=DefenseService.current(level.getServer(),f.settlement().id()).orElseThrow();
            h.assertTrue(current.state()==ThreatState.ACTIVE,"Real native navigation reaches defense area and activates");
            h.assertTrue(mobs.getFirst().position().distanceToSqr(initial)>100,"Party really walked from outside");
            h.assertTrue(!level.getChunkSource().hasChunk(untouched.x,untouched.z),"Approach never loads remote control chunk");
            mobs.forEach(Entity::discard);
        });
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void detectedAndActiveTargetBoundsRejectDistantPursuit(GameTestHelper h) {
        var f=fixture(h,541024); var level=h.getLevel();
        var event=ThreatApproachSpawner.spawn(level,f.settlement(),PartyType.PILLAGER_PATROL,false).orElseThrow();
        var mobs=EncounterSavedData.get(level.getServer()).get(event.partyId()).orElseThrow().remainingMembers().stream().map(id -> (Mob)level.getEntity(id)).toList();
        var mob=mobs.getFirst(); mob.setNoAi(true); mob.setPos(f.center().getX()+55.5,f.center().getY(),f.center().getZ()+.5);
        h.assertTrue(DefenseService.detectMember(level,mob).orElseThrow().state()==ThreatState.DETECTED,"Detection precedes combat arrival");
        mob.setPos(f.center().getX()+20.5,f.center().getY(),f.center().getZ()+.5);
        h.assertTrue(DefenseService.detectMember(level,mob).orElseThrow().state()==ThreatState.ACTIVE,"Arrival advances same event");
        var target=EntityType.VILLAGER.create(level); target.setPos(f.center().getX()+90.5,f.center().getY(),f.center().getZ()+.5); level.addFreshEntity(target);
        mob.setTarget(target); h.assertTrue(mob.getTarget()==null,"Native target event rejects pursuit beyond objective bounds");
        target.setPos(f.center().getX()+22.5,f.center().getY(),f.center().getZ()+.5); mob.setTarget(target);
        h.assertTrue(mob.getTarget()==target,"Combat with local citizen remains permitted");
        mobs.forEach(Entity::discard); target.discard(); h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=1200)
    public static void impossibleLoadedRouteFailsSafelyWithoutTeleportOrReward(GameTestHelper h) {
        var f=fixture(h,542048); var level=h.getLevel();
        var event=ThreatApproachSpawner.spawn(level,f.settlement(),PartyType.PILLAGER_PATROL,false).orElseThrow();
        var mobs=EncounterSavedData.get(level.getServer()).get(event.partyId()).orElseThrow().remainingMembers().stream().map(id -> (Mob)level.getEntity(id)).toList();
        var origin=EncounterSavedData.get(level.getServer()).get(event.partyId()).orElseThrow().origin();
        var cage=new BlockPos(origin.x(),f.center().getY(),origin.z());
        for(int x=-6;x<=6;x++) for(int z=-6;z<=6;z++) if(Math.abs(x)==6 || Math.abs(z)==6)
            for(int y=0;y<8;y++) level.setBlock(cage.offset(x,y,z),Blocks.BEDROCK.defaultBlockState(),18);
        h.succeedWhen(() -> {
            var result=DefenseService.latest(level.getServer(),f.settlement().id()).orElseThrow();
            h.assertTrue(result.state()==ThreatState.FAILED && result.outcome()==ThreatOutcome.ROUTE_BLOCKED,"Bounded failed path retries close event");
            h.assertTrue(result.participants().isEmpty() && mobs.stream().allMatch(Entity::isAlive),"Route failure invents neither deaths nor rewards");
            mobs.forEach(Entity::discard);
        });
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void unloadedApproachAnnulusRefusesSpawnAndPreservesRemoteChunks(GameTestHelper h) {
        var level=h.getLevel(); var center=h.absolutePos(new BlockPos(543072,1,543072));
        level.getChunkAt(center);
        var settlement=Settlement.established(UUID.randomUUID(),new Territory(level.dimension().location().toString(),center.getX(),center.getY(),center.getZ(),48),0,SettlementOrigin.CONVERTED,UUID.randomUUID());
        SettlementSavedData.get(level.getServer()).add(settlement);
        var remote=new ChunkPos(center.east(140));
        h.assertTrue(!level.getChunkSource().hasChunk(remote.x,remote.z),"Annulus begins unavailable");
        h.assertTrue(ThreatApproachSpawner.spawn(level,settlement,PartyType.UNDEAD_HORDE,false).isEmpty(),"No loaded safe origin means refusal");
        h.assertTrue(!level.getChunkSource().hasChunk(remote.x,remote.z) && DefenseService.current(level.getServer(),settlement.id()).isEmpty(),"No chunk loading or phantom event"); h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void hudInsideOutsideInventoryProgressAndDedupUseActualOutboundPayloads(GameTestHelper h) {
        var f=fixture(h,544096); var p=f.player(); var data=QuestSavedData.get(p.server); var q=offer(data,p,f.settlement().id()); data.acceptExpanded(p.getUUID(),q.id());
        h.assertTrue(KingdomHudService.update(p) && !KingdomHudService.update(p) && p.hudSnapshots.size()==1,"Unchanged HUD sends one native payload");
        var inside=p.hudSnapshots.getLast();
        h.assertTrue(inside.contains("settlement",10) && inside.getCompound("settlement").getString("name").equals(f.settlement().name()) && inside.getCompound("quest").getUUID("id").equals(q.id()),"Inside HUD combines local settlement and personal tracked quest");
        p.getInventory().items.set(0,new ItemStack(Items.IRON_INGOT,12));
        h.assertTrue(KingdomHudService.update(p) && p.hudSnapshots.getLast().getCompound("quest").getList("requirements",10).getCompound(0).getInt("count")==12,"Inventory objective is server sourced");
        p.setPos(f.center().getX()+60.5,f.center().getY(),f.center().getZ()+.5);
        h.assertTrue(KingdomHudService.update(p) && !p.hudSnapshots.getLast().contains("settlement") && p.hudSnapshots.getLast().contains("quest"),"Outside keeps quest only");
        data.untrack(p.getUUID(),q.id()); KingdomHudService.update(p); h.assertTrue(p.hudSnapshots.getLast().isEmpty() && !KingdomHudService.update(p),"Outside untracked sends one clear then nothing");
        data.track(p.getUUID(),q.id()); data.markObjective(p.getUUID(),q.id()); data.completeExpanded(p.getUUID(),q.id());
        h.assertTrue(KingdomHudService.snapshot(p).isEmpty(),"Completed quest clears HUD"); h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void remoteJournalTracksOwnQuestButCannotAcceptClaimOrUseAnotherPlayerSession(GameTestHelper h) {
        var f=fixture(h,545120); var p=f.player(); var b=new UiTestPlayer(h.getLevel()); var data=QuestSavedData.get(p.server);
        var a=offer(data,p,f.settlement().id()); var other=offer(data,b,f.settlement().id()); data.acceptExpanded(p.getUUID(),a.id()); data.acceptExpanded(b.getUUID(),other.id());
        var available=offer(data,p,UUID.randomUUID());
        p.setPos(f.center().getX()+90.5,f.center().getY(),f.center().getZ()+.5); QuestJournalService.open(p);
        var token=p.snapshots.getLast().getUUID("session");
        h.assertTrue(p.snapshots.getLast().getBoolean("remote"),"Journal opens while away");
        h.assertTrue(!VillageUiService.handle(p,new UiPayloads.Request(token,available.id(),UiPayloads.Action.ACCEPT)),"Remote acceptance refused");
        data.markObjective(p.getUUID(),a.id());
        h.assertTrue(!VillageUiService.handle(p,new UiPayloads.Request(token,a.id(),UiPayloads.Action.CLAIM)) && data.quest(p.getUUID(),a.id()).orElseThrow().state()==QuestState.ACTIVE,"Remote claim cannot manufacture reward");
        h.assertTrue(!VillageUiService.handle(b,new UiPayloads.Request(token,a.id(),UiPayloads.Action.TRACK)) && !VillageUiService.handle(p,new UiPayloads.Request(token,other.id(),UiPayloads.Action.TRACK)),"Cross-player session and quest access rejected");
        h.assertTrue(VillageUiService.handle(p,new UiPayloads.Request(token,a.id(),UiPayloads.Action.UNTRACK)) && VillageUiService.handle(p,new UiPayloads.Request(token,a.id(),UiPayloads.Action.TRACK)),"Own tracking remains allowed remotely");
        var reloaded=QuestSavedData.load(data.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(reloaded.tracked(p.getUUID()).orElseThrow().id().equals(a.id()) && reloaded.tracked(b.getUUID()).orElseThrow().id().equals(other.id()),"Players retain independent tracked choices after reload");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void kingdomShortcutUsesLocalContextAndDeniesOutOfTerritoryMutations(GameTestHelper h) {
        var f=fixture(h,546144); var p=f.player();
        h.assertTrue(VillageUiService.openShortcut(p) && p.snapshots.getLast().getBoolean("shortcut") && !p.snapshots.getLast().getBoolean("readonly"),"Kingdom shortcut opens management inside");
        p.setPos(f.center().getX()+55.5,f.center().getY(),f.center().getZ()+.5);
        h.assertTrue(VillageUiService.openShortcut(p) && p.snapshots.getLast().getBoolean("readonly"),"Near territory is information only");
        var token=p.snapshots.getLast().getUUID("session");
        h.assertTrue(!VillageUiService.handle(p,new UiPayloads.Request(token,UUID.randomUUID(),UiPayloads.Action.ACCEPT_CITIZEN)),"Nearby read-only session cannot mutate citizens");
        p.setPos(f.center().getX()+90.5,f.center().getY(),f.center().getZ()+.5);
        h.assertTrue(!VillageUiService.openShortcut(p),"Away shortcut refuses nonexistent local context"); h.succeed();
    }

    private static QuestInstance offer(QuestSavedData data,UiTestPlayer player,UUID settlement) {
        var level=new LevelValue(1); var q=new QuestInstance(UUID.randomUUID(),QuestTemplate.IRON_REQUEST,new QuestSource(settlement,null,QuestTemplate.IRON_REQUEST.sourceRole()),
                new QuestObjective.Resource(List.of(new ResourceRequirement(ResourceKind.IRON_INGOT,16))),level,QuestDifficulty.fromLevel(level),new QuestRewards(1,2),QuestState.AVAILABLE,false,0,Long.MAX_VALUE);
        if(!data.offer(player.getUUID(),q)) throw new IllegalStateException("Fixture quest refused"); return q;
    }
    private static Fixture fixture(GameTestHelper h,int coordinate) {
        var level=h.getLevel(); var center=h.absolutePos(new BlockPos(coordinate,1,coordinate));
        for(int x=(center.getX()-48)>>4;x<=(center.getX()+144)>>4;x++) for(int z=(center.getZ()-48)>>4;z<=(center.getZ()+48)>>4;z++) level.getChunk(x,z);
        var chunk=new ChunkPos(center.east(48)); level.getChunkSource().addRegionTicket(FIXTURE,chunk,9,chunk);
        for(int x=-32;x<=120;x++) for(int z=-32;z<=32;z++) {
            level.setBlock(center.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState(),18);
            for(int y=0;y<3;y++) level.setBlock(center.offset(x,y,z),Blocks.AIR.defaultBlockState(),18);
        }
        var settlement=Settlement.established(UUID.randomUUID(),new Territory(level.dimension().location().toString(),center.getX(),center.getY(),center.getZ(),48),0,SettlementOrigin.CONVERTED,UUID.randomUUID());
        var at=center.offset(12,-1,12); var house=new SettlementLayoutMetadata.Building(BuildingKind.HOUSE,ResourceLocation.parse("livingkingdoms:plains/house"),at,Rotation.NONE,new PlotBounds(at.getX(),at.getZ(),at.getX()+6,at.getZ()+6),at.above());
        SettlementSavedData.get(level.getServer()).add(settlement,new SettlementLayoutMetadata(ArchitectureStyle.PLAINS,List.of(house),List.of(center.east().below()),List.of()));
        var player=new UiTestPlayer(level) { @Override public boolean isFakePlayer() { return false; } }; player.getAbilities().mayBuild=true; player.setPos(center.getX()+.5,center.getY(),center.getZ()+.5);
        OnboardingTestSupport.legacyCollaborator(player,settlement);
        return new Fixture(center,settlement,player);
    }
}

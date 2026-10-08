package dev.livingkingdoms.gametest;

import dev.livingkingdoms.advancement.KingdomMilestone;
import dev.livingkingdoms.citizen.*;
import dev.livingkingdoms.citizen.domain.*;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.config.CitizenConfig;
import dev.livingkingdoms.construction.ConstructionService;
import dev.livingkingdoms.construction.persistence.ConstructionSavedData;
import dev.livingkingdoms.npc.NpcService;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.*;
import dev.livingkingdoms.ui.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
import java.util.function.Consumer;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ImmigrationGameTests {
    private static final TicketType<ChunkPos> FIXTURE=TicketType.create("livingkingdoms_immigration_test",Comparator.comparingLong(ChunkPos::toLong),600);

    @GameTest(template="empty",timeoutTicks=600)
    public static void sharedApprovalSpawnsOneCitizenAndAwardsPersistentAdvancement(GameTestHelper h) {
        var f=fixture(h,250000); var level=h.getLevel(); var server=level.getServer(); var data=CitizenSavedData.get(server);
        var candidate=ImmigrationService.attempt(level,f.settlement(),true).orElseThrow();
        int before=data.population(f.settlement().id());
        h.assertTrue(level.getEntitiesOfClass(Villager.class,new net.minecraft.world.phys.AABB(f.center()).inflate(48)).size()==before,"Proposal creates no final entity");
        var second=player(level,f.mayor().blockPosition().east(2));
        h.assertTrue(VillageUiService.openDialogue(f.player(),f.mayor()) && VillageUiService.openDialogue(second,f.mayor()),"Two players have independent Mayor sessions");
        var firstToken=f.player().snapshots.getLast().getUUID("session"); var secondToken=second.snapshots.getLast().getUUID("session");
        h.assertTrue(!VillageUiService.handle(f.player(),new UiPayloads.Request(firstToken,candidate.id(),UiPayloads.Action.ACCEPT_CITIZEN)),"Approval requires entering the immigration view");
        h.assertTrue(VillageUiService.handle(f.player(),new UiPayloads.Request(firstToken,new UUID(0,0),UiPayloads.Action.IMMIGRATION))
                && VillageUiService.handle(second,new UiPayloads.Request(secondToken,new UUID(0,0),UiPayloads.Action.IMMIGRATION)),"Both see shared candidates");
        h.assertTrue(!VillageUiService.handle(second,new UiPayloads.Request(firstToken,candidate.id(),UiPayloads.Action.ACCEPT_CITIZEN)),"Another player's nonce cannot approve");
        h.assertTrue(VillageUiService.handle(f.player(),new UiPayloads.Request(firstToken,candidate.id(),UiPayloads.Action.ACCEPT_CITIZEN)),"First approval succeeds");
        h.assertTrue(!VillageUiService.handle(second,new UiPayloads.Request(secondToken,candidate.id(),UiPayloads.Action.ACCEPT_CITIZEN))
                && !ImmigrationService.accept(f.player(),f.settlement().id(),candidate.id()),"Stale and replay approval cannot duplicate");
        h.assertTrue(data.population(f.settlement().id())==before+1 && data.candidates(f.settlement().id()).isEmpty(),"Shared population grows once");
        var citizen=data.citizen(candidate.id()).orElseThrow(); var entity=(Villager)level.getEntity(citizen.entityId());
        h.assertTrue(entity!=null && entity.getCustomName().getString().equals(candidate.name()) && citizen.homeId()!=null
                && entity.hasRestriction() && entity.isPersistenceRequired(),"Immigrant has persisted identity, real entity and assigned home");
        var reload=CitizenSavedData.load(data.save(new CompoundTag(),level.registryAccess()),level.registryAccess());
        h.assertTrue(reload.citizen(citizen.id()).orElseThrow().equals(citizen) && reload.population(f.settlement().id())==before+1
                && reload.summary(f.settlement().id()).equals(data.summary(f.settlement().id())),"Names, levels, home and population reload together");
        var advancement=server.getAdvancements().get(KingdomMilestone.FIRST_CITIZEN.id());
        h.assertTrue(advancement!=null && f.player().getAdvancements().getOrStartProgress(advancement).isDone()
                && !KingdomMilestone.awardFirstCitizen(f.player()) && !second.getAdvancements().getOrStartProgress(advancement).isDone(),"Only accepting player earns first citizen once");
        f.player().getAdvancements().save();
        var path=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("advancements").resolve(f.player().getUUID()+".json");
        var reopened=new net.minecraft.server.PlayerAdvancements(server.getFixerUpper(),server.getPlayerList(),server.getAdvancements(),path,f.player());
        h.assertTrue(reopened.getOrStartProgress(advancement).isDone() && !reopened.award(advancement,"accepted"),"Advancement receipt survives native file reopen"); reopened.stopListening();
        entity.die(level.damageSources().genericKill());
        h.assertTrue(data.byEntity(entity.getUUID()).orElseThrow().state()==CitizenState.DEAD && data.population(f.settlement().id())==before
                && data.summary(f.settlement().id()).free()==1,"Confirmed death keeps history and releases the home");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void canceledEntitySpawnRestoresCandidateAndHousing(GameTestHelper h) {
        var f=fixture(h,251024); var level=h.getLevel(); var data=CitizenSavedData.get(level.getServer());
        var candidate=ImmigrationService.attempt(level,f.settlement(),true).orElseThrow();
        int before=data.population(f.settlement().id()); var housing=data.summary(f.settlement().id());
        Consumer<EntityJoinLevelEvent> reject=event -> {
            if(event.getEntity().getPersistentData().hasUUID(CitizenService.CITIZEN_ID_KEY)
                    && event.getEntity().getPersistentData().getUUID(CitizenService.CITIZEN_ID_KEY).equals(candidate.id())) event.setCanceled(true);
        };
        NeoForge.EVENT_BUS.addListener(reject);
        try { h.assertTrue(!ImmigrationService.accept(f.player(),f.settlement().id(),candidate.id()),"Canceled spawn is rejected"); }
        finally { NeoForge.EVENT_BUS.unregister(reject); }
        h.assertTrue(data.candidate(candidate.id()).orElseThrow().equals(candidate) && data.population(f.settlement().id())==before
                && data.summary(f.settlement().id()).equals(housing) && data.citizen(candidate.id()).isEmpty(),"Failure rolls back entity identity, reservation and candidate");
        h.assertTrue(ImmigrationService.accept(f.player(),f.settlement().id(),candidate.id()),"Same candidate can succeed when spawn is safe"); h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void eligibilityCooldownDeclineAndExpirationRemainAuthoritative(GameTestHelper h) {
        var f=fixture(h,252048); var level=h.getLevel(); var server=level.getServer(); var data=CitizenSavedData.get(server);
        long now=ImmigrationService.now(server); data.schedule(f.settlement().id(),now+10000);
        h.assertTrue(ImmigrationService.attempt(level,f.settlement(),false).isEmpty(),"Persisted cooldown gates regular proposals");
        var candidate=ImmigrationService.attempt(level,f.settlement(),true).orElseThrow();
        var expired=new ImmigrationCandidate(UUID.randomUUID(),f.settlement().id(),"Alden Brook",new LevelValue(2),CitizenRole.UNASSIGNED,Math.max(0,now-1),now);
        data.addCandidate(expired); ImmigrationService.candidates(level,f.settlement());
        h.assertTrue(data.candidate(expired.id()).isEmpty() && !ImmigrationService.accept(f.player(),f.settlement().id(),expired.id()),"Expiration is enforced on server before UI approval");
        h.assertTrue(ImmigrationService.decline(f.player(),f.settlement().id(),candidate.id()) && !ImmigrationService.decline(f.player(),f.settlement().id(),candidate.id()),"Decline consumes one shared request");
        var changed=f.settlement().withLifecycle(SettlementLifecycle.FOUNDING); SettlementSavedData.get(server).replace(f.settlement(),changed);
        h.assertTrue(ImmigrationService.attempt(level,changed,true).isEmpty(),"Even debug cannot bypass founding lifecycle");
        SettlementSavedData.get(server).replace(changed,f.settlement());
        var fresh=ImmigrationService.attempt(level,f.settlement(),true).orElseThrow();
        var layout=SettlementSavedData.get(server).layout(f.settlement().id()).orElseThrow();
        int capacity=CitizenConfig.DEFAULT_HOUSE_CAPACITY.get();
        try {
            CitizenConfig.DEFAULT_HOUSE_CAPACITY.set(1); data.synchronizeHousing(f.settlement(),layout,CitizenConfig::capacity);
            h.assertTrue(data.summary(f.settlement().id()).free()==0 && !ImmigrationService.accept(f.player(),f.settlement().id(),fresh.id()),"Housing is checked again after candidate generation");
        } finally { CitizenConfig.DEFAULT_HOUSE_CAPACITY.set(capacity); }
        h.assertTrue(!ImmigrationService.decline(player(level,f.center().offset(100,0,0)),f.settlement().id(),fresh.id()),"Out of territory players cannot resolve requests"); h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void convertedVillageCanConstructRealHousingWithoutInventedVanillaCapacity(GameTestHelper h) {
        var f=fixture(h,253072,false); var level=h.getLevel(); var server=level.getServer(); var data=CitizenSavedData.get(server);
        h.assertTrue(data.summary(f.settlement().id()).total()==0 && ImmigrationService.attempt(level,f.settlement(),true).isEmpty(),"Vanilla village starts with no inferred beds/housing");
        h.assertTrue(ConstructionService.planHouse(level,f.settlement().id(),f.player()),"Player plans an actual LK house in safe converted wilderness space");
        var entry=ConstructionService.current(server,f.settlement().id()).orElseThrow(); var project=entry.project();
        var funded=project.supply(project.required()).start(ConstructionService.now(server));
        ConstructionSavedData.get(server).replace(project,funded);
        h.assertTrue(ConstructionService.resolve(server,project.id(),f.player(),true),"Existing construction places the real native house");
        h.assertTrue(data.summary(f.settlement().id()).total()==CitizenConfig.DEFAULT_HOUSE_CAPACITY.get()
                && SettlementSavedData.get(server).layout(f.settlement().id()).orElseThrow().buildings().stream().allMatch(b -> b.kind()==BuildingKind.HOUSE),"Only completed LK house counts; converted village has no fake core");
        var reopened=SettlementSavedData.load(SettlementSavedData.get(server).save(new CompoundTag(),level.registryAccess()),level.registryAccess());
        h.assertTrue(reopened.layout(f.settlement().id()).orElseThrow().equals(SettlementSavedData.get(server).layout(f.settlement().id()).orElseThrow()),"Converted plaza/house layout persists");
        h.assertTrue(ImmigrationService.attempt(level,f.settlement(),true).isPresent(),"Free actual house capacity unlocks immigration"); h.succeed();
    }

    private record Fixture(BlockPos center,Settlement settlement,UiTestPlayer player,Villager mayor) {}
    private static Fixture fixture(GameTestHelper h,int coordinate) { return fixture(h,coordinate,true); }
    private static Fixture fixture(GameTestHelper h,int coordinate,boolean house) {
        var level=h.getLevel(); var center=h.absolutePos(new BlockPos(coordinate,1,coordinate)); int radius=48;
        for(int x=(center.getX()-radius)>>4;x<=(center.getX()+radius)>>4;x++) for(int z=(center.getZ()-radius)>>4;z<=(center.getZ()+radius)>>4;z++) level.getChunk(x,z);
        var chunk=new ChunkPos(center); level.getChunkSource().addRegionTicket(FIXTURE,chunk,5,chunk);
        for(int x=-radius;x<=radius;x++) for(int z=-radius;z<=radius;z++) {
            level.setBlock(center.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState(),18);
            for(int y=0;y<13;y++) level.setBlock(center.offset(x,y,z),Blocks.AIR.defaultBlockState(),18);
        }
        var settlement=Settlement.established(UUID.randomUUID(),new Territory(level.dimension().location().toString(),center.getX(),center.getY(),center.getZ(),48),99,SettlementOrigin.CONVERTED,UUID.randomUUID());
        var settlements=SettlementSavedData.get(level.getServer()); settlements.add(settlement);
        level.setBlock(center,Blocks.LODESTONE.defaultBlockState(),18);
        if(house) {
            var origin=center.offset(12,-1,12);
            var building=new SettlementLayoutMetadata.Building(BuildingKind.HOUSE,net.minecraft.resources.ResourceLocation.parse("livingkingdoms:allied/plains/house"),origin,
                    net.minecraft.world.level.block.Rotation.NONE,new PlotBounds(origin.getX(),origin.getZ(),origin.getX()+6,origin.getZ()+6),origin.offset(3,1,7));
            settlements.updateLayout(settlement.id(),new SettlementLayoutMetadata(ArchitectureStyle.PLAINS,List.of(building),List.of(center.east().below()),List.of()));
        }
        var mayor=NpcService.ensureMayor(level,settlement).orElseThrow(); CitizenService.ensureInitialized(level,settlement);
        return new Fixture(center,settlement,player(level,mayor.blockPosition().east(2)),mayor);
    }
    private static UiTestPlayer player(ServerLevel level,BlockPos pos) {
        var player=new UiTestPlayer(level) { @Override public boolean isFakePlayer() { return false; } };
        player.getAbilities().mayBuild=true; player.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5); return player;
    }
}

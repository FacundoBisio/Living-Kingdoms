package dev.livingkingdoms.gametest;

import dev.livingkingdoms.advancement.KingdomMilestone;
import dev.livingkingdoms.citizen.*;
import dev.livingkingdoms.citizen.domain.*;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.construction.ConstructionService;
import dev.livingkingdoms.encounter.*;
import dev.livingkingdoms.encounter.domain.*;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import dev.livingkingdoms.faction.*;
import dev.livingkingdoms.npc.*;
import dev.livingkingdoms.profession.*;
import dev.livingkingdoms.profession.domain.*;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.*;
import dev.livingkingdoms.ui.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GuardGameTests {
    private static final TicketType<ChunkPos> FIXTURE=TicketType.create("livingkingdoms_guards_test",Comparator.comparingLong(ChunkPos::toLong),1200);
    private record Fixture(BlockPos center,Settlement s,UiTestPlayer player,Villager mayor) {}
    private record Worker(Citizen c,Villager v) {}

    @GameTest(template="empty",timeoutTicks=600)
    public static void nativeGuardDefeatsPillagerAndGuardOnlyVictoryNeverRewardsPlayer(GameTestHelper h) {
        var f=fixture(h,270000,true); var w=guard(h,f); var enemy=hostile(h,f,EntityType.PILLAGER,PartyType.PILLAGER_PATROL,10);
        enemy.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.CROSSBOW)); FactionCombat.install(enemy);
        var party=EncounterSavedData.get(h.getLevel().getServer()).forMember(enemy.getUUID()).orElseThrow();
        h.succeedWhen(() -> {
            h.assertTrue(w.v().isAlive(),"Basic Guard survives a local single patrol combatant");
            h.assertTrue(EncounterSavedData.get(h.getLevel().getServer()).get(party.id()).orElseThrow().state()==PartyState.DEFEATED,"Native navigation and melee defeat real Pillager");
            var p=ProfessionSavedData.get(h.getLevel().getServer()).profession(w.c().id()).orElseThrow();
            h.assertTrue(p.experience()>0,"Actual hostile damage grants profession XP");
            h.assertTrue(QuestSavedData.get(h.getLevel().getServer()).reputation(f.player().getUUID(),f.s().id())==0
                    && EncounterSavedData.get(h.getLevel().getServer()).eligibleParticipants(party.id(),h.getLevel().getGameTime(),1,1200).isEmpty(),"Guard victory has no passive player credit");
        });
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void nativeGuardDefeatsUndeadUsingSameFactionPolicy(GameTestHelper h) {
        var f=fixture(h,271024,true); var w=guard(h,f); var enemy=hostile(h,f,EntityType.ZOMBIE,PartyType.UNDEAD_HORDE,10);
        enemy.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.IRON_HELMET)); FactionCombat.install(enemy);
        var party=EncounterSavedData.get(h.getLevel().getServer()).forMember(enemy.getUUID()).orElseThrow();
        h.succeedWhen(() -> {
            h.assertTrue(w.v().isAlive(),"Guard remains alive");
            h.assertTrue(EncounterSavedData.get(h.getLevel().getServer()).get(party.id()).orElseThrow().state()==PartyState.DEFEATED,"Real Undead horde member defeated");
            h.assertTrue(ProfessionSavedData.get(h.getLevel().getServer()).profession(w.c().id()).orElseThrow().experience()>0,"Undead grants combat contribution XP");
        });
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void chaseStopsAtBoundaryAndNativeNavigationReturnsGuard(GameTestHelper h) {
        var f=fixture(h,272048,true); var w=guard(h,f); var enemy=hostile(h,f,EntityType.PILLAGER,PartyType.PILLAGER_PATROL,12); enemy.setNoAi(true);
        // Initial fixture starts outside the home boundary with an out-of-range target; no implementation teleporting.
        w.v().setPos(f.center().getX()+44.5,f.center().getY(),f.center().getZ()+.5); enemy.setPos(f.center().getX()+47.5,f.center().getY(),f.center().getZ()+.5); w.v().setTarget(enemy);
        h.succeedWhen(() -> {
            h.assertTrue(w.v().getTarget()==null,"Over-range enemy cleared");
            h.assertTrue(GuardPolicy.within(w.v().getX(),w.v().getZ(),f.center().getX()+.5,f.center().getZ()+.5,32),"Native path returns inside defense radius");
            h.assertTrue(enemy.getHealth()==enemy.getMaxHealth(),"Guard never attacks beyond chase limit");
        });
    }
    @GameTest(template="empty",timeoutTicks=160)
    public static void guardAvoidsAlliesNeutralAnimalsAndUnrelatedMobRewards(GameTestHelper h) {
        var f=fixture(h,273072,true); var w=guard(h,f); var level=h.getLevel();
        var ally=EntityType.ZOMBIE.create(level); ally.setPos(f.center().getX()+4.5,f.center().getY(),f.center().getZ()+.5); ally.setNoAi(true); ally.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.IRON_HELMET)); NpcIdentity.attach(ally,f.s().id(),NpcRole.GUARD); level.addFreshEntity(ally);
        var cow=EntityType.COW.create(level); cow.setPos(f.center().getX()+3.5,f.center().getY(),f.center().getZ()+.5); level.addFreshEntity(cow);
        h.assertTrue(!GuardWork.hostile(ally) && !GuardWork.hostile(cow) && !GuardWork.hostile(f.mayor()),"Metadata allies and unrelated animals are protected");
        w.v().setTarget(ally); h.assertTrue(w.v().getTarget()==null,"Central target event rejects allied target");
        cow.hurt(w.v().damageSources().mobAttack(w.v()),1000);
        h.runAfterDelay(80,() -> {
            h.assertTrue(ally.getHealth()==ally.getMaxHealth() && w.v().getTarget()!=ally,"Native guard never selects ally");
            h.assertTrue(ProfessionSavedData.get(level.getServer()).profession(w.c().id()).orElseThrow().experience()==0,"Unrelated kill grants no Guard XP");
            h.assertTrue(QuestSavedData.get(level.getServer()).reputation(f.player().getUUID(),f.s().id())==0,"Unrelated kill grants no player reputation"); ally.discard(); h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void sharedMayorGuardAssignmentCapacityReplayAndAdvancement(GameTestHelper h) throws Exception {
        var f=fixture(h,274096,true); var level=h.getLevel(); var workers=new ArrayList<Worker>(); for(int i=0;i<5;i++) workers.add(worker(h,f));
        var other=player(level,f.mayor().blockPosition().east(2));
        VillageUiService.openDialogue(f.player(),f.mayor()); VillageUiService.openDialogue(other,f.mayor());
        var a=f.player().snapshots.getLast().getUUID("session"); var b=other.snapshots.getLast().getUUID("session");
        VillageUiService.handle(f.player(),new UiPayloads.Request(a,new UUID(0,0),UiPayloads.Action.CITIZENS)); VillageUiService.handle(other,new UiPayloads.Request(b,new UUID(0,0),UiPayloads.Action.CITIZENS));
        h.assertTrue(!VillageUiService.handle(other,new UiPayloads.Request(a,workers.getFirst().c().id(),UiPayloads.Action.ASSIGN_GUARD)),"Other player's session is rejected");
        for(int i=0;i<4;i++) h.assertTrue(VillageUiService.handle(i%2==0?f.player():other,new UiPayloads.Request(i%2==0?a:b,workers.get(i).c().id(),UiPayloads.Action.ASSIGN_GUARD)),"Shared slot assigned once");
        h.assertTrue(!VillageUiService.handle(other,new UiPayloads.Request(b,workers.getFirst().c().id(),UiPayloads.Action.ASSIGN_GUARD)),"Stale duplicate assignment rejected");
        h.assertTrue(!VillageUiService.handle(other,new UiPayloads.Request(b,workers.get(4).c().id(),UiPayloads.Action.ASSIGN_GUARD)),"Full Barracks rejects fifth citizen");
        var adv=level.getServer().getAdvancements().get(KingdomMilestone.FIRST_GUARD.id());
        h.assertTrue(adv!=null && f.player().getAdvancements().getOrStartProgress(adv).isDone() && other.getAdvancements().getOrStartProgress(adv).isDone() && !KingdomMilestone.awardFirstGuard(f.player()),"Native milestone once per assigning player");
        h.assertTrue(VillageUiService.handle(f.player(),new UiPayloads.Request(a,workers.getFirst().c().id(),UiPayloads.Action.REMOVE_PROFESSION)),"Removal releases slot");
        h.assertTrue(workers.getFirst().v().getItemBySlot(EquipmentSlot.MAINHAND).isEmpty(),"Issued gear removed");
        VillageUiService.handle(other,new UiPayloads.Request(b,new UUID(0,0),UiPayloads.Action.REFRESH));
        h.assertTrue(VillageUiService.handle(other,new UiPayloads.Request(b,workers.getFirst().c().id(),UiPayloads.Action.ASSIGN_GUARD)),"Citizen reassigned by another player's newer view");
        h.assertTrue(!VillageUiService.handle(f.player(),new UiPayloads.Request(a,workers.getFirst().c().id(),UiPayloads.Action.REMOVE_PROFESSION)),"Stale employment generation cannot mutate reassigned job");
        h.assertTrue(VillageUiService.handle(other,new UiPayloads.Request(b,workers.getFirst().c().id(),UiPayloads.Action.REMOVE_PROFESSION)),"Fresh current view removes job safely");
        h.assertTrue(VillageUiService.handle(other,new UiPayloads.Request(b,workers.get(4).c().id(),UiPayloads.Action.ASSIGN_GUARD)),"Released slot reused across players");
        var dispatcher=level.getServer().getCommands().getDispatcher(); h.assertTrue(dispatcher.execute("kingdom settlement security",f.player().createCommandSourceStack().withPermission(2))==1,"Read-only security command");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void barracksConstructionUsesExistingResourcesTimeAndOnePlacement(GameTestHelper h) {
        var f=fixture(h,275120,false); var server=h.getLevel().getServer();
        VillageUiService.openDialogue(f.player(),f.mayor()); var token=f.player().snapshots.getLast().getUUID("session");
        VillageUiService.handle(f.player(),new UiPayloads.Request(token,new UUID(0,0),UiPayloads.Action.CONSTRUCTION));
        h.assertTrue(VillageUiService.handle(f.player(),new UiPayloads.Request(token,new UUID(0,0),UiPayloads.Action.PLAN_BARRACKS)),"Plan Barracks from ordinary UI");
        var project=ConstructionService.current(server,f.s().id()).orElseThrow().project(); h.assertTrue(project.building()==BuildingKind.BARRACKS && project.durationTicks()>0 && !project.required().isEmpty(),"Configurable material and time requirements");
        f.player().getInventory().items.set(0,new ItemStack(Items.OAK_LOG,64)); f.player().getInventory().items.set(1,new ItemStack(Items.STONE,64)); f.player().getInventory().items.set(2,new ItemStack(Items.IRON_INGOT,64));
        h.assertTrue(ConstructionService.deposit(f.player(),f.s().id(),project.id()),"Existing material delivery funds construction");
        h.assertTrue(!ConstructionService.resolve(server,project.id(),f.player()),"Native gameplay waits for build time");
        h.assertTrue(ConstructionService.resolve(server,project.id(),f.player(),true) && !ConstructionService.resolve(server,project.id(),f.player(),true),"One protected native placement");
        var b=barracks(f); h.assertTrue(b.workplaceSlots()==4 && b.capabilities().contains(BuildingCapability.GUARD_WORKPLACE),"Four functional shared Guard slots");
        h.assertTrue(!ConstructionService.planBarracks(h.getLevel(),f.s().id(),f.player()),"Existing Barracks blocks duplicate construction"); h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void guardReloadEquipmentXpAndUnloadedRemovalArePersistent(GameTestHelper h) {
        var f=fixture(h,276144,true); var w=guard(h,f); var level=h.getLevel(); var d=ProfessionSavedData.get(level.getServer()); var p=d.profession(w.c().id()).orElseThrow();
        h.assertTrue(d.guardExperience(p,200,5,40),"Meaningful combat receipt promotes test Guard"); GuardWork.control(w.v());
        var hand=w.v().getItemBySlot(EquipmentSlot.MAINHAND).copy(); var armor=w.v().getItemBySlot(EquipmentSlot.CHEST).copy(); var tag=w.v().saveWithoutId(new CompoundTag()); var health=w.v().getHealth();
        var profiles=ProfessionSavedData.load(d.save(new CompoundTag(),level.registryAccess()),level.registryAccess());
        h.assertTrue(profiles.profession(w.c().id()).equals(d.profession(w.c().id())) && profiles.workers(barracks(f).id())==1,"Profession and capacity persist");
        w.v().discard(); var restored=EntityType.VILLAGER.create(level); restored.load(tag); level.addFreshEntity(restored);
        h.assertTrue(GuardWork.assigned(restored) && ItemStack.isSameItemSameComponents(hand,restored.getItemBySlot(EquipmentSlot.MAINHAND))
                && ItemStack.isSameItemSameComponents(armor,restored.getItemBySlot(EquipmentSlot.CHEST)) && restored.getHealth()==health,"Same entity UUID, gear, level modifiers and health survive join without healing");
        var item=new ItemEntity(level,f.center().getX(),f.center().getY(),f.center().getZ(),hand.copy()); h.assertTrue(!level.addFreshEntity(item),"Bound service gear cannot become transferable drops");
        restored.discard(); h.assertTrue(ProfessionService.remove(f.player(),f.s().id(),w.c().id()),"Unloaded assignment removal releases metadata slot");
        var retired=EntityType.VILLAGER.create(level); retired.load(tag); level.addFreshEntity(retired); h.assertTrue(!GuardWork.assigned(retired) && retired.getItemBySlot(EquipmentSlot.MAINHAND).isEmpty(),"Old entity NBT cannot restore removed job or issued equipment"); h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void guardDeathFreesHousingBarracksAndSecurityWithoutRespawn(GameTestHelper h) {
        var f=fixture(h,277168,true); var w=guard(h,f); var server=h.getLevel().getServer(); var before=CitizenSavedData.get(server).summary(f.s().id()); int score=SecurityService.score(server,f.s().id());
        w.v().hurt(h.getLevel().damageSources().genericKill(),1000);
        h.assertTrue(CitizenSavedData.get(server).citizen(w.c().id()).orElseThrow().state()==CitizenState.DEAD && ProfessionSavedData.get(server).workers(barracks(f).id())==0,"Confirmed death frees shared workplace");
        h.assertTrue(CitizenSavedData.get(server).summary(f.s().id()).free()>before.free() && SecurityService.score(server,f.s().id())<score,"Home capacity and derived security update");
        ProfessionService.ensure(h.getLevel(),f.s()); h.assertTrue(!ProfessionService.assignGuard(f.player(),f.s().id(),w.c().id()) && !ProfessionSavedData.get(server).profession(w.c().id()).orElseThrow().active(),"No automatic replacement or historical reactivation"); h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void playerContributionSurvivesGuardFinishAndBudgetPreventsHealedMobXpFarm(GameTestHelper h) {
        var f=fixture(h,278192,true); var w=guard(h,f); w.v().setNoAi(true); var enemy=hostile(h,f,EntityType.PILLAGER,PartyType.PILLAGER_PATROL,10); enemy.setNoAi(true); var level=h.getLevel();
        var party=EncounterSavedData.get(level.getServer()).forMember(enemy.getUUID()).orElseThrow(); var source=w.v().damageSources().mobAttack(w.v());
        enemy.hurt(f.player().damageSources().playerAttack(f.player()),4); enemy.invulnerableTime=0;
        for(int i=0;i<12;i++) { enemy.hurt(source,4); enemy.setHealth(enemy.getMaxHealth()); enemy.invulnerableTime=0; }
        var d=ProfessionSavedData.get(level.getServer()); long capped=d.profession(w.c().id()).orElseThrow().experience(); h.assertTrue(capped>0 && capped<=12,"Finite per-victim contribution budget grants no XP after repeated heals");
        var saved=enemy.saveWithoutId(new CompoundTag()); enemy.discard(); var reloaded=EntityType.PILLAGER.create(level); reloaded.load(saved); level.addFreshEntity(reloaded); reloaded.invulnerableTime=0; reloaded.hurt(source,1000);
        h.assertTrue(d.profession(w.c().id()).orElseThrow().experience()==capped,"Victim budget persists through entity reload and lethal overkill");
        h.assertTrue(EncounterSavedData.get(level.getServer()).get(party.id()).orElseThrow().state()==PartyState.DEFEATED
                && QuestSavedData.get(level.getServer()).reputation(f.player().getUUID(),f.s().id())==2,"Meaningful player participation receives one local reward when Guard finishes");
        var wounded=hostile(h,f,EntityType.PILLAGER,PartyType.PILLAGER_PATROL,8); wounded.setNoAi(true);
        wounded.hurt(f.player().damageSources().playerAttack(f.player()),wounded.getMaxHealth()-1); wounded.invulnerableTime=0; wounded.hurt(source,1000);
        h.assertTrue(d.profession(w.c().id()).orElseThrow().experience()==capped,"One remaining health point cannot award full-bar XP for lethal overkill or kill stealing");
        h.assertTrue(SecurityService.immigrationModifier(level.getServer(),f.s().id())>0 && SecurityService.lowSecurity(level.getServer(),f.s().id()),"Security modifier never blocks housing-eligible immigration; low security quest hook exposed"); h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void unguardedSettlementReceivesLocalEncounterAlertWithoutSpam(GameTestHelper h) {
        var f=fixture(h,279216,false); var level=h.getLevel(); var messages=new ArrayList<net.minecraft.network.chat.Component>();
        var observer=new UiTestPlayer(level) {
            @Override public void displayClientMessage(net.minecraft.network.chat.Component message,boolean overlay) { messages.add(message); }
        };
        observer.setPos(f.center().getX()+.5,f.center().getY(),f.center().getZ()+.5); level.addNewPlayer(observer);
        var enemy=hostile(h,f,EntityType.PILLAGER,PartyType.PILLAGER_PATROL,8); enemy.setNoAi(true);
        h.runAfterDelay(100,() -> {
            try {
                h.assertTrue(SecurityService.score(level.getServer(),f.s().id())==0,"No Guard or military building required for threat detection");
                h.assertTrue(messages.size()==1 && messages.getFirst().getString().contains(f.s().name()),"Entity-local periodic encounter event alerts nearby player");
                SecurityService.alert(level,f.s()); SecurityService.alert(level,f.s()); h.assertTrue(messages.size()==1,"Shared settlement cooldown suppresses repeated alerts"); h.succeed();
            } finally { enemy.discard(); level.removePlayerImmediately(observer,Entity.RemovalReason.DISCARDED); }
        });
    }

    private static Mob hostile(GameTestHelper h,Fixture f,EntityType<? extends Mob> type,PartyType partyType,int x) {
        var level=h.getLevel(); var m=type.create(level); m.setPos(f.center().getX()+x+.5,f.center().getY(),f.center().getZ()+.5); m.setPersistenceRequired();
        var ids=Set.of(m.getUUID()); var party=new HostileParty(UUID.randomUUID(),partyType.faction(),partyType,new OriginRegion(level.dimension().location().toString(),f.center().getX(),f.center().getY(),f.center().getZ(),8),f.s().id(),PartyState.ALIVE,ids,ids,1,2,false,true);
        EncounterSavedData.get(level.getServer()).add(party,level.getServer().overworld().getGameTime()); EncounterMember.attach(m,party.id(),party.faction()); level.addFreshEntity(m); return m;
    }
    private static FunctionalBuilding barracks(Fixture f) { return ProfessionSavedData.get(f.player().server).buildings(f.s().id()).stream().filter(b -> b.kind()==BuildingKind.BARRACKS).findFirst().orElseThrow(); }
    private static Worker guard(GameTestHelper h,Fixture f) { var w=worker(h,f); h.assertTrue(ProfessionService.assignGuard(f.player(),f.s().id(),w.c().id()),"Guard assigned via real authority"); w.v().setPos(f.center().getX()+2.5,f.center().getY(),f.center().getZ()+.5); return w; }
    private static Worker worker(GameTestHelper h,Fixture f) { var candidate=ImmigrationService.attempt(h.getLevel(),f.s(),true).orElseThrow(); h.assertTrue(ImmigrationService.accept(f.player(),f.s().id(),candidate.id()),"Immigrant accepted into free home"); var c=CitizenSavedData.get(h.getLevel().getServer()).citizen(candidate.id()).orElseThrow(); ProfessionService.ensure(h.getLevel(),f.s()); return new Worker(c,(Villager)h.getLevel().getEntity(c.entityId())); }
    private static UiTestPlayer player(ServerLevel level,BlockPos pos) { var p=new UiTestPlayer(level) { @Override public boolean isFakePlayer() { return false; } }; p.getAbilities().mayBuild=true; p.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5); return p; }
    private static Fixture fixture(GameTestHelper h,int coordinate,boolean barracks) {
        var level=h.getLevel(); var center=h.absolutePos(new BlockPos(coordinate,1,coordinate)); int radius=48;
        for(int x=(center.getX()-radius)>>4;x<=(center.getX()+radius)>>4;x++) for(int z=(center.getZ()-radius)>>4;z<=(center.getZ()+radius)>>4;z++) level.getChunk(x,z);
        var chunk=new ChunkPos(center); level.getChunkSource().addRegionTicket(FIXTURE,chunk,5,chunk);
        for(int x=-radius;x<=radius;x++) for(int z=-radius;z<=radius;z++) { level.setBlock(center.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState(),18); for(int y=0;y<13;y++) level.setBlock(center.offset(x,y,z),Blocks.AIR.defaultBlockState(),18); }
        var s=Settlement.established(UUID.randomUUID(),new Territory(level.dimension().location().toString(),center.getX(),center.getY(),center.getZ(),radius),1,SettlementOrigin.CONVERTED,UUID.randomUUID());
        var buildings=new ArrayList<SettlementLayoutMetadata.Building>(); var catalog=BuildingCatalog.load(level,ArchitectureStyle.PLAINS);
        for(int i=0;i<(barracks?4:2);i++) {
            var m=catalog.get(i==3?BuildingKind.BARRACKS:BuildingKind.HOUSE); var at=center.offset(i>=2?-18:12,-1,i%2==1?-16:12);
            m.template().placeInWorld(level,at,at,SettlementTemplate.settings(),level.random,18); buildings.add(new SettlementLayoutMetadata.Building(m.kind(),m.id(),at,Rotation.NONE,new PlotBounds(at.getX(),at.getZ(),at.getX()+m.size().getX()-1,at.getZ()+m.size().getZ()-1),at.offset(m.entrance())));
        }
        SettlementSavedData.get(level.getServer()).add(s,new SettlementLayoutMetadata(ArchitectureStyle.PLAINS,buildings,List.of(center.east().below()),List.of())); level.setBlock(center,Blocks.LODESTONE.defaultBlockState(),18);
        var mayor=NpcService.ensureMayor(level,s).orElseThrow(); ProfessionService.ensure(level,s); return new Fixture(center,s,player(level,mayor.blockPosition().east(2)),mayor);
    }
}

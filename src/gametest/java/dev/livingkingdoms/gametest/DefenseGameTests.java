package dev.livingkingdoms.gametest;

import dev.livingkingdoms.advancement.KingdomMilestone;
import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.citizen.ImmigrationService;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.defense.*;
import dev.livingkingdoms.defense.domain.*;
import dev.livingkingdoms.defense.persistence.DefenseSavedData;
import dev.livingkingdoms.encounter.*;
import dev.livingkingdoms.encounter.domain.*;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import dev.livingkingdoms.faction.FactionCombat;
import dev.livingkingdoms.npc.NpcService;
import dev.livingkingdoms.profession.ProfessionService;
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
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DefenseGameTests {
    private static final TicketType<ChunkPos> FIXTURE=TicketType.create("livingkingdoms_defense_test",Comparator.comparingLong(ChunkPos::toLong),1200);
    private record Fixture(BlockPos center,Settlement settlement,UiTestPlayer player,Villager mayor,BlockPos board) {}
    private record Party(HostileParty data,List<Mob> mobs) {}

    @GameTest(template="empty",timeoutTicks=600)
    public static void guardOnlyPillagerVictoryResolvesAcceptedDefenseWithoutPersonalReward(GameTestHelper h) {
        var f=fixture(h,290000,true); var guard=guard(h,f); var party=party(h,f,PartyType.PILLAGER_PATROL,1,false,true);
        var event=DefenseService.detectMember(h.getLevel(),party.mobs().getFirst()).orElseThrow(); var quest=accept(h,f,f.player());
        h.assertTrue(DefenseService.status(f.player().server,f.settlement().id())==SettlementSafety.THREATENED,"Shared settlement is threatened");
        h.succeedWhen(() -> {
            var result=DefenseService.latest(f.player().server,f.settlement().id()).orElseThrow();
            h.assertTrue(guard.isAlive() && result.state()==ThreatState.RESOLVED,"Native Guard defeats real Pillager and resolves event");
            h.assertTrue(result.id().equals(event.id()) && result.remainingMembers()==0 && result.participants().isEmpty(),"Exact event succeeds without passive credit");
            h.assertTrue(quests(f).quest(f.player().getUUID(),quest.id()).orElseThrow().state()==QuestState.EXPIRED,"Accepted observer quest is closed without combat reward");
            h.assertTrue(quests(f).reputation(f.player().getUUID(),f.settlement().id())==0 && !advancement(f.player()),"Guard-only victory gives neither reputation nor advancement");
            h.assertTrue(DefenseService.status(f.player().server,f.settlement().id())==SettlementSafety.SAFE,"Post-defense returns to SAFE");
        });
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void nativeGuardUndeadVictoryUsesSameLocalEventAndReturnsSafe(GameTestHelper h) {
        var f=fixture(h,291024,true); var guard=guard(h,f); var party=party(h,f,PartyType.UNDEAD_HORDE,1,false,true);
        DefenseService.detectMember(h.getLevel(),party.mobs().getFirst());
        h.succeedWhen(() -> {
            var event=DefenseService.latest(f.player().server,f.settlement().id()).orElseThrow();
            h.assertTrue(guard.isAlive() && event.state()==ThreatState.RESOLVED && event.faction()==PartyType.UNDEAD_HORDE.faction(),"Normal Guard defeats real Undead member");
            h.assertTrue(DefenseService.status(f.player().server,f.settlement().id())==SettlementSafety.SAFE && !advancement(f.player()),"Undead defense clears shared status without passive rewards");
        });
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void playerDamageAndNativeGuardFinishEnableOneBoardClaimAndAdvancement(GameTestHelper h) {
        var f=fixture(h,292048,true); var guard=guard(h,f); var party=party(h,f,PartyType.PILLAGER_PATROL,1,false,true);
        DefenseService.detectMember(h.getLevel(),party.mobs().getFirst()); var quest=accept(h,f,f.player());
        party.mobs().getFirst().hurt(f.player().damageSources().playerAttack(f.player()),4);
        h.succeedWhen(() -> {
            var event=DefenseService.latest(f.player().server,f.settlement().id()).orElseThrow();
            h.assertTrue(guard.isAlive() && event.state()==ThreatState.RESOLVED && event.successfulParticipant(f.player().getUUID()),"Guard finish preserves meaningful player damage credit");
            var ready=quests(f).quest(f.player().getUUID(),quest.id()).orElseThrow();
            h.assertTrue(ready.objectiveSatisfied() && ready.state()==QuestState.ACTIVE,"Accepted contextual quest ready");
            DefenseService.awardPending(f.player());
            h.assertTrue(advancement(f.player()) && !KingdomMilestone.awardFirstDefense(f.player()),"Native milestone granted once for meaningful success");
            h.assertTrue(VillageUiService.openBoard(f.player(),f.board()),"Open normal Board to claim");
            UUID session=f.player().snapshots.getLast().getUUID("session"); int before=quests(f).reputation(f.player().getUUID(),f.settlement().id());
            h.assertTrue(VillageUiService.handle(f.player(),new UiPayloads.Request(session,quest.id(),UiPayloads.Action.CLAIM)),"Qualified player claims through existing UI");
            h.assertTrue(quests(f).reputation(f.player().getUUID(),f.settlement().id())==before+quest.rewards().reputation()
                    && emeralds(f.player())==quest.rewards().emeralds(),"Exact modest quest reward delivered");
            h.assertTrue(!VillageUiService.handle(f.player(),new UiPayloads.Request(session,quest.id(),UiPayloads.Action.CLAIM)),"Replayed claim rejected");
        });
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void multiplayerSharedProgressCountsOnlyLinkedMembersAndIndividualContributors(GameTestHelper h) {
        var f=fixture(h,293072,false); var party=party(h,f,PartyType.PILLAGER_PATROL,2,false,true); party.mobs().forEach(m -> m.setNoAi(true));
        var event=DefenseService.detectMember(h.getLevel(),party.mobs().getFirst()).orElseThrow();
        h.assertTrue(DefenseService.detectMember(h.getLevel(),party.mobs().getLast()).orElseThrow().id().equals(event.id()),"Same party cannot duplicate event");
        var a=accept(h,f,f.player()); var bPlayer=player(h.getLevel(),f.center().east(2)); var observer=player(h.getLevel(),f.center().east(2));
        var b=accept(h,f,bPlayer); var c=accept(h,f,observer);
        var cow=EntityType.COW.create(h.getLevel()); cow.setPos(f.center().getX()+4.5,f.center().getY(),f.center().getZ()+.5); h.getLevel().addFreshEntity(cow);
        cow.hurt(f.player().damageSources().playerAttack(f.player()),1000);
        h.assertTrue(DefenseService.current(f.player().server,f.settlement().id()).orElseThrow().remainingMembers()==2,"Unrelated death gives no progress");
        party.mobs().getFirst().hurt(f.player().damageSources().playerAttack(f.player()),4);
        party.mobs().getFirst().invulnerableTime=0; party.mobs().getFirst().hurt(bPlayer.damageSources().playerAttack(bPlayer),4);
        party.mobs().getFirst().invulnerableTime=0; party.mobs().getFirst().hurt(h.getLevel().damageSources().genericKill(),1000);
        h.assertTrue(DefenseService.current(f.player().server,f.settlement().id()).orElseThrow().defeatedMembers()==1,"Actual member death advances shared count once");
        party.mobs().getLast().hurt(h.getLevel().damageSources().genericKill(),1000);
        var result=DefenseService.latest(f.player().server,f.settlement().id()).orElseThrow();
        h.assertTrue(result.participants().equals(Set.of(f.player().getUUID(),bPlayer.getUUID())),"Eligibility frozen independently for two meaningful contributors");
        h.assertTrue(quests(f).quest(f.player().getUUID(),a.id()).orElseThrow().objectiveSatisfied()
                && quests(f).quest(bPlayer.getUUID(),b.id()).orElseThrow().objectiveSatisfied()
                && quests(f).quest(observer.getUUID(),c.id()).orElseThrow().state()==QuestState.EXPIRED,"Only accepted participating players may claim");
        DefenseService.awardPending(observer); h.assertTrue(!advancement(observer),"Proximity observer gets no advancement");
        h.assertTrue(VillageUiService.openBoard(bPlayer,f.board()),"Other player owns its own UI session"); var token=bPlayer.snapshots.getLast().getUUID("session");
        h.assertTrue(!VillageUiService.handle(f.player(),new UiPayloads.Request(token,b.id(),UiPayloads.Action.CLAIM)),"Other session cannot be used to steal reward");
        h.assertTrue(VillageUiService.handle(bPlayer,new UiPayloads.Request(token,b.id(),UiPayloads.Action.CLAIM)),"Second contributor claims its own share once");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void timeoutAndVanishedPartyCleanAcceptedQuestsWithoutDestruction(GameTestHelper h) {
        var f=fixture(h,294096,false); var party=party(h,f,PartyType.PILLAGER_PATROL,1,false,true); party.mobs().getFirst().setNoAi(true);
        long now=f.player().server.overworld().getGameTime();
        var event=new SettlementThreatEvent(UUID.randomUUID(),f.settlement().id(),party.data().origin().dimension(),party.data().faction(),party.data().id(),1,1,1,1,
                ThreatState.DETECTED,ThreatOutcome.NONE,now,-1,-1,now+20,false,true,Set.of());
        DefenseSavedData.get(f.player().server).detect(event); DefenseSavedData.get(f.player().server).activate(event.id(),now); var q=accept(h,f,f.player());
        h.runAfterDelay(25,() -> {
            h.assertTrue(DefenseService.current(f.player().server,f.settlement().id()).isEmpty(),"Timeout removes threatened state");
            h.assertTrue(DefenseService.latest(f.player().server,f.settlement().id()).orElseThrow().outcome()==ThreatOutcome.TIMEOUT
                    && quests(f).quest(f.player().getUUID(),q.id()).orElseThrow().state()==QuestState.FAILED,"Accepted timeout quest closes safely");
            var another=party(h,f,PartyType.UNDEAD_HORDE,1,false,true); another.mobs().getFirst().setNoAi(true);
            DefenseService.detectMember(h.getLevel(),another.mobs().getFirst()); var second=accept(h,f,f.player());
            EncounterSavedData.get(f.player().server).remove(another.data().id());
            h.assertTrue(DefenseService.current(f.player().server,f.settlement().id()).isEmpty()
                    && DefenseService.latest(f.player().server,f.settlement().id()).orElseThrow().outcome()==ThreatOutcome.PARTY_MISSING,"Vanished linked party fails without dangling event");
            h.assertTrue(quests(f).quest(f.player().getUUID(),second.id()).orElseThrow().state()==QuestState.FAILED && f.mayor().isAlive()
                    && SettlementSavedData.get(f.player().server).get(f.settlement().id()).isPresent(),"Settlement and Mayor survive simple failure");
            party.mobs().forEach(Entity::discard); another.mobs().forEach(Entity::discard); h.succeed();
        });
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void activeEventQuestAndNativeEntityRoundTripResumeWithoutReplay(GameTestHelper h) {
        var f=fixture(h,295120,false); var party=party(h,f,PartyType.UNDEAD_HORDE,1,false,true); var enemy=party.mobs().getFirst(); enemy.setNoAi(true);
        var event=DefenseService.detectMember(h.getLevel(),enemy).orElseThrow(); var quest=accept(h,f,f.player()); var registry=h.getLevel().registryAccess();
        var savedDefense=DefenseSavedData.load(DefenseSavedData.get(f.player().server).save(new CompoundTag(),registry),registry);
        var savedQuests=QuestSavedData.load(quests(f).save(new CompoundTag(),registry),registry);
        var savedEncounters=EncounterSavedData.load(EncounterSavedData.get(f.player().server).save(new CompoundTag(),registry),registry);
        h.assertTrue(savedDefense.active(f.settlement().id()).orElseThrow().equals(event)
                && savedQuests.quest(f.player().getUUID(),quest.id()).orElseThrow().state()==QuestState.ACTIVE
                && savedEncounters.get(party.data().id()).orElseThrow().remainingMembers().contains(enemy.getUUID()),"All persisted links survive native save format roundtrip");
        var tag=enemy.saveWithoutId(new CompoundTag()); enemy.discard(); var restored=EntityType.ZOMBIE.create(h.getLevel()); restored.load(tag); h.getLevel().addFreshEntity(restored);
        h.assertTrue(DefenseService.detectMember(h.getLevel(),restored).orElseThrow().id().equals(event.id()),"Restored same UUID resumes existing event without new alert");
        restored.hurt(f.player().damageSources().playerAttack(f.player()),1000);
        h.assertTrue(DefenseService.latest(f.player().server,f.settlement().id()).orElseThrow().successfulParticipant(f.player().getUUID()) && advancement(f.player()),"Restored threat resolves with native meaningful contribution");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void defaultDebugCommandsCannotGrantDefenseRewardOrFabricateVictory(GameTestHelper h) throws Exception {
        var f=fixture(h,296144,false); var dispatcher=f.player().server.getCommands().getDispatcher(); var source=f.player().createCommandSourceStack().withPermission(2);
        h.assertTrue(dispatcher.execute("kingdom defense trigger pillager",source)==1,"Explicit operator command creates real test party");
        var event=DefenseService.current(f.player().server,f.settlement().id()).orElseThrow(); var quest=accept(h,f,f.player());
        h.assertTrue(event.debug() && !event.rewardEligible() && quest.rewards().equals(new QuestRewards(0,0)),"Default debug snapshots zero rewards");
        for(var id:EncounterSavedData.get(f.player().server).get(event.partyId()).orElseThrow().remainingMembers())
            ((Mob)h.getLevel().getEntity(id)).hurt(f.player().damageSources().playerAttack(f.player()),1000);
        h.assertTrue(DefenseService.latest(f.player().server,f.settlement().id()).orElseThrow().participants().isEmpty()
                && quests(f).reputation(f.player().getUUID(),f.settlement().id())==0 && !advancement(f.player()),"Debug kill gives no personal reward or advancement");
        h.assertTrue(dispatcher.execute("kingdom defense trigger undead",source)==1 && dispatcher.execute("kingdom defense info",source)==1,"Second development threat and diagnostics work");
        var second=DefenseService.current(f.player().server,f.settlement().id()).orElseThrow();
        h.assertTrue(dispatcher.execute("kingdom defense resolve",source)==1,"Operator closes development threat safely");
        h.assertTrue(DefenseService.latest(f.player().server,f.settlement().id()).orElseThrow().outcome()==ThreatOutcome.DEBUG_CANCELED
                && EncounterSavedData.get(f.player().server).get(second.partyId()).orElseThrow().state()==PartyState.ALIVE,"Resolve command cannot manufacture victory or deaths");
        for(var id:EncounterSavedData.get(f.player().server).get(second.partyId()).orElseThrow().remainingMembers()) { var mob=h.getLevel().getEntity(id); if(mob!=null) mob.discard(); }
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=240)
    public static void periodicRealMemberDetectionProducesOneAlertAndContextualBoardSnapshot(GameTestHelper h) {
        var f=fixture(h,297168,false); var messages=new ArrayList<net.minecraft.network.chat.Component>();
        var observer=new UiTestPlayer(h.getLevel()) { @Override public void displayClientMessage(net.minecraft.network.chat.Component m,boolean actionbar) { messages.add(m); } };
        observer.setPos(f.center().getX()+.5,f.center().getY(),f.center().getZ()+.5); h.getLevel().addNewPlayer(observer);
        var party=party(h,f,PartyType.PILLAGER_PATROL,2,false,true); party.mobs().forEach(m -> m.setNoAi(true));
        h.runAfterDelay(170,() -> {
            try {
                h.assertTrue(messages.size()==1 && messages.getFirst().getString().contains(f.settlement().name()),"Real local ticks alert once despite multiple members and repeats");
                h.assertTrue(VillageUiService.openBoard(f.player(),f.board()),"Ordinary Board exposes contextual defense");
                var snapshot=f.player().snapshots.getLast(); var entries=snapshot.getList("quests",10);
                h.assertTrue(snapshot.getString("safety").equals("threatened") && snapshot.getBoolean("defense"),"Shared safety and defense state are player-facing");
                boolean found=false;
                for(int i=0;i<entries.size();i++) if(entries.getCompound(i).getString("template").equals("local_defense")) {
                    var entry=entries.getCompound(i); found=entry.getInt("defense_remaining")==2 && entry.getInt("defense_total")==2
                            && entry.getString("defense_faction").equals("pillager") && !entry.contains("party");
                }
                h.assertTrue(found,"Exact party progress, faction and readable title present without technical party IDs"); h.succeed();
            } finally { party.mobs().forEach(Entity::discard); h.getLevel().removePlayerImmediately(observer,Entity.RemovalReason.DISCARDED); }
        });
    }

    private static QuestSavedData quests(Fixture f) { return QuestSavedData.get(f.player().server); }
    private static boolean advancement(UiTestPlayer player) { var adv=player.server.getAdvancements().get(KingdomMilestone.FIRST_DEFENSE.id()); return adv!=null && player.getAdvancements().getOrStartProgress(adv).isDone(); }
    private static int emeralds(UiTestPlayer player) { return player.getInventory().items.stream().filter(s -> s.is(Items.EMERALD)).mapToInt(ItemStack::getCount).sum(); }
    private static QuestInstance accept(GameTestHelper h,Fixture f,UiTestPlayer player) {
        h.assertTrue(VillageUiService.openBoard(player,f.board()),"Board opens through physical anchor");
        var quest=QuestSavedData.get(player.server).quests(player.getUUID(),f.settlement().id()).stream().filter(q -> q.template()==QuestTemplate.LOCAL_DEFENSE && q.state()==QuestState.AVAILABLE).findFirst().orElseThrow();
        h.assertTrue(VillageUiService.handle(player,new UiPayloads.Request(player.snapshots.getLast().getUUID("session"),quest.id(),UiPayloads.Action.ACCEPT)),"Contextual quest accepted in normal Board"); return quest;
    }
    private static Villager guard(GameTestHelper h,Fixture f) {
        var candidate=ImmigrationService.attempt(h.getLevel(),f.settlement(),true).orElseThrow();
        h.assertTrue(ImmigrationService.accept(f.player(),f.settlement().id(),candidate.id()),"Immigrant accepted into home");
        var citizen=CitizenSavedData.get(f.player().server).citizen(candidate.id()).orElseThrow(); ProfessionService.ensure(h.getLevel(),f.settlement());
        h.assertTrue(ProfessionService.assignGuard(f.player(),f.settlement().id(),citizen.id()),"Guard assigned through existing authority");
        var guard=(Villager)h.getLevel().getEntity(citizen.entityId()); guard.setPos(f.center().getX()+2.5,f.center().getY(),f.center().getZ()+.5); return guard;
    }
    private static Party party(GameTestHelper h,Fixture f,PartyType type,int count,boolean debug,boolean rewards) {
        var mobs=new ArrayList<Mob>(); var ids=new HashSet<UUID>();
        for(int i=0;i<count;i++) {
            Mob mob=(type==PartyType.PILLAGER_PATROL?EntityType.PILLAGER:EntityType.ZOMBIE).create(h.getLevel());
            mob.setPos(f.center().getX()+10.5,f.center().getY(),f.center().getZ()+.5+i*2); mob.setPersistenceRequired();
            if(type==PartyType.PILLAGER_PATROL) mob.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.CROSSBOW)); else mob.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.IRON_HELMET));
            mobs.add(mob); ids.add(mob.getUUID());
        }
        var party=new HostileParty(UUID.randomUUID(),type.faction(),type,new OriginRegion(h.getLevel().dimension().location().toString(),f.center().getX(),f.center().getY(),f.center().getZ(),8),f.settlement().id(),PartyState.ALIVE,ids,ids,2,2,debug,rewards);
        EncounterSavedData.get(f.player().server).add(party,f.player().server.overworld().getGameTime());
        for(var mob:mobs) { EncounterMember.attach(mob,party.id(),party.faction()); h.getLevel().addFreshEntity(mob); FactionCombat.install(mob); }
        return new Party(party,List.copyOf(mobs));
    }
    private static UiTestPlayer player(ServerLevel level,BlockPos at) { var p=new UiTestPlayer(level) { @Override public boolean isFakePlayer() { return false; } }; p.getAbilities().mayBuild=true; p.setPos(at.getX()+.5,at.getY(),at.getZ()+.5); return p; }
    private static Fixture fixture(GameTestHelper h,int coordinate,boolean barracks) {
        var level=h.getLevel(); var center=h.absolutePos(new BlockPos(coordinate,1,coordinate)); int radius=48;
        for(int x=(center.getX()-radius)>>4;x<=(center.getX()+radius)>>4;x++) for(int z=(center.getZ()-radius)>>4;z<=(center.getZ()+radius)>>4;z++) level.getChunk(x,z);
        var chunk=new ChunkPos(center); level.getChunkSource().addRegionTicket(FIXTURE,chunk,5,chunk);
        for(int x=-radius;x<=radius;x++) for(int z=-radius;z<=radius;z++) { level.setBlock(center.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState(),18); for(int y=0;y<13;y++) level.setBlock(center.offset(x,y,z),Blocks.AIR.defaultBlockState(),18); }
        var s=Settlement.established(UUID.randomUUID(),new Territory(level.dimension().location().toString(),center.getX(),center.getY(),center.getZ(),radius),1,SettlementOrigin.CONVERTED,UUID.randomUUID());
        var buildings=new ArrayList<SettlementLayoutMetadata.Building>(); var catalog=BuildingCatalog.load(level,ArchitectureStyle.PLAINS);
        for(int i=0;i<(barracks?4:2);i++) {
            var m=catalog.get(i==3?BuildingKind.BARRACKS:BuildingKind.HOUSE); var at=center.offset(i>=2?-18:12,-1,i%2==1?-16:12);
            m.template().placeInWorld(level,at,at,SettlementTemplate.settings(),level.random,18);
            buildings.add(new SettlementLayoutMetadata.Building(m.kind(),m.id(),at,Rotation.NONE,new PlotBounds(at.getX(),at.getZ(),at.getX()+m.size().getX()-1,at.getZ()+m.size().getZ()-1),at.offset(m.entrance())));
        }
        SettlementSavedData.get(level.getServer()).add(s,new SettlementLayoutMetadata(ArchitectureStyle.PLAINS,buildings,List.of(center.east().below()),List.of())); level.setBlock(center,Blocks.LODESTONE.defaultBlockState(),18);
        var mayor=NpcService.ensureMayor(level,s).orElseThrow(); ProfessionService.ensure(level,s); var board=center.south(3); level.setBlock(board,KingdomBlocks.QUEST_BOARD.get().defaultBlockState(),18);
        return new Fixture(center,s,player(level,center.east(2)),mayor,board);
    }
}

package dev.livingkingdoms.defense;

import dev.livingkingdoms.advancement.KingdomMilestone;
import dev.livingkingdoms.config.DefenseConfig;
import dev.livingkingdoms.config.KingdomConfig;
import dev.livingkingdoms.defense.domain.*;
import dev.livingkingdoms.defense.persistence.DefenseSavedData;
import dev.livingkingdoms.encounter.EncounterMember;
import dev.livingkingdoms.encounter.domain.HostileParty;
import dev.livingkingdoms.encounter.domain.PartyState;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import dev.livingkingdoms.profession.GuardWork;
import dev.livingkingdoms.profession.SecurityService;
import dev.livingkingdoms.profession.domain.GuardPolicy;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.NearestAlliedSettlementService;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.BuildingKind;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Event-driven local defense. Minecraft owns the mobs; encounter damage owns participation. */
public final class DefenseService {
    private DefenseService() {}

    public static Optional<SettlementThreatEvent> current(MinecraftServer server, UUID settlement) {
        var event = DefenseSavedData.get(server).active(settlement);
        event.ifPresent(value -> reconcile(server, value));
        return DefenseSavedData.get(server).active(settlement);
    }
    public static Optional<SettlementThreatEvent> latest(MinecraftServer server, UUID settlement) {
        return DefenseSavedData.get(server).latest(settlement);
    }
    public static Optional<SettlementThreatEvent> eventForParty(MinecraftServer server, UUID party) {
        return DefenseSavedData.get(server).forParty(party);
    }
    public static SettlementSafety status(MinecraftServer server, UUID settlement) {
        return current(server, settlement).filter(e -> e.state()!=ThreatState.APPROACHING).isPresent() ? SettlementSafety.THREATENED : SettlementSafety.SAFE;
    }

    /** Completed building metadata only; pending projects and multiple towers supply no extra bonus. */
    public static double detectionRange(MinecraftServer server, Settlement settlement) {
        var buildings = ProfessionSavedData.get(server).buildings(settlement.id());
        boolean tower = buildings.stream().anyMatch(b -> b.active() && b.kind() == BuildingKind.WATCHTOWER);
        // Older saves can have native layout records before profession metadata has been initialized.
        if (buildings.isEmpty()) tower = SettlementSavedData.get(server).layout(settlement.id())
                .map(layout -> layout.buildings().stream().anyMatch(b -> b.kind() == BuildingKind.WATCHTOWER)).orElse(false);
        double security = SecurityService.score(server, settlement.id()) / 100.0 * DefenseConfig.SECURITY_DETECTION_BONUS.get();
        return Math.min(DefenseConfig.MAX_DETECTION_RANGE.get(), DefenseConfig.NORMAL_DETECTION_RANGE.get() + security
                + (tower ? DefenseConfig.WATCHTOWER_DETECTION_BONUS.get() : 0));
    }

    /** Called for a loaded member, at bounded intervals or when a Guard acquires it. */
    public static Optional<SettlementThreatEvent> detectMember(ServerLevel level, LivingEntity member) {
        var tag = EncounterMember.read(member);
        if (tag.isEmpty()) return Optional.empty();
        var party = EncounterSavedData.get(level.getServer()).forMember(member.getUUID());
        if (party.isEmpty() || !party.get().id().equals(tag.get().partyId()) || party.get().faction() != tag.get().faction()
                || !party.get().remainingMembers().contains(member.getUUID())) return Optional.empty();
        return detect(level, party.get(), member.blockPosition());
    }

    /** Position must come from an actual party member, never its possibly stale origin. */
    public static Optional<SettlementThreatEvent> detect(ServerLevel level, HostileParty party, BlockPos position) {
        MinecraftServer server = level.getServer();
        var data = DefenseSavedData.get(server);
        var known = data.forParty(party.id());
        if (known.isPresent()) {
            SettlementSavedData.get(server).get(known.get().settlementId()).ifPresent(s -> advance(level,known.get(),s,position));
            return data.forParty(party.id());
        }
        if (party.state() != PartyState.ALIVE || !GuardPolicy.hostile(party.faction())
                || !party.origin().dimension().equals(level.dimension().location().toString())
                || !EncounterSavedData.get(server).get(party.id()).filter(party::equals).isPresent()) return Optional.empty();
        var allied = NearestAlliedSettlementService.findNearest(level, position, DefenseConfig.MAX_DETECTION_RANGE.get())
                .filter(s -> GuardPolicy.within(position.getX() + .5, position.getZ() + .5,
                        s.territory().x() + .5, s.territory().z() + .5, detectionRange(server, s)));
        if (allied.isEmpty()) return Optional.empty();
        Settlement settlement = allied.get();
        current(server, settlement.id()); // An expired previous threat must not block a fresh one.
        long now = server.overworld().getGameTime();
        var event = new SettlementThreatEvent(UUID.randomUUID(), settlement.id(), party.origin().dimension(),
                party.faction(), party.id(), party.threatRating(), (int) Math.ceil(party.levels().average()),
                party.memberIds().size(), party.remainingMembers().size(), ThreatState.DETECTED, ThreatOutcome.NONE,
                now, -1, -1, Math.addExact(now, DefenseConfig.EVENT_DURATION.get()), party.debug(),
                party.rewardEligible(), Set.of());
        if (!data.detect(event)) return Optional.empty();
        if(GuardPolicy.within(position.getX()+.5,position.getZ()+.5,settlement.territory().x()+.5,settlement.territory().z()+.5,GuardWork.defense(settlement)))
            data.activate(event.id(), now);
        spotted(level,settlement,event);
        install(level,party);
        return data.get(event.id());
    }

    public static Optional<SettlementThreatEvent> beginApproach(ServerLevel level,Settlement settlement,HostileParty party) {
        var server=level.getServer(); var data=DefenseSavedData.get(server);
        if(!settlement.faction().isAllied() || party.state()!=PartyState.ALIVE || !GuardPolicy.hostile(party.faction())
                || !party.origin().dimension().equals(settlement.territory().dimension())
                || !party.origin().dimension().equals(level.dimension().location().toString())
                || EncounterSavedData.get(server).get(party.id()).filter(party::equals).isEmpty()) return Optional.empty();
        double distance=Math.hypot(party.origin().x()-settlement.territory().x(),party.origin().z()-settlement.territory().z());
        if(!ThreatApproachPolicy.validOrigin(distance,settlement.territory().radius(),DefenseConfig.APPROACH_MIN_DISTANCE.get(),DefenseConfig.APPROACH_MAX_DISTANCE.get())) return Optional.empty();
        current(server,settlement.id()); long now=server.overworld().getGameTime();
        var event=new SettlementThreatEvent(UUID.randomUUID(),settlement.id(),party.origin().dimension(),party.faction(),party.id(),party.threatRating(),
                (int)Math.ceil(party.levels().average()),party.memberIds().size(),party.remainingMembers().size(),ThreatState.APPROACHING,ThreatOutcome.NONE,
                now,-1,-1,Math.addExact(now,DefenseConfig.EVENT_DURATION.get()),party.debug(),party.rewardEligible(),Set.of());
        if(!data.detect(event)) return Optional.empty();
        install(level,party); return data.get(event.id());
    }
    private static void install(ServerLevel level,HostileParty party) {
        for(var id:party.remainingMembers()) if(level.getEntity(id) instanceof net.minecraft.world.entity.Mob mob) ThreatApproachWork.install(mob);
    }
    private static void spotted(ServerLevel level,Settlement settlement,SettlementThreatEvent event) {
        SecurityService.alertApproaching(level,settlement,event.faction());
    }
    private static void advance(ServerLevel level,SettlementThreatEvent event,Settlement settlement,BlockPos position) {
        if(level.getServer().overworld().getGameTime()>=event.expiresAt()) return;
        var data=DefenseSavedData.get(level.getServer());
        double distance=Math.hypot(position.getX()-settlement.territory().x(),position.getZ()-settlement.territory().z());
        if(event.state()==ThreatState.APPROACHING && distance<=detectionRange(level.getServer(),settlement) && data.spot(event.id())) {
            spotted(level,settlement,event); event=data.get(event.id()).orElseThrow();
        }
        if(event.state()==ThreatState.DETECTED && distance<=GuardWork.defense(settlement)) data.activate(event.id(),level.getServer().overworld().getGameTime());
    }

    /** Every actual member death updates only its own linked event, including Guard/environment deaths. */
    public static void onPartyChanged(ServerLevel level, UUID partyId, ServerPlayer killer) {
        var event = eventForParty(level.getServer(), partyId);
        event.ifPresent(value -> reconcile(level.getServer(), value));
        if (killer != null) awardPending(killer);
    }

    /** Resume persisted links or close broken/expired links without inventing a victory. */
    public static void reconcile(MinecraftServer server, SettlementThreatEvent snapshot) {
        var data = DefenseSavedData.get(server);
        var event = data.get(snapshot.id()).orElse(null);
        if (event == null || !event.threatened()) return;
        long now = server.overworld().getGameTime();
        var settlement = SettlementSavedData.get(server).get(event.settlementId());
        if (settlement.isEmpty() || !settlement.get().faction().isAllied()
                || !settlement.get().territory().dimension().equals(event.dimension())) {
            fail(server, event, ThreatOutcome.SETTLEMENT_MISSING); return;
        }
        if (now >= event.expiresAt()) { fail(server, event, ThreatOutcome.TIMEOUT); return; }
        var encounters = EncounterSavedData.get(server);
        var party = encounters.get(event.partyId());
        if (party.isEmpty() || party.get().faction() != event.faction()
                || !party.get().origin().dimension().equals(event.dimension())
                || party.get().memberIds().size() != event.totalMembers()) {
            fail(server, event, ThreatOutcome.PARTY_MISSING); return;
        }
        var level=server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(event.dimension())));
        if(level!=null) for(var id:party.get().remainingMembers()) if(level.getEntity(id) instanceof LivingEntity member && member.isAlive()) {
            advance(level,data.get(event.id()).orElseThrow(),settlement.get(),member.blockPosition());
            if(member instanceof net.minecraft.world.entity.Mob mob) ThreatApproachWork.install(mob);
        }
        data.updateProgress(event.id(), party.get().remainingMembers().size());
        if (party.get().state() != PartyState.DEFEATED) return;
        // Intercepting the linked party outside the walls is still a defense victory.
        if(data.get(event.id()).orElseThrow().state()==ThreatState.APPROACHING) data.spot(event.id());
        if(data.get(event.id()).orElseThrow().state()==ThreatState.DETECTED) data.activate(event.id(),now);
        Set<UUID> participants = event.rewardEligible() ? encounters.eligibleParticipants(event.partyId(), now,
                KingdomConfig.ENCOUNTER_MIN_CONTRIBUTION.get(), EncounterSavedData.PARTICIPATION_EXPIRY_TICKS) : Set.of();
        if (!data.resolve(event.id(), now, participants)) return;
        var resolved = data.get(event.id()).orElseThrow();
        DefenseQuestService.resolved(server, resolved);
        notifyLocal(server, settlement.get(), "defense.livingkingdoms.safe");
        for (UUID id : resolved.participants()) {
            var player = server.getPlayerList().getPlayer(id);
            if (player == null) for (var playerLevel : server.getAllLevels()) {
                var local = playerLevel.getPlayerByUUID(id);
                if (local instanceof ServerPlayer found) { player = found; break; }
            }
            if (player != null) awardPending(player);
        }
    }

    public static boolean fail(MinecraftServer server, SettlementThreatEvent event, ThreatOutcome outcome) {
        var data = DefenseSavedData.get(server);
        if (!data.fail(event.id(), server.overworld().getGameTime(), outcome)) return false;
        DefenseQuestService.failed(server, data.get(event.id()).orElseThrow());
        SettlementSavedData.get(server).get(event.settlementId())
                .ifPresent(s -> notifyLocal(server, s, "defense.livingkingdoms.failed"));
        return true;
    }
    public static void onPartyRemoved(MinecraftServer server, UUID party) {
        eventForParty(server, party).filter(SettlementThreatEvent::threatened)
                .ifPresent(event -> fail(server, event, ThreatOutcome.PARTY_MISSING));
    }
    private static void notifyLocal(MinecraftServer server, Settlement settlement, String key) {
        for (var level : server.getAllLevels()) {
            if (!level.dimension().location().toString().equals(settlement.territory().dimension())) continue;
            for (var player : level.players()) if (player.isAlive() && !player.isSpectator()
                    && settlement.territory().contains(settlement.territory().dimension(), player.blockPosition().getX(), player.blockPosition().getZ()))
                player.displayClientMessage(Component.translatable(key, settlement.name()), false);
        }
    }
    public static void awardPending(ServerPlayer player) {
        if (DefenseSavedData.get(player.server).hasSuccessfulParticipation(player.getUUID())) KingdomMilestone.awardFirstDefense(player);
    }
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) awardPending(player);
    }
    public static void onServerTick(ServerTickEvent.Post tick) {
        MinecraftServer server = tick.getServer();
        long now = server.overworld().getGameTime();
        if (now % DefenseConfig.DETECTION_INTERVAL.get() != 0) return;
        var data = DefenseSavedData.get(server);
        for (var event : data.activeEvents(16)) reconcile(server, event);
        if (now % 1200 != 0) return;
        var parties = EncounterSavedData.get(server);
        var quests = QuestSavedData.get(server);
        for (var event : data.terminalEvents(16)) if (now - event.resolvedAt() >= DefenseConfig.RETENTION.get()
                && parties.get(event.partyId()).isEmpty() && !quests.hasDefenseQuest(event.partyId())) data.removeTerminal(event.id());
    }
}

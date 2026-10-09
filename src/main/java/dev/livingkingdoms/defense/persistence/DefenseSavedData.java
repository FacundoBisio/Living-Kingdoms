package dev.livingkingdoms.defense.persistence;

import dev.livingkingdoms.defense.domain.*;
import dev.livingkingdoms.faction.Faction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Additional server-owned metadata. Existing settlement, quest and encounter schemas stay intact. */
public final class DefenseSavedData extends SavedData {
    public static final String DATA_NAME = "livingkingdoms_defense";
    public static final int MAX_EVENTS = 65536;
    public static final int MAX_ACTIVE_EVENTS = 4096;
    private static final int MAX_SUCCESSFUL_PLAYERS = 100000;
    private final Map<UUID, SettlementThreatEvent> events = new LinkedHashMap<>();
    private final Map<UUID, UUID> byParty = new HashMap<>(), activeBySettlement = new LinkedHashMap<>(), latestBySettlement = new HashMap<>();
    private record EventOrder(long detectedAt, long sequence, UUID id) implements Comparable<EventOrder> {
        @Override public int compareTo(EventOrder other) {
            int time = Long.compare(detectedAt, other.detectedAt);
            return time != 0 ? time : Long.compare(sequence, other.sequence);
        }
    }
    private final Map<UUID, NavigableSet<EventOrder>> settlementHistory = new HashMap<>();
    private final Map<UUID, EventOrder> eventOrders = new HashMap<>();
    private long nextSequence;
    private final Set<UUID> successfulPlayers = new LinkedHashSet<>();
    private final List<UUID> activeIds = new ArrayList<>();
    private final Map<UUID, Integer> activePositions = new HashMap<>();
    private final List<UUID> terminalIds = new ArrayList<>();
    private final Map<UUID, Integer> terminalPositions = new HashMap<>();
    private int activeCursor, terminalCursor;
    private Thread owner;

    public DefenseSavedData() {}
    DefenseSavedData(Thread owner) { this.owner = Objects.requireNonNull(owner); }

    public static DefenseSavedData get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Defense data requires the server thread");
        var data = getOrCreate(server.overworld().getDataStorage(), server.getWorldPath(LevelResource.ROOT).resolve("data"));
        data.owner = Thread.currentThread(); return data;
    }
    public static DefenseSavedData getOrCreate(DimensionDataStorage storage, Path directory) {
        return storage.computeIfAbsent(new Factory<>(() -> {
            if (!Files.notExists(directory.resolve(DATA_NAME + ".dat")))
                throw new IllegalStateException("Unreadable Living Kingdoms defense data; refusing to overwrite it");
            return new DefenseSavedData();
        }, DefenseSavedData::load), DATA_NAME);
    }
    private void authority() {
        if (owner != null && owner != Thread.currentThread()) throw new IllegalStateException("Defense data requires the server thread");
    }
    public Optional<SettlementThreatEvent> get(UUID id) { authority(); return Optional.ofNullable(events.get(Objects.requireNonNull(id))); }
    public Optional<SettlementThreatEvent> forParty(UUID party) {
        authority(); return Optional.ofNullable(byParty.get(Objects.requireNonNull(party))).map(events::get);
    }
    public Optional<SettlementThreatEvent> active(UUID settlement) {
        authority(); return Optional.ofNullable(activeBySettlement.get(Objects.requireNonNull(settlement))).map(events::get);
    }
    public Optional<SettlementThreatEvent> latest(UUID settlement) {
        authority(); return Optional.ofNullable(latestBySettlement.get(Objects.requireNonNull(settlement))).map(events::get);
    }
    public List<SettlementThreatEvent> activeEvents() {
        authority(); return activeBySettlement.values().stream().map(events::get).toList();
    }
    /** Rotating bounded timeout/reload reconciliation sample; deaths update their linked event immediately. */
    public List<SettlementThreatEvent> activeEvents(int limit) {
        authority(); if (limit < 1 || limit > 256) throw new IllegalArgumentException("Active sample limit must be 1..256");
        int size = Math.min(limit, activeIds.size()); var result = new ArrayList<SettlementThreatEvent>(size);
        for (int i = 0; i < size; i++) {
            if (activeCursor >= activeIds.size()) activeCursor = 0;
            result.add(events.get(activeIds.get(activeCursor++)));
        }
        return List.copyOf(result);
    }
    public List<SettlementThreatEvent> terminalEvents() {
        authority(); return terminalIds.stream().map(events::get).toList();
    }
    /** Rotating bounded maintenance sample; never copies or walks the full terminal history. */
    public List<SettlementThreatEvent> terminalEvents(int limit) {
        authority(); if (limit < 1 || limit > 256) throw new IllegalArgumentException("Terminal sample limit must be 1..256");
        int size = Math.min(limit, terminalIds.size()); var result = new ArrayList<SettlementThreatEvent>(size);
        for (int i = 0; i < size; i++) {
            if (terminalCursor >= terminalIds.size()) terminalCursor = 0;
            result.add(events.get(terminalIds.get(terminalCursor++)));
        }
        return List.copyOf(result);
    }
    public boolean hasSuccessfulParticipation(UUID player) {
        authority(); return successfulPlayers.contains(Objects.requireNonNull(player));
    }
    public boolean detect(SettlementThreatEvent event) {
        authority(); Objects.requireNonNull(event);
        if (event.state() != ThreatState.DETECTED) throw new IllegalArgumentException("New threats must start as DETECTED");
        if (events.containsKey(event.id()) || byParty.containsKey(event.partyId()) || activeBySettlement.containsKey(event.settlementId())
                || events.size() >= MAX_EVENTS || activeBySettlement.size() >= MAX_ACTIVE_EVENTS) return false;
        put(event); return true;
    }
    public boolean updateProgress(UUID id, int remaining) {
        authority(); var event = get(id).orElse(null);
        if (event == null || !event.threatened() || remaining < 0 || remaining > event.remainingMembers()) return false;
        if (remaining != event.remainingMembers()) put(event.withProgress(remaining)); return true;
    }
    public boolean activate(UUID id, long now) {
        authority(); var event = get(id).orElse(null);
        if (event == null || event.state() != ThreatState.DETECTED || now < event.detectedAt() || now >= event.expiresAt()) return false;
        put(event.activated(now)); return true;
    }
    public boolean resolve(UUID id, long now, Set<UUID> eligiblePlayers) {
        authority(); Objects.requireNonNull(eligiblePlayers); var event = get(id).orElse(null);
        if (event == null || event.state() != ThreatState.ACTIVE || event.remainingMembers() != 0
                || now < event.startedAt() || now >= event.expiresAt()) return false;
        var eligible = Set.copyOf(eligiblePlayers);
        if (!event.rewardEligible()) eligible = Set.of();
        if (eligible.size() > SettlementThreatEvent.MAX_PARTICIPANTS || newPlayerCount(eligible) + (long) successfulPlayers.size() > MAX_SUCCESSFUL_PLAYERS) return false;
        put(event.resolved(now, eligible)); return true;
    }
    public boolean fail(UUID id, long now, ThreatOutcome reason) {
        authority(); Objects.requireNonNull(reason); var event = get(id).orElse(null);
        if (event == null || !event.threatened() || now < Math.max(event.detectedAt(), event.startedAt())
                || reason == ThreatOutcome.NONE || reason == ThreatOutcome.VICTORY
                || reason == ThreatOutcome.TIMEOUT && now < event.expiresAt()) return false;
        put(event.failed(now, reason)); return true;
    }
    /** Compare-and-set receipt: identity, difficulty, timing and reward eligibility cannot be rewritten. */
    public boolean replace(SettlementThreatEvent expected, SettlementThreatEvent next) {
        authority(); Objects.requireNonNull(expected); Objects.requireNonNull(next);
        if (!sameIdentity(expected, next)) throw new IllegalArgumentException("Threat identity changed");
        if (!expected.equals(events.get(expected.id())) || !expected.threatened()) return false;
        if (next.remainingMembers() > expected.remainingMembers() || expected.state() == ThreatState.ACTIVE && next.state() == ThreatState.DETECTED
                || expected.startedAt() != -1 && next.startedAt() != expected.startedAt()
                || expected.state() == ThreatState.DETECTED && next.state() == ThreatState.RESOLVED
                || expected.state() == ThreatState.DETECTED && next.state() == ThreatState.FAILED && next.startedAt() != -1
                || newPlayerCount(next.participants()) + (long) successfulPlayers.size() > MAX_SUCCESSFUL_PLAYERS) return false;
        if (!next.equals(expected)) put(next); return true;
    }
    /** Caller must first verify that the party is absent, retention elapsed and no live quest refers to it. */
    public boolean removeTerminal(UUID id) {
        authority(); var event = get(id).orElse(null); if (event == null || event.threatened()) return false;
        events.remove(id); byParty.remove(event.partyId());
        int position = terminalPositions.remove(id), last = terminalIds.size() - 1; UUID moved = terminalIds.remove(last);
        if (position != last) { terminalIds.set(position, moved); terminalPositions.put(moved, position); }
        if (terminalCursor > terminalIds.size()) terminalCursor = 0;
        var history = settlementHistory.get(event.settlementId()); history.remove(eventOrders.remove(event.id()));
        if (history.isEmpty()) { settlementHistory.remove(event.settlementId()); latestBySettlement.remove(event.settlementId()); }
        else latestBySettlement.put(event.settlementId(), history.last().id());
        setDirty(); return true;
    }
    private long newPlayerCount(Set<UUID> players) { return players.stream().filter(id -> !successfulPlayers.contains(id)).count(); }
    private void put(SettlementThreatEvent event) {
        events.put(event.id(), event); byParty.put(event.partyId(), event.id());
        if (event.threatened()) {
            activeBySettlement.put(event.settlementId(), event.id());
            if (!activePositions.containsKey(event.id())) { activePositions.put(event.id(), activeIds.size()); activeIds.add(event.id()); }
        }
        else {
            activeBySettlement.remove(event.settlementId(), event.id());
            Integer position = activePositions.remove(event.id());
            if (position != null) {
                int last = activeIds.size() - 1; UUID moved = activeIds.remove(last);
                if (position != last) { activeIds.set(position, moved); activePositions.put(moved, position); }
                if (activeCursor > activeIds.size()) activeCursor = 0;
            }
            if (!terminalPositions.containsKey(event.id())) { terminalPositions.put(event.id(), terminalIds.size()); terminalIds.add(event.id()); }
        }
        indexLatest(event); successfulPlayers.addAll(event.participants()); setDirty();
    }
    private void indexLatest(SettlementThreatEvent event) {
        var history = settlementHistory.computeIfAbsent(event.settlementId(), key -> new TreeSet<>());
        // LinkedHashMap save order preserves creation order across reload, including two events in one tick.
        history.add(eventOrders.computeIfAbsent(event.id(), id -> new EventOrder(event.detectedAt(), nextSequence++, id)));
        latestBySettlement.put(event.settlementId(), history.last().id());
    }
    private static boolean sameIdentity(SettlementThreatEvent a, SettlementThreatEvent b) {
        return a.id().equals(b.id()) && a.settlementId().equals(b.settlementId()) && a.dimension().equals(b.dimension())
                && a.faction() == b.faction() && a.partyId().equals(b.partyId()) && a.threatRating() == b.threatRating()
                && a.recommendedLevel() == b.recommendedLevel() && a.totalMembers() == b.totalMembers()
                && a.detectedAt() == b.detectedAt() && a.expiresAt() == b.expiresAt() && a.debug() == b.debug()
                && a.rewardEligible() == b.rewardEligible();
    }

    public static DefenseSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        require(tag, "schema_version", Tag.TAG_INT);
        if (tag.getInt("schema_version") != 1) throw new IllegalArgumentException("Unsupported defense schema");
        var data = new DefenseSavedData();
        for (Tag entry : list(tag, "events", MAX_EVENTS)) {
            var e = (CompoundTag) entry; var participants = new LinkedHashSet<UUID>();
            for (Tag participant : list(e, "participants", SettlementThreatEvent.MAX_PARTICIPANTS))
                if (!participants.add(uuid((CompoundTag) participant, "player"))) throw new IllegalArgumentException("Duplicate defense participant");
            var event = new SettlementThreatEvent(uuid(e, "id"), uuid(e, "settlement"), string(e, "dimension"), Faction.fromId(string(e, "faction")),
                    uuid(e, "party"), integer(e, "threat"), integer(e, "recommended_level"), integer(e, "total"), integer(e, "remaining"),
                    ThreatState.valueOf(string(e, "state")), ThreatOutcome.valueOf(string(e, "outcome")), number(e, "detected_at"),
                    number(e, "started_at"), number(e, "resolved_at"), number(e, "expires_at"), bool(e, "debug"), bool(e, "reward_eligible"), participants);
            if (data.events.containsKey(event.id()) || data.byParty.containsKey(event.partyId())
                    || event.threatened() && (data.activeBySettlement.containsKey(event.settlementId()) || data.activeBySettlement.size() >= MAX_ACTIVE_EVENTS))
                throw new IllegalArgumentException("Duplicate or overbooked defense event");
            if (data.newPlayerCount(event.participants()) + (long) data.successfulPlayers.size() > MAX_SUCCESSFUL_PLAYERS)
                throw new IllegalArgumentException("Too many successful defense participants");
            data.put(event);
        }
        var successful = new LinkedHashSet<UUID>();
        for (Tag participant : list(tag, "successful_players", MAX_SUCCESSFUL_PLAYERS))
            if (!successful.add(uuid((CompoundTag) participant, "player"))) throw new IllegalArgumentException("Duplicate successful defense player");
        if (!successful.containsAll(data.successfulPlayers)) throw new IllegalArgumentException("Missing successful defense participation receipt");
        data.successfulPlayers.clear(); data.successfulPlayers.addAll(successful); data.setDirty(false); return data;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        authority(); tag.putInt("schema_version", 1); var list = new ListTag();
        for (var event : events.values()) {
            var e = new CompoundTag(); e.putUUID("id", event.id()); e.putUUID("settlement", event.settlementId());
            e.putString("dimension", event.dimension()); e.putString("faction", event.faction().id()); e.putUUID("party", event.partyId());
            e.putInt("threat", event.threatRating()); e.putInt("recommended_level", event.recommendedLevel());
            e.putInt("total", event.totalMembers()); e.putInt("remaining", event.remainingMembers()); e.putString("state", event.state().name());
            e.putString("outcome", event.outcome().name()); e.putLong("detected_at", event.detectedAt()); e.putLong("started_at", event.startedAt());
            e.putLong("resolved_at", event.resolvedAt()); e.putLong("expires_at", event.expiresAt());
            e.putBoolean("debug", event.debug()); e.putBoolean("reward_eligible", event.rewardEligible());
            e.put("participants", players(event.participants())); list.add(e);
        }
        tag.put("events", list); tag.put("successful_players", players(successfulPlayers)); return tag;
    }
    private static ListTag players(Set<UUID> players) {
        var list = new ListTag(); for (UUID player : players) { var e = new CompoundTag(); e.putUUID("player", player); list.add(e); } return list;
    }
    private static ListTag list(CompoundTag e, String field, int max) {
        require(e, field, Tag.TAG_LIST); var tag = (ListTag) e.get(field);
        if (tag.size() > max || !tag.isEmpty() && tag.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Invalid defense list: " + field);
        return tag;
    }
    private static void require(CompoundTag e, String field, int type) {
        if (!e.contains(field, type)) throw new IllegalArgumentException("Invalid defense field: " + field);
    }
    private static UUID uuid(CompoundTag e, String field) {
        if (!e.hasUUID(field)) throw new IllegalArgumentException("Invalid defense UUID: " + field); return e.getUUID(field);
    }
    private static String string(CompoundTag e, String field) { require(e, field, Tag.TAG_STRING); return e.getString(field); }
    private static int integer(CompoundTag e, String field) { require(e, field, Tag.TAG_INT); return e.getInt(field); }
    private static long number(CompoundTag e, String field) { require(e, field, Tag.TAG_LONG); return e.getLong(field); }
    private static boolean bool(CompoundTag e, String field) {
        require(e, field, Tag.TAG_BYTE); int value = e.getByte(field);
        if (value != 0 && value != 1) throw new IllegalArgumentException("Invalid defense boolean: " + field); return value == 1;
    }
}

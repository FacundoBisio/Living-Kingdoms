package dev.livingkingdoms.quest.persistence;

import dev.livingkingdoms.quest.domain.PlayerSettlementProgress;
import dev.livingkingdoms.quest.domain.QuestId;
import dev.livingkingdoms.quest.domain.QuestState;
import dev.livingkingdoms.quest.domain.QuestTerms;
import dev.livingkingdoms.quest.expansion.domain.QuestCategory;
import dev.livingkingdoms.quest.expansion.domain.QuestInstance;
import dev.livingkingdoms.quest.expansion.domain.QuestObjective;
import dev.livingkingdoms.quest.expansion.domain.QuestTemplate;
import dev.livingkingdoms.quest.expansion.persistence.ExpandedQuestNbt;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;

/** Global Overworld storage for UUID relationships; production access is on the server thread. */
public final class QuestSavedData extends SavedData {
    public static final String DATA_NAME = "livingkingdoms_quests";
    private static final int SCHEMA_VERSION = 4;
    private static final int MAX_EXPANDED_QUESTS = 256;
    private static final int MAX_BOARDS = 1024;
    private final Map<RelationshipKey, Relationship> relationships = new LinkedHashMap<>();
    private final Map<UUID, UUID> mayors = new LinkedHashMap<>();
    private final Map<UUID, EncounterRewardReceipt> encounterRewards = new LinkedHashMap<>();
    private final Map<UUID, ExpandedProgress> expandedPlayers = new LinkedHashMap<>();
    /** Only accepted, unresolved combat quests are indexed. No scan of all players on a death. */
    private final Map<UUID, Set<QuestReference>> partyQuests = new LinkedHashMap<>();

    public static QuestSavedData get(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Quest data must be accessed on the server thread");
        }
        return getOrCreate(server.overworld().getDataStorage(),
                server.getWorldPath(LevelResource.ROOT).resolve("data"));
    }

    static QuestSavedData getOrCreate(DimensionDataStorage storage, Path directory) {
        // Vanilla can swallow deserialize errors. An existing unreadable file must never
        // become a new empty store that the next save would overwrite.
        Factory<QuestSavedData> factory = new Factory<>(() -> {
            if (!Files.notExists(directory.resolve(DATA_NAME + ".dat"))) {
                throw new IllegalStateException("Existing Living Kingdoms quest data could not be loaded; "
                        + "refusing to overwrite it. Back up the world and inspect the server log.");
            }
            return new QuestSavedData();
        }, QuestSavedData::load);
        return storage.computeIfAbsent(factory, DATA_NAME);
    }

    public PlayerSettlementProgress progress(UUID player, UUID settlement) {
        return progress(player, settlement, QuestId.IRON_SHORTAGE);
    }

    public PlayerSettlementProgress progress(UUID player, UUID settlement, QuestId quest) {
        Objects.requireNonNull(quest, "quest");
        Relationship relationship = relationships.get(new RelationshipKey(player, settlement));
        if (relationship == null) return new PlayerSettlementProgress(QuestState.AVAILABLE, null, 0);
        AcceptedQuest accepted = relationship.quests.get(quest);
        return accepted == null
                ? new PlayerSettlementProgress(QuestState.AVAILABLE, null, relationship.reputation)
                : new PlayerSettlementProgress(accepted.state, accepted.terms, relationship.reputation);
    }

    public int reputation(UUID player, UUID settlement) {
        Relationship relationship = relationships.get(new RelationshipKey(player, settlement));
        return relationship == null ? 0 : relationship.reputation;
    }

    /** Compatibility entrypoint: a single player claims the entire party receipt. */
    public boolean awardEncounterReputationOnce(UUID party, UUID player, UUID settlement, int amount) {
        return awardEncounterReputationOnce(party, Set.of(player), settlement, amount);
    }

    /** All qualifying players are credited together; a later batch cannot append another reward. */
    public boolean awardEncounterReputationOnce(UUID party, Set<UUID> players, UUID settlement, int amount) {
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(settlement, "settlement");
        Set<UUID> recipients = Set.copyOf(players);
        if (recipients.isEmpty() || recipients.size() > 64) throw new IllegalArgumentException("Invalid encounter recipients");
        if (amount < 1 || amount > 1_000_000) throw new IllegalArgumentException("Invalid encounter reputation reward");
        if (encounterRewards.containsKey(party)) return false;
        // Preflight every addition before changing any player or receipt.
        Map<RelationshipKey, Integer> updates = new LinkedHashMap<>();
        for (UUID player : recipients) {
            RelationshipKey key = new RelationshipKey(player, settlement);
            Relationship relationship = relationships.get(key);
            updates.put(key, Math.addExact(relationship == null ? 0 : relationship.reputation, amount));
        }
        updates.forEach((key, value) -> relationships.computeIfAbsent(key, ignored -> new Relationship()).reputation = value);
        encounterRewards.put(party, new EncounterRewardReceipt(recipients, settlement, amount));
        setDirty();
        return true;
    }

    /** Retired parties cannot resolve deaths again; retain reputation, discard only obsolete receipts. */
    public void retainEncounterReceipts(Set<UUID> trackedParties) {
        if (encounterRewards.keySet().removeIf(id -> !trackedParties.contains(id))) setDirty();
    }

    public boolean hasEncounterReward(UUID party) {
        return encounterRewards.containsKey(Objects.requireNonNull(party, "party"));
    }

    public Optional<UUID> mainSettlement(UUID player) {
        ExpandedProgress progress = expandedPlayers.get(Objects.requireNonNull(player, "player"));
        return Optional.ofNullable(progress == null ? null : progress.mainSettlement);
    }

    /** The first allied settlement owns the player's main chain across the whole world. */
    public boolean anchorMain(UUID player, UUID settlement) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(settlement, "settlement");
        ExpandedProgress progress = expandedPlayers.get(player);
        if (progress != null && progress.mainSettlement != null) return progress.mainSettlement.equals(settlement);
        expandedPlayers.computeIfAbsent(player, ignored -> new ExpandedProgress()).mainSettlement = settlement;
        setDirty();
        return true;
    }

    public List<QuestInstance> quests(UUID player, UUID settlement) {
        Objects.requireNonNull(settlement, "settlement");
        ExpandedProgress progress = expandedPlayers.get(Objects.requireNonNull(player, "player"));
        return progress == null ? List.of() : progress.quests.values().stream()
                .filter(quest -> quest.source().settlementId().equals(settlement)).toList();
    }

    public Optional<QuestInstance> quest(UUID player, UUID id) {
        Objects.requireNonNull(id, "quest");
        ExpandedProgress progress = expandedPlayers.get(Objects.requireNonNull(player, "player"));
        return Optional.ofNullable(progress == null ? null : progress.quests.get(id));
    }

    public boolean offer(UUID player, QuestInstance quest) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(quest, "quest");
        if (quest.state() != QuestState.AVAILABLE) return false;
        ExpandedProgress progress = expandedPlayers.get(player);
        if (progress != null && progress.quests.containsKey(quest.id())) return false;
        QuestInstance replacement = null;
        if (quest.template().category() == QuestCategory.MAIN) {
            if (progress == null || !quest.source().settlementId().equals(progress.mainSettlement)
                    || !priorMainCompleted(progress, quest.template())) return false;
            for (QuestInstance previous : progress.quests.values()) {
                if (previous.template() != quest.template()) continue;
                if (quest.template() != QuestTemplate.MAIN_PATROL || previous.state() != QuestState.FAILED) return false;
                replacement = previous;
            }
        }
        int size = progress == null ? 0 : progress.quests.size();
        if (size >= MAX_EXPANDED_QUESTS && replacement == null) return false;
        if (progress == null) progress = expandedPlayers.computeIfAbsent(player, ignored -> new ExpandedProgress());
        if (replacement != null) {
            unindex(player, replacement);
            progress.quests.remove(replacement.id());
        }
        progress.quests.put(quest.id(), quest);
        setDirty();
        return true;
    }

    public boolean acceptExpanded(UUID player, UUID id) {
        QuestInstance quest = quest(player, id).orElse(null);
        if (quest == null || quest.state() != QuestState.AVAILABLE) return false;
        ExpandedProgress progress = expandedPlayers.get(player);
        if (quest.template().category() == QuestCategory.MAIN && !priorMainCompleted(progress, quest.template())) return false;
        QuestInstance accepted = quest.withState(QuestState.ACTIVE);
        progress.quests.put(id, accepted);
        index(player, accepted);
        setDirty();
        return true;
    }

    public boolean markObjective(UUID player, UUID id) {
        QuestInstance quest = quest(player, id).orElse(null);
        if (quest == null || quest.state() != QuestState.ACTIVE || quest.objectiveSatisfied()) return false;
        expandedPlayers.get(player).quests.put(id, quest.ready());
        unindex(player, quest);
        setDirty();
        return true;
    }

    /** Inventory/reward item validation is performed by the gameplay service before this atomic transition. */
    public boolean completeExpanded(UUID player, UUID id) {
        QuestInstance quest = quest(player, id).orElse(null);
        if (quest == null || quest.state() != QuestState.ACTIVE || !quest.objectiveSatisfied()) return false;
        RelationshipKey key = new RelationshipKey(player, quest.source().settlementId());
        Relationship relationship = relationships.get(key);
        int updated = Math.addExact(relationship == null ? 0 : relationship.reputation, quest.rewards().reputation());
        expandedPlayers.get(player).quests.put(id, quest.withState(QuestState.COMPLETED));
        relationships.computeIfAbsent(key, ignored -> new Relationship()).reputation = updated;
        unindex(player, quest);
        setDirty();
        return true;
    }

    public boolean failExpanded(UUID player, UUID id) {
        QuestInstance quest = quest(player, id).orElse(null);
        if (quest == null || (quest.state() != QuestState.ACTIVE && quest.state() != QuestState.AVAILABLE)
                || quest.objectiveSatisfied()) return false;
        expandedPlayers.get(player).quests.put(id, quest.withState(QuestState.FAILED));
        unindex(player, quest);
        setDirty();
        return true;
    }

    public long boardRefreshAt(UUID player, UUID settlement) {
        BoardRotation board = board(player, settlement);
        return board == null ? -1 : board.refreshedAt;
    }

    public long boardGeneration(UUID player, UUID settlement) {
        BoardRotation board = board(player, settlement);
        return board == null ? 0 : board.generation;
    }

    private BoardRotation board(UUID player, UUID settlement) {
        Objects.requireNonNull(settlement, "settlement");
        ExpandedProgress progress = expandedPlayers.get(Objects.requireNonNull(player, "player"));
        return progress == null ? null : progress.boards.get(settlement);
    }

    /** Preflight the full rotation before replacing offers or moving the saved cooldown. */
    public boolean rotateBoard(UUID player, UUID settlement, long now, long cooldown, List<QuestInstance> offers) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(settlement, "settlement");
        List<QuestInstance> checked = List.copyOf(offers);
        if (now < 0 || cooldown < 1 || checked.size() > 4) throw new IllegalArgumentException("Invalid quest board rotation");
        ExpandedProgress progress = expandedPlayers.get(player);
        BoardRotation previous = board(player, settlement);
        if (previous != null && (now < previous.refreshedAt || now - previous.refreshedAt < cooldown)) return false;
        long generation = Math.addExact(previous == null ? 0 : previous.generation, 1);
        if (previous == null && progress != null && progress.boards.size() >= MAX_BOARDS) return false;
        Set<UUID> ids = new HashSet<>();
        for (QuestInstance quest : checked) {
            if (quest.template().category() != QuestCategory.DYNAMIC || quest.state() != QuestState.AVAILABLE
                    || !quest.source().settlementId().equals(settlement) || !ids.add(quest.id())
                    || (progress != null && progress.quests.containsKey(quest.id()))) return false;
        }
        long retained = progress == null ? 0 : progress.quests.values().stream().filter(quest -> !rotatable(quest, settlement)).count();
        if (retained + checked.size() > MAX_EXPANDED_QUESTS) return false;
        if (progress == null) progress = expandedPlayers.computeIfAbsent(player, ignored -> new ExpandedProgress());
        progress.quests.values().removeIf(quest -> rotatable(quest, settlement));
        for (QuestInstance quest : checked) progress.quests.put(quest.id(), quest);
        progress.boards.put(settlement, new BoardRotation(now, generation));
        setDirty();
        return true;
    }

    public void expireOffers(UUID player, UUID settlement, long now) {
        if (now < 0) throw new IllegalArgumentException("Invalid expiration time");
        ExpandedProgress progress = expandedPlayers.get(Objects.requireNonNull(player, "player"));
        Objects.requireNonNull(settlement, "settlement");
        if (progress == null) return;
        boolean changed = false;
        for (Map.Entry<UUID, QuestInstance> entry : progress.quests.entrySet()) {
            QuestInstance quest = entry.getValue();
            if (quest.template().category() == QuestCategory.DYNAMIC && quest.state() == QuestState.AVAILABLE
                    && quest.source().settlementId().equals(settlement) && quest.expiresAt() <= now) {
                entry.setValue(quest.withState(QuestState.EXPIRED));
                changed = true;
            }
        }
        if (changed) setDirty();
    }

    /** Exact target UUID and the encounter system's validated participation are both required. */
    public void resolveParty(UUID party, Set<UUID> participants) {
        Objects.requireNonNull(party, "party");
        Set<UUID> eligible = Set.copyOf(participants);
        Set<QuestReference> references = partyQuests.remove(party);
        if (references == null) return;
        for (QuestReference reference : references) {
            if (eligible.contains(reference.player)) markObjective(reference.player, reference.quest);
            else failExpanded(reference.player, reference.quest);
        }
    }

    private static boolean priorMainCompleted(ExpandedProgress progress, QuestTemplate template) {
        for (QuestTemplate prerequisite : QuestTemplate.values()) {
            if (prerequisite.category() != QuestCategory.MAIN || prerequisite.order() >= template.order()) continue;
            boolean completed = progress.quests.values().stream().anyMatch(quest -> quest.template() == prerequisite
                    && quest.state() == QuestState.COMPLETED);
            if (!completed) return false;
        }
        return true;
    }

    private static boolean rotatable(QuestInstance quest, UUID settlement) {
        return quest.template().category() == QuestCategory.DYNAMIC && quest.state() != QuestState.ACTIVE
                && quest.source().settlementId().equals(settlement);
    }

    private void index(UUID player, QuestInstance quest) {
        if (quest.state() == QuestState.ACTIVE && !quest.objectiveSatisfied() && quest.objective() instanceof QuestObjective.Party party) {
            partyQuests.computeIfAbsent(party.partyId(), ignored -> new LinkedHashSet<>()).add(new QuestReference(player, quest.id()));
        }
    }

    private void unindex(UUID player, QuestInstance quest) {
        if (quest.objective() instanceof QuestObjective.Party party) {
            Set<QuestReference> references = partyQuests.get(party.partyId());
            if (references != null) {
                references.remove(new QuestReference(player, quest.id()));
                if (references.isEmpty()) partyQuests.remove(party.partyId());
            }
        }
    }

    /** Only AVAILABLE can become ACTIVE; accepted terms cannot be replaced by config changes. */
    public boolean accept(UUID player, UUID settlement, QuestTerms terms) {
        return accept(player, settlement, QuestId.IRON_SHORTAGE, terms);
    }

    public boolean accept(UUID player, UUID settlement, QuestId quest, QuestTerms terms) {
        Objects.requireNonNull(quest, "quest");
        Objects.requireNonNull(terms, "terms");
        RelationshipKey key = new RelationshipKey(player, settlement);
        Relationship relationship = relationships.get(key);
        if (relationship != null) {
            AcceptedQuest accepted = relationship.quests.get(quest);
            if (accepted != null && accepted.state != QuestState.AVAILABLE) return false;
        } else {
            relationship = new Relationship();
            relationships.put(key, relationship);
        }
        relationship.quests.put(quest, new AcceptedQuest(QuestState.ACTIVE, terms));
        setDirty();
        return true;
    }

    /** The gameplay service verifies inventory first; this transition grants reputation once. */
    public boolean complete(UUID player, UUID settlement) {
        return complete(player, settlement, QuestId.IRON_SHORTAGE);
    }

    public boolean complete(UUID player, UUID settlement, QuestId quest) {
        Objects.requireNonNull(quest, "quest");
        Relationship relationship = relationships.get(new RelationshipKey(player, settlement));
        if (relationship == null) return false;
        AcceptedQuest accepted = relationship.quests.get(quest);
        if (accepted == null || accepted.state != QuestState.ACTIVE) return false;
        int updatedReputation = Math.addExact(relationship.reputation, accepted.terms.reputationReward());
        relationship.quests.put(quest, new AcceptedQuest(QuestState.COMPLETED, accepted.terms));
        relationship.reputation = updatedReputation;
        setDirty();
        return true;
    }

    public boolean fail(UUID player, UUID settlement) {
        return fail(player, settlement, QuestId.IRON_SHORTAGE);
    }

    public boolean fail(UUID player, UUID settlement, QuestId quest) {
        Objects.requireNonNull(quest, "quest");
        Relationship relationship = relationships.get(new RelationshipKey(player, settlement));
        if (relationship == null) return false;
        AcceptedQuest accepted = relationship.quests.get(quest);
        if (accepted == null || accepted.state != QuestState.ACTIVE) return false;
        relationship.quests.put(quest, new AcceptedQuest(QuestState.FAILED, accepted.terms));
        setDirty();
        return true;
    }

    public Optional<UUID> mayor(UUID settlement) {
        return Optional.ofNullable(mayors.get(Objects.requireNonNull(settlement, "settlement")));
    }

    /** A settlement has one Mayor, and a Mayor cannot belong to two settlements. */
    public boolean associateMayor(UUID settlement, UUID entity) {
        Objects.requireNonNull(settlement, "settlement");
        Objects.requireNonNull(entity, "entity");
        UUID existing = mayors.get(settlement);
        if (existing != null) return existing.equals(entity);
        if (mayors.containsValue(entity)) return false;
        mayors.put(settlement, entity);
        setDirty();
        return true;
    }

    /** Roll back only the association written by a failed, synchronous establishment. */
    public void rollbackMayorAssociation(UUID settlement, UUID entity) {
        if (mayors.remove(settlement, entity)) setDirty();
    }

    public static QuestSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        require(tag, "schema_version", Tag.TAG_INT);
        int version = tag.getInt("schema_version");
        if (version < 1 || version > SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported Living Kingdoms quest schema: "
                    + tag.getInt("schema_version"));
        }
        QuestSavedData data = new QuestSavedData();
        ListTag entries = compoundList(tag, "player_settlements");
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            RelationshipKey key = new RelationshipKey(uuid(entry, "player"), uuid(entry, "settlement"));
            if (data.relationships.containsKey(key)) {
                throw new IllegalArgumentException("Duplicate player/settlement relationship: " + key);
            }
            require(entry, "reputation", Tag.TAG_INT);
            Relationship relationship = new Relationship();
            relationship.reputation = entry.getInt("reputation");
            ListTag quests = compoundList(entry, "quests");
            for (int q = 0; q < quests.size(); q++) {
                CompoundTag questTag = quests.getCompound(q);
                require(questTag, "quest_id", Tag.TAG_STRING);
                require(questTag, "state", Tag.TAG_STRING);
                QuestId quest = QuestId.fromId(questTag.getString("quest_id"));
                QuestState state = QuestState.valueOf(questTag.getString("state"));
                if (state == QuestState.EXPIRED) throw new IllegalArgumentException("Legacy quests cannot expire");
                QuestTerms terms = null;
                if (state == QuestState.AVAILABLE) {
                    if (questTag.contains("terms")) {
                        throw new IllegalArgumentException("Available quests cannot have accepted terms");
                    }
                } else {
                    require(questTag, "terms", Tag.TAG_COMPOUND);
                    CompoundTag termsTag = questTag.getCompound("terms");
                    require(termsTag, "required_iron", Tag.TAG_INT);
                    require(termsTag, "reward_emeralds", Tag.TAG_INT);
                    require(termsTag, "reputation_reward", Tag.TAG_INT);
                    terms = new QuestTerms(termsTag.getInt("required_iron"), termsTag.getInt("reward_emeralds"),
                            termsTag.getInt("reputation_reward"));
                }
                if (relationship.quests.putIfAbsent(quest, new AcceptedQuest(state, terms)) != null) {
                    throw new IllegalArgumentException("Duplicate quest for player/settlement: " + quest.id());
                }
            }
            data.relationships.put(key, relationship);
        }
        ListTag mayors = compoundList(tag, "mayors");
        for (int i = 0; i < mayors.size(); i++) {
            CompoundTag entry = mayors.getCompound(i);
            UUID settlement = uuid(entry, "settlement");
            UUID entity = uuid(entry, "entity");
            if (data.mayors.containsKey(settlement) || data.mayors.containsValue(entity)) {
                throw new IllegalArgumentException("Duplicate Mayor association");
            }
            data.mayors.put(settlement, entity);
        }
        if (version >= 2) {
            ListTag receipts = compoundList(tag, "encounter_rewards");
            for (int i = 0; i < receipts.size(); i++) {
                CompoundTag receipt = receipts.getCompound(i);
                UUID party = uuid(receipt, "party");
                Set<UUID> players = new HashSet<>();
                if (version == 2) {
                    players.add(uuid(receipt, "player"));
                } else {
                    ListTag recipients = compoundList(receipt, "players");
                    if (recipients.isEmpty() || recipients.size() > 64) throw new IllegalArgumentException("Invalid encounter recipients");
                    for (int p = 0; p < recipients.size(); p++) {
                        if (!players.add(uuid(recipients.getCompound(p), "player"))) throw new IllegalArgumentException("Duplicate encounter recipient");
                    }
                }
                UUID settlement = uuid(receipt, "settlement");
                require(receipt, "amount", Tag.TAG_INT);
                int amount = receipt.getInt("amount");
                if (amount < 1 || amount > 1_000_000
                        || players.stream().anyMatch(player -> !data.relationships.containsKey(new RelationshipKey(player, settlement)))) {
                    throw new IllegalArgumentException("Invalid encounter reward receipt");
                }
                if (data.encounterRewards.putIfAbsent(party, new EncounterRewardReceipt(Set.copyOf(players), settlement, amount)) != null) {
                    throw new IllegalArgumentException("Duplicate encounter reward receipt");
                }
            }
        }
        if (version >= 4) data.loadExpanded(tag);
        else if (tag.contains("expanded_players") && !compoundList(tag, "expanded_players").isEmpty()) {
            throw new IllegalArgumentException("Expanded quest data cannot be discarded by a legacy schema number");
        }
        data.setDirty(false);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schema_version", SCHEMA_VERSION);
        ListTag entries = new ListTag();
        relationships.forEach((key, relationship) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("player", key.player);
            entry.putUUID("settlement", key.settlement);
            entry.putInt("reputation", relationship.reputation);
            ListTag quests = new ListTag();
            relationship.quests.forEach((quest, accepted) -> {
                CompoundTag questTag = new CompoundTag();
                questTag.putString("quest_id", quest.id());
                questTag.putString("state", accepted.state.name());
                if (accepted.terms != null) {
                    CompoundTag terms = new CompoundTag();
                    terms.putInt("required_iron", accepted.terms.requiredIron());
                    terms.putInt("reward_emeralds", accepted.terms.rewardEmeralds());
                    terms.putInt("reputation_reward", accepted.terms.reputationReward());
                    questTag.put("terms", terms);
                }
                quests.add(questTag);
            });
            entry.put("quests", quests);
            entries.add(entry);
        });
        tag.put("player_settlements", entries);
        ListTag mayorEntries = new ListTag();
        mayors.forEach((settlement, entity) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("settlement", settlement);
            entry.putUUID("entity", entity);
            mayorEntries.add(entry);
        });
        tag.put("mayors", mayorEntries);
        ListTag receipts = new ListTag();
        encounterRewards.forEach((party, receipt) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("party", party);
            ListTag recipients = new ListTag();
            receipt.players.stream().sorted().forEach(player -> {
                CompoundTag recipient = new CompoundTag();
                recipient.putUUID("player", player);
                recipients.add(recipient);
            });
            entry.put("players", recipients);
            entry.putUUID("settlement", receipt.settlement);
            entry.putInt("amount", receipt.amount);
            receipts.add(entry);
        });
        tag.put("encounter_rewards", receipts);
        ListTag players = new ListTag();
        expandedPlayers.forEach((player, progress) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("player", player);
            if (progress.mainSettlement != null) entry.putUUID("main_settlement", progress.mainSettlement);
            ListTag quests = new ListTag();
            progress.quests.values().forEach(quest -> quests.add(ExpandedQuestNbt.write(quest)));
            entry.put("quests", quests);
            ListTag boards = new ListTag();
            progress.boards.forEach((settlement, rotation) -> {
                CompoundTag board = new CompoundTag();
                board.putUUID("settlement", settlement);
                board.putLong("refreshed_at", rotation.refreshedAt);
                board.putLong("generation", rotation.generation);
                boards.add(board);
            });
            entry.put("boards", boards);
            players.add(entry);
        });
        tag.put("expanded_players", players);
        return tag;
    }

    private void loadExpanded(CompoundTag tag) {
        ListTag players = compoundList(tag, "expanded_players");
        for (int p = 0; p < players.size(); p++) {
            CompoundTag entry = players.getCompound(p);
            UUID player = uuid(entry, "player");
            if (expandedPlayers.containsKey(player)) throw new IllegalArgumentException("Duplicate expanded quest player");
            ExpandedProgress progress = new ExpandedProgress();
            if (entry.contains("main_settlement")) progress.mainSettlement = uuid(entry, "main_settlement");
            ListTag quests = compoundList(entry, "quests");
            if (quests.size() > MAX_EXPANDED_QUESTS) throw new IllegalArgumentException("Too many expanded quests");
            Set<QuestTemplate> mainTemplates = new HashSet<>();
            for (int q = 0; q < quests.size(); q++) {
                QuestInstance quest = ExpandedQuestNbt.read(quests.getCompound(q));
                if (progress.quests.putIfAbsent(quest.id(), quest) != null) throw new IllegalArgumentException("Duplicate expanded quest UUID");
                if (quest.template().category() == QuestCategory.MAIN && (!quest.source().settlementId().equals(progress.mainSettlement)
                        || !mainTemplates.add(quest.template()))) throw new IllegalArgumentException("Invalid main quest anchor or duplicate step");
            }
            for (QuestInstance quest : progress.quests.values()) {
                if (quest.template().category() == QuestCategory.MAIN && !priorMainCompleted(progress, quest.template())) {
                    throw new IllegalArgumentException("Main quest prerequisites missing from save");
                }
            }
            ListTag boards = compoundList(entry, "boards");
            if (boards.size() > MAX_BOARDS) throw new IllegalArgumentException("Too many saved boards");
            for (int b = 0; b < boards.size(); b++) {
                CompoundTag board = boards.getCompound(b);
                require(board, "refreshed_at", Tag.TAG_LONG);
                require(board, "generation", Tag.TAG_LONG);
                long refreshedAt = board.getLong("refreshed_at");
                long generation = board.getLong("generation");
                if (refreshedAt < 0 || generation < 1) throw new IllegalArgumentException("Invalid saved board rotation");
                if (progress.boards.putIfAbsent(uuid(board, "settlement"), new BoardRotation(refreshedAt, generation)) != null) {
                    throw new IllegalArgumentException("Duplicate saved board settlement");
                }
            }
            expandedPlayers.put(player, progress);
            progress.quests.values().forEach(quest -> index(player, quest));
        }
    }

    private static UUID uuid(CompoundTag tag, String field) {
        if (!tag.hasUUID(field)) throw new IllegalArgumentException("Missing or invalid quest UUID: " + field);
        return tag.getUUID(field);
    }

    private static ListTag compoundList(CompoundTag tag, String field) {
        require(tag, field, Tag.TAG_LIST);
        ListTag entries = (ListTag) tag.get(field);
        if (!entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("Quest field must contain compounds: " + field);
        }
        return entries;
    }

    private static void require(CompoundTag tag, String field, int type) {
        if (!tag.contains(field, type)) throw new IllegalArgumentException("Missing or invalid quest field: " + field);
    }

    private record RelationshipKey(UUID player, UUID settlement) {
        private RelationshipKey {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(settlement, "settlement");
        }
    }

    private static final class Relationship {
        private int reputation;
        private final Map<QuestId, AcceptedQuest> quests = new LinkedHashMap<>();
    }

    private record AcceptedQuest(QuestState state, QuestTerms terms) {}
    private record EncounterRewardReceipt(Set<UUID> players, UUID settlement, int amount) {}
    private static final class ExpandedProgress {
        private UUID mainSettlement;
        private final Map<UUID, QuestInstance> quests = new LinkedHashMap<>();
        private final Map<UUID, BoardRotation> boards = new LinkedHashMap<>();
    }
    private record BoardRotation(long refreshedAt, long generation) {}
    private record QuestReference(UUID player, UUID quest) {}
}

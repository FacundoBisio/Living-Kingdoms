package dev.livingkingdoms.construction.persistence;

import dev.livingkingdoms.construction.domain.*;
import dev.livingkingdoms.structure.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.util.*;

/** Authoritative world receipts; derived deadline and chunk indexes never scan all projects per tick. */
public final class ConstructionSavedData extends SavedData {
    public static final String DATA_NAME = "livingkingdoms_construction";
    private static final Map<SettlementLayout,Boolean> validatedPlans = new IdentityHashMap<>();
    private static final Deque<SettlementLayout> validationOrder = new ArrayDeque<>();
    /** Immutable blueprints need their full transformed-cell audit once, not every credited work interval. */
    private static synchronized void validatePlan(SettlementLayout plan) {
        if(validatedPlans.containsKey(plan)) return;
        plan.validateGeometry();
        if(validationOrder.size()>=128) validatedPlans.remove(validationOrder.removeFirst());
        validatedPlans.put(plan,Boolean.TRUE); validationOrder.addLast(plan);
    }
    private final Map<UUID,Entry> entries = new LinkedHashMap<>();
    private final Map<UUID,List<UUID>> settlements = new HashMap<>();
    private final Map<UUID,UUID> builders = new HashMap<>();
    private final Map<UUID,Integer> buildingCounts = new HashMap<>();
    private final LinkedHashSet<UUID> pending = new LinkedHashSet<>();
    private final PriorityQueue<Deadline> due = new PriorityQueue<>(Comparator.comparingLong(Deadline::at));
    private final Map<UUID,Long> scheduled = new HashMap<>();
    private final Set<UUID> blockedSites = new HashSet<>();
    private final Map<ChunkKey,Set<UUID>> waiting = new HashMap<>();
    private final LinkedHashSet<UUID> awakened = new LinkedHashSet<>();
    private final Map<ChunkKey,Map<BlockPos,UUID>> markers = new HashMap<>();
    public record Entry(ConstructionProject project, SettlementLayout plan) {
        public Entry {
            validatePlan(plan);
            if (plan.buildings().size()!=1) throw new IllegalArgumentException("One building per project");
            var b=plan.buildings().getFirst(); var p=project.plot();
            if(b.module().kind()!=project.building() || !b.origin().equals(new BlockPos(p.x(),p.y(),p.z())) || !b.rotation().name().equals(p.rotation().name()))
                throw new IllegalArgumentException("Project and blueprint differ");
        }
        public BlockPos marker() { return plan.buildings().getFirst().origin().above(); }
    }
    private record Deadline(UUID id,long at) {}
    private record ChunkKey(String dimension,long chunk) {}
    public static ConstructionSavedData get(MinecraftServer server) {
        if(!server.isSameThread()) throw new IllegalStateException("Construction requires the server thread");
        var directory=server.getWorldPath(LevelResource.ROOT).resolve("data");
        return getOrCreate(server.overworld().getDataStorage(),directory);
    }
    public static ConstructionSavedData getOrCreate(net.minecraft.world.level.storage.DimensionDataStorage storage,java.nio.file.Path directory) {
        return storage.computeIfAbsent(new Factory<>(() -> {
            if(!Files.notExists(directory.resolve(DATA_NAME+".dat"))) throw new IllegalStateException("Existing construction data failed to load; refusing to overwrite it");
            return new ConstructionSavedData();
        },ConstructionSavedData::load),DATA_NAME);
    }
    public Optional<Entry> get(UUID id) { return Optional.ofNullable(entries.get(id)); }
    public List<Entry> projects(UUID settlement) { return settlements.getOrDefault(settlement,List.of()).stream().map(entries::get).toList(); }
    public int buildingCount(UUID settlement) { return buildingCounts.getOrDefault(settlement,0); }
    /** Settlement FIFO, bounded by its 64 retained projects. Includes Builder replacements. */
    public List<Entry> readyProjects(UUID settlement) { return projects(settlement).stream().filter(e -> e.project.state()==ConstructionState.READY).toList(); }
    public Optional<Entry> forBuilder(UUID builder) { return Optional.ofNullable(builders.get(builder)).map(entries::get); }
    /** Round-robin settlements with funded queued projects; no world/settlement scan. */
    public List<UUID> pendingSettlements(int limit) {
        if(limit<0) throw new IllegalArgumentException("Invalid queue limit");
        List<UUID> result=new ArrayList<>(); int count=Math.min(limit,pending.size());
        for(int i=0;i<count;i++) { var it=pending.iterator(); UUID id=it.next(); it.remove(); result.add(id); pending.add(id); }
        return result;
    }
    public List<PlotBounds> reservations(UUID settlement) {
        return projects(settlement).stream().filter(e -> e.project.state()!=ConstructionState.COMPLETED).map(e -> e.plan.buildings().getFirst().bounds()).toList();
    }
    public Optional<UUID> marker(String dimension,BlockPos pos) {
        return Optional.ofNullable(markers.getOrDefault(new ChunkKey(dimension,ChunkPos.asLong(pos.getX()>>4,pos.getZ()>>4)),Map.of()).get(pos));
    }
    public void reserve(ConstructionProject project,SettlementLayout plan) {
        Entry entry=new Entry(project,plan);
        if(entries.containsKey(project.id()) || entries.size()>=16384 || projects(project.settlementId()).size()>=64)
            throw new IllegalArgumentException("Duplicate project or construction storage limit");
        requireFreeBuilder(project);
        PlotBounds bounds=plan.buildings().getFirst().bounds();
        if(projects(project.settlementId()).stream().anyMatch(e -> e.plan.buildings().getFirst().bounds().conflicts(bounds,0))) throw new IllegalArgumentException("Reserved plot collision");
        entries.put(project.id(),entry); settlements.computeIfAbsent(project.settlementId(),ignored -> new ArrayList<>()).add(project.id()); index(entry); setDirty();
    }
    public boolean replace(ConstructionProject expected,ConstructionProject replacement) {
        if(!expected.id().equals(replacement.id()) || !expected.settlementId().equals(replacement.settlementId()) || !expected.plot().equals(replacement.plot())
                || expected.building()!=replacement.building() || !expected.required().equals(replacement.required())
                || expected.durationTicks()!=replacement.durationTicks() || expected.createdAt()!=replacement.createdAt()
                || expected.builderRequired()!=replacement.builderRequired()) throw new IllegalArgumentException("Project blueprint cannot change");
        Entry old=entries.get(expected.id()); if(old==null || !old.project.equals(expected)) return false;
        validateTransition(expected,replacement); requireFreeBuilder(replacement);
        if(expected.equals(replacement)) return false;
        unindex(old); Entry next=new Entry(replacement,old.plan); entries.put(expected.id(),next); index(next); refreshPending(expected.settlementId()); setDirty(); return true;
    }
    /** Only for a failed synchronous founding transaction. */
    public void removeUnstarted(ConstructionProject expected) {
        Entry entry=entries.get(expected.id());
        if(entry==null || !entry.project.equals(expected) || expected.state()!=ConstructionState.WAITING_FOR_RESOURCES || !expected.supplied().isEmpty())
            throw new IllegalStateException("Project cannot roll back");
        unindex(entry); entries.remove(expected.id()); settlements.get(expected.settlementId()).remove(expected.id()); refreshPending(expected.settlementId()); setDirty();
    }

    /** Only for a failed synchronous founding transaction. */
    public void rollback(UUID settlement) {
        for(Entry e:projects(settlement)) { unindex(e); entries.remove(e.project.id()); }
        settlements.remove(settlement); pending.remove(settlement); setDirty();
    }
    private void requireFreeBuilder(ConstructionProject p) {
        if(p.state()!=ConstructionState.BUILDING || p.builderId()==null) return;
        UUID owner=builders.get(p.builderId());
        if(owner!=null && !owner.equals(p.id())) throw new IllegalArgumentException("Builder already owns an active project");
    }
    private static void validateTransition(ConstructionProject old,ConstructionProject next) {
        boolean valid=switch(old.state()) {
            case PLANNED -> next.state()==ConstructionState.WAITING_FOR_RESOURCES;
            case WAITING_FOR_RESOURCES -> next.state()==ConstructionState.WAITING_FOR_RESOURCES || next.state()==ConstructionState.READY
                    || next.state()==ConstructionState.BUILDING && next.funded();
            case READY -> next.state()==ConstructionState.BUILDING || next.state()==ConstructionState.READY && old.builderRequired()
                    || next.state()==ConstructionState.COMPLETED;
            case BUILDING -> next.state()==ConstructionState.BUILDING || next.state()==ConstructionState.READY && old.builderRequired()
                    || next.state()==ConstructionState.FAILED || next.state()==ConstructionState.COMPLETED;
            case FAILED -> next.state()==(old.builderRequired() ? ConstructionState.READY : ConstructionState.BUILDING)
                    || next.state()==ConstructionState.FAILED && old.builderRequired() || next.state()==ConstructionState.COMPLETED;
            case COMPLETED -> next.state()==ConstructionState.COMPLETED;
        };
        if(!valid || old.startedAt()>=0 && old.startedAt()!=next.startedAt() || next.workTicks()<old.workTicks()
                || next.lastWorkAt()<old.lastWorkAt() || next.visualStage()<old.visualStage()
                || (next.awardedStages() & old.awardedStages())!=old.awardedStages()
                || old.supplied().entrySet().stream().anyMatch(e -> next.supplied().getOrDefault(e.getKey(),0)<e.getValue())
                || old.state()==ConstructionState.COMPLETED && (!Objects.equals(old.builderId(),next.builderId())
                    || old.completedAt()!=next.completedAt() || old.workTicks()!=next.workTicks() || old.lastWorkAt()!=next.lastWorkAt()))
            throw new IllegalArgumentException("Construction receipt cannot regress or rewrite its history");
        if(old.state()==ConstructionState.BUILDING && next.state()==ConstructionState.BUILDING && !Objects.equals(old.builderId(),next.builderId()))
            throw new IllegalArgumentException("Release the existing Builder before reassignment");
    }
    private void refreshPending(UUID settlement) {
        if(readyProjects(settlement).isEmpty()) pending.remove(settlement); else pending.add(settlement);
    }
    private void index(Entry e) {
        var p=e.project; if(p.state()==ConstructionState.COMPLETED) return;
        if(p.state()==ConstructionState.READY) pending.add(p.settlementId());
        var key=chunk(e.plan.territory().dimension(),e.marker()); markers.computeIfAbsent(key,ignored -> new HashMap<>()).put(e.marker(),p.id());
        if(p.state()!=ConstructionState.BUILDING) return;
        buildingCounts.merge(p.settlementId(),1,Integer::sum);
        if(p.builderId()!=null) builders.put(p.builderId(),p.id());
        if(p.builderRequired()) return; // Physical work resumes through its citizen, never an elapsed deadline.
        if(p.awaitingChunks()) chunks(e).forEach(c -> waiting.computeIfAbsent(c,ignored -> new HashSet<>()).add(p.id()));
        else scheduleCheck(p.id(),p.deadline());
    }
    private void unindex(Entry e) {
        awakened.remove(e.project.id());
        scheduled.remove(e.project.id());
        blockedSites.remove(e.project.id());
        if(e.project.state()==ConstructionState.BUILDING) {
            buildingCounts.computeIfPresent(e.project.settlementId(),(id,count) -> count==1 ? null : count-1);
            if(e.project.builderId()!=null) builders.remove(e.project.builderId(),e.project.id());
        }
        var key=chunk(e.plan.territory().dimension(),e.marker()); var map=markers.get(key);
        if(map!=null) { map.remove(e.marker()); if(map.isEmpty()) markers.remove(key); }
        if(e.project.awaitingChunks()) for(var c:chunks(e)) { var ids=waiting.get(c); if(ids!=null) { ids.remove(e.project.id()); if(ids.isEmpty()) waiting.remove(c); } }
        // Deadlines are removed lazily on state comparison, avoiding a linear priority-queue search.
    }
    private Set<ChunkKey> chunks(Entry e) { Set<ChunkKey> result=new HashSet<>(); e.plan.before().keySet().forEach(pos -> result.add(chunk(e.plan.territory().dimension(),pos))); return result; }
    private static ChunkKey chunk(String dimension,BlockPos pos) { return new ChunkKey(dimension,ChunkPos.asLong(pos.getX()>>4,pos.getZ()>>4)); }
    public void chunkLoaded(String dimension,ChunkPos pos) { awakened.addAll(waiting.getOrDefault(new ChunkKey(dimension,pos.toLong()),Set.of())); }
    public void scheduleCheck(UUID id,long at) {
        var e=entries.get(id);
        if(e==null || e.project.state()!=ConstructionState.BUILDING || e.project.awaitingChunks() || e.project.builderRequired()) throw new IllegalStateException("Project cannot be scheduled");
        scheduled.put(id,at); due.add(new Deadline(id,at));
    }
    public void scheduleSiteRetry(UUID id,long now) {
        var e=entries.get(id); if(e==null) throw new IllegalArgumentException("Unknown project");
        scheduleCheck(id,Math.max(e.project.deadline(),now+100)); blockedSites.add(id);
    }
    public boolean waitingForClearSite(UUID id) { return blockedSites.contains(id); }
    public List<UUID> ready(long now,int limit) {
        List<UUID> result=new ArrayList<>();
        while(!awakened.isEmpty() && result.size()<limit) { var it=awakened.iterator(); UUID id=it.next(); it.remove(); result.add(id); }
        int inspected=0;
        while(!due.isEmpty() && due.peek().at<=now && result.size()<limit && inspected++<64) {
            Deadline d=due.remove(); Entry e=entries.get(d.id);
            if(e!=null && e.project.state()==ConstructionState.BUILDING && !e.project.awaitingChunks() && scheduled.getOrDefault(d.id,-1L)==d.at && !result.contains(d.id)) {
                scheduled.remove(d.id); result.add(d.id);
            }
        } return result;
    }
    public static ConstructionSavedData load(CompoundTag tag,HolderLookup.Provider registries) {
        ConstructionNbt.require(tag,"schema_version",Tag.TAG_INT);
        int schema=tag.getInt("schema_version"); if(schema<1 || schema>2) throw new IllegalArgumentException("Unsupported construction schema");
        ConstructionSavedData data=new ConstructionSavedData(); var list=ConstructionNbt.compounds(tag,"projects",16384);
        for(int i=0;i<list.size();i++) {
            CompoundTag t=list.getCompound(i); ConstructionNbt.require(t,"project",Tag.TAG_COMPOUND); ConstructionNbt.require(t,"plan",Tag.TAG_COMPOUND);
            var p=ConstructionNbt.readProject(t.getCompound("project"),schema); data.reserve(p,ConstructionNbt.readPlan(t.getCompound("plan"),registries));
            if(p.awaitingChunks() && !p.builderRequired()) data.awakened.add(p.id()); // One initial legacy loaded-chunk check after restart.
        } data.setDirty(false); return data;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        tag.putInt("schema_version",2); ListTag list=new ListTag();
        entries.values().forEach(e -> { CompoundTag t=new CompoundTag(); t.put("project",ConstructionNbt.writeProject(e.project)); t.put("plan",ConstructionNbt.writePlan(e.plan)); list.add(t); });
        tag.put("projects",list); return tag;
    }
}

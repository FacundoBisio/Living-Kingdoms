package dev.livingkingdoms.construction.domain;

import dev.livingkingdoms.quest.expansion.domain.ResourceKind;
import dev.livingkingdoms.structure.BuildingKind;
import java.util.Map;
import java.util.EnumMap;
import java.util.Objects;
import java.util.UUID;

/** Settlement-owned receipt. Legacy projects use game time; new expansion uses credited Builder work. */
public record ConstructionProject(UUID id, UUID settlementId, BuildingKind building, Plot plot,
        ConstructionState state, Map<ResourceKind, Integer> required, Map<ResourceKind, Integer> supplied,
        long durationTicks, long createdAt, long startedAt, long completedAt, boolean awaitingChunks,
        boolean builderRequired, UUID builderId, long workTicks, long lastWorkAt, int visualStage, int awardedStages) {
    public enum Orientation { NONE, CLOCKWISE_90, CLOCKWISE_180, COUNTERCLOCKWISE_90 }
    public record Plot(int x, int y, int z, Orientation rotation) {
        public Plot { Objects.requireNonNull(rotation); }
    }
    /** Source/save compatibility: existing projects retain their funded materials and original deadline. */
    public ConstructionProject(UUID id, UUID settlementId, BuildingKind building, Plot plot,
            ConstructionState state, Map<ResourceKind,Integer> required, Map<ResourceKind,Integer> supplied,
            long durationTicks, long createdAt, long startedAt, long completedAt, boolean awaitingChunks) {
        this(id, settlementId, building, plot, state, required, supplied, durationTicks, createdAt,
                startedAt, completedAt, awaitingChunks, false, null, 0, -1,
                state == ConstructionState.COMPLETED ? 4 : -1, 0);
    }
    public ConstructionProject {
        Objects.requireNonNull(id); Objects.requireNonNull(settlementId); Objects.requireNonNull(building);
        Objects.requireNonNull(plot); Objects.requireNonNull(state);
        required = Map.copyOf(required); supplied = Map.copyOf(supplied);
        validateMaterials(required, supplied);
        if (durationTicks < 20 || durationTicks > 1728000 || createdAt < 0 || startedAt < -1 || completedAt < -1
                || startedAt >= 0 && startedAt < createdAt || completedAt >= 0 && completedAt < startedAt
                || (state == ConstructionState.BUILDING || state == ConstructionState.COMPLETED) && (startedAt < 0 || !satisfied(required, supplied))
                || state == ConstructionState.COMPLETED && completedAt < 0 || state != ConstructionState.COMPLETED && completedAt != -1
                || awaitingChunks && state != ConstructionState.BUILDING || state == ConstructionState.READY && !satisfied(required, supplied))
            throw new IllegalArgumentException("Invalid construction timestamps/state");
        if ((state == ConstructionState.PLANNED || state == ConstructionState.WAITING_FOR_RESOURCES) && startedAt != -1
                || state == ConstructionState.READY && !builderRequired && startedAt != -1
                || state == ConstructionState.FAILED && (startedAt < 0 || !satisfied(required, supplied))
                || startedAt > Long.MAX_VALUE - durationTicks)
            throw new IllegalArgumentException("Invalid construction start");
        if (workTicks < 0 || workTicks > durationTicks || lastWorkAt < -1 || lastWorkAt >= 0 && lastWorkAt < startedAt
                || visualStage < -1 || visualStage > 4 || awardedStages < 0 || awardedStages > 31
                || !builderRequired && (builderId != null || workTicks != 0 || lastWorkAt != -1 || awardedStages != 0)
                || builderRequired && state == ConstructionState.BUILDING && builderId == null
                || (state == ConstructionState.PLANNED || state == ConstructionState.WAITING_FOR_RESOURCES || state == ConstructionState.READY) && builderId != null
                || startedAt < 0 && (workTicks != 0 || lastWorkAt != -1 || visualStage != -1 || awardedStages != 0)
                || state == ConstructionState.COMPLETED && (visualStage != 4 || builderRequired && workTicks != durationTicks)
                || state != ConstructionState.COMPLETED && visualStage == 4 && (!builderRequired || workTicks < durationTicks)
                || builderRequired && visualStage >= 0 && visualStage > workTicks * 4 / durationTicks
                || builderRequired && completedAt >= 0 && completedAt < lastWorkAt
                || visualStage < 0 && awardedStages != 0 || visualStage >= 0 && (awardedStages >>> (visualStage + 1)) != 0)
            throw new IllegalArgumentException("Invalid Builder work/stage receipt");
        if (building == BuildingKind.CORE || building == BuildingKind.FOUNDING_CAMP)
            throw new IllegalArgumentException("Construction projects are permanent building additions");
    }
    public static ConstructionProject planned(UUID settlement, BuildingKind building, Plot plot, Map<ResourceKind,Integer> cost, long duration, long now) {
        return new ConstructionProject(UUID.randomUUID(), settlement, building, plot, ConstructionState.PLANNED, cost, Map.of(), duration, now, -1, -1, false);
    }
    public ConstructionProject requiringBuilder() {
        if (state != ConstructionState.PLANNED || startedAt >= 0) throw new IllegalStateException("Only a new project can require a Builder");
        return receipt(state, supplied, startedAt, completedAt, awaitingChunks, true, null, workTicks, lastWorkAt, visualStage, awardedStages);
    }
    private static boolean satisfied(Map<ResourceKind,Integer> required, Map<ResourceKind,Integer> supplied) {
        return required.entrySet().stream().allMatch(e -> supplied.getOrDefault(e.getKey(), 0).equals(e.getValue()));
    }
    private static void validateMaterials(Map<ResourceKind,Integer> required, Map<ResourceKind,Integer> supplied) {
        if (required.isEmpty() || required.values().stream().anyMatch(n -> n < 1 || n > 2304)
                || supplied.entrySet().stream().anyMatch(e -> !required.containsKey(e.getKey()) || e.getValue() < 0 || e.getValue() > required.get(e.getKey())))
            throw new IllegalArgumentException("Invalid construction materials");
    }
    public int missing(ResourceKind resource) { return required.getOrDefault(resource, 0) - supplied.getOrDefault(resource, 0); }
    public boolean funded() { return satisfied(required, supplied); }
    public ConstructionProject waitForResources() {
        if (state != ConstructionState.PLANNED) throw new IllegalStateException("Project already reserved");
        return copy(ConstructionState.WAITING_FOR_RESOURCES, supplied, -1, -1, false);
    }
    public ConstructionProject supply(Map<ResourceKind,Integer> delivery) {
        if (state != ConstructionState.WAITING_FOR_RESOURCES) throw new IllegalStateException("Project is not accepting resources");
        Map<ResourceKind,Integer> updated = new EnumMap<>(ResourceKind.class); updated.putAll(supplied);
        delivery.forEach((kind, count) -> {
            if (count < 1 || count > missing(kind)) throw new IllegalArgumentException("Delivery exceeds remaining requirements");
            updated.merge(kind, count, Integer::sum);
        });
        return copy(satisfied(required, updated) ? ConstructionState.READY : state, updated, -1, -1, false);
    }
    public ConstructionProject start(long now) {
        if (builderRequired) throw new IllegalStateException("Project requires an assigned Builder");
        return start(now, null);
    }
    public ConstructionProject start(long now, UUID builder) {
        if (state != ConstructionState.READY || now < createdAt || builderRequired && builder == null || !builderRequired && builder != null)
            throw new IllegalStateException("Project is not ready for this Builder");
        return receipt(ConstructionState.BUILDING, supplied, startedAt < 0 ? now : startedAt, -1, false,
                builderRequired, builder, workTicks, builderRequired ? Math.max(now, lastWorkAt) : lastWorkAt, visualStage, awardedStages);
    }
    public ConstructionProject releaseBuilder() {
        if (!builderRequired || state != ConstructionState.BUILDING) throw new IllegalStateException("Project has no active Builder");
        return receipt(ConstructionState.READY, supplied, startedAt, -1, false, true, null, workTicks, lastWorkAt, visualStage, awardedStages);
    }
    /** A persisted tick receipt prevents duplicate work from two callers in the same interval. */
    public ConstructionProject addWork(long now, long units) {
        if (!builderRequired || state != ConstructionState.BUILDING || now < startedAt || units < 1)
            throw new IllegalStateException("Project cannot receive Builder work");
        if (now <= lastWorkAt) return this;
        long updated = workTicks + Math.min(units, durationTicks - workTicks);
        return receipt(state, supplied, startedAt, -1, false, true, builderId, updated, now, visualStage, awardedStages);
    }
    public long remainingWork() { return state == ConstructionState.COMPLETED ? 0 : durationTicks - workTicks; }
    /** Visual stage records successful placement, not merely the target implied by work. */
    public ConstructionProject stagePlaced(int stage) {
        if (!builderRequired || state != ConstructionState.BUILDING || stage < visualStage || stage < 0 || stage > targetStage())
            throw new IllegalStateException("Construction stage cannot be placed");
        return receipt(state, supplied, startedAt, -1, awaitingChunks, true, builderId, workTicks, lastWorkAt, stage, awardedStages);
    }
    public int targetStage() {
        if (state == ConstructionState.COMPLETED) return 4;
        return builderRequired ? (int)Math.min(4, workTicks * 4 / durationTicks) : 0;
    }
    public int targetStage(long now) { return builderRequired ? targetStage() : (int)Math.min(4,Math.floor(progress(now)*4)); }
    public boolean stageXpAwarded(int stage) { return stage >= 0 && stage <= 4 && (awardedStages & (1 << stage)) != 0; }
    public ConstructionProject awardStageXp(int stage) {
        if (!builderRequired || stage < 0 || stage > visualStage || stage > 4) throw new IllegalStateException("No placed milestone to reward");
        if (stageXpAwarded(stage)) return this;
        return receipt(state, supplied, startedAt, completedAt, awaitingChunks, true, builderId, workTicks, lastWorkAt, visualStage, awardedStages | (1 << stage));
    }
    public boolean due(long now) { return state == ConstructionState.BUILDING && now >= startedAt && (builderRequired ? workTicks >= durationTicks : now - startedAt >= durationTicks); }
    /** Legacy deadline only; Builder project ETAs must use remainingWork and current work speed. */
    public long deadline() { return Math.addExact(startedAt, durationTicks); }
    public double progress(long now) { return state == ConstructionState.COMPLETED ? 1 : builderRequired ? (double)workTicks / durationTicks : startedAt < 0 ? 0 : Math.clamp((double)(now - startedAt) / durationTicks, 0, 1); }
    public ConstructionProject defer(long now) {
        if (!due(now)) throw new IllegalStateException("Construction is not due");
        return copy(state, supplied, startedAt, -1, true);
    }
    public ConstructionProject complete(long now) {
        if (!due(now)) throw new IllegalStateException("Construction is not due");
        return receipt(ConstructionState.COMPLETED, supplied, startedAt, now, false, builderRequired, builderId, workTicks, lastWorkAt, 4, awardedStages);
    }
    public ConstructionProject fail() {
        if (state != ConstructionState.BUILDING) throw new IllegalStateException("Only placement can fail");
        return copy(ConstructionState.FAILED, supplied, startedAt, -1, false);
    }
    public ConstructionProject resume() {
        if (state != ConstructionState.BUILDING) throw new IllegalStateException("Project is not building");
        return copy(state, supplied, startedAt, -1, false);
    }
    public ConstructionProject retry() {
        if (state != ConstructionState.FAILED || !funded()) throw new IllegalStateException("Project cannot retry");
        return builderRequired ? receipt(ConstructionState.READY, supplied, startedAt, -1, false, true, null, workTicks, lastWorkAt, visualStage, awardedStages)
                : copy(ConstructionState.BUILDING, supplied, startedAt, -1, false);
    }
    /** Operator-only completion; survival completion checks credited work or the legacy deadline. */
    public ConstructionProject debugComplete(long now) {
        if ((state != ConstructionState.BUILDING && state != ConstructionState.READY && state != ConstructionState.FAILED)
                || !funded() || now < Math.max(createdAt,startedAt)) throw new IllegalStateException("Project is not funded");
        return receipt(ConstructionState.COMPLETED, supplied, startedAt<0 ? now : startedAt, now, false, builderRequired, builderId,
                builderRequired ? durationTicks : workTicks, builderRequired ? Math.max(now, lastWorkAt) : lastWorkAt, 4, builderRequired ? 31 : awardedStages);
    }
    /** Operator-only visual/work advance; all traversed milestones consume their XP receipts without rewards. */
    public ConstructionProject debugAdvance(long now,int stage) {
        if(!builderRequired) {
            if(state!=ConstructionState.BUILDING || now<startedAt || stage<0 || stage>3 || stage<visualStage)
                throw new IllegalStateException("Legacy project cannot debug advance");
            return receipt(state,supplied,startedAt,-1,awaitingChunks,false,null,workTicks,lastWorkAt,stage,0);
        }
        if (!builderRequired || (state!=ConstructionState.READY && state!=ConstructionState.BUILDING && state!=ConstructionState.FAILED)
                || !funded() || now<Math.max(createdAt,startedAt) || stage<0 || stage>4 || stage<visualStage)
            throw new IllegalStateException("Project cannot debug advance");
        long work=Math.max(workTicks,(durationTicks*stage+3)/4);
        int receipts=awardedStages | ((1 << (stage+1))-1);
        return receipt(state,supplied,startedAt<0 ? now : startedAt,-1,false,true,builderId,work,Math.max(now,lastWorkAt),stage,receipts);
    }
    private ConstructionProject copy(ConstructionState next, Map<ResourceKind,Integer> materials, long start, long end, boolean pending) {
        return receipt(next, materials, start, end, pending, builderRequired, builderId, workTicks, lastWorkAt, visualStage, awardedStages);
    }
    private ConstructionProject receipt(ConstructionState next, Map<ResourceKind,Integer> materials, long start, long end, boolean pending,
            boolean requiresBuilder, UUID builder, long work, long receiptAt, int stage, int rewarded) {
        return new ConstructionProject(id, settlementId, building, plot, next, required, materials, durationTicks, createdAt, start, end,
                pending, requiresBuilder, builder, work, receiptAt, stage, rewarded);
    }
}

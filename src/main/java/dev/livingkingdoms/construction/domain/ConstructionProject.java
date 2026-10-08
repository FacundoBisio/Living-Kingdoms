package dev.livingkingdoms.construction.domain;

import dev.livingkingdoms.quest.expansion.domain.ResourceKind;
import dev.livingkingdoms.structure.BuildingKind;
import java.util.Map;
import java.util.EnumMap;
import java.util.Objects;
import java.util.UUID;

/** Settlement-owned receipt. Progress uses persistent server game time, never wall-clock time. */
public record ConstructionProject(UUID id, UUID settlementId, BuildingKind building, Plot plot,
        ConstructionState state, Map<ResourceKind, Integer> required, Map<ResourceKind, Integer> supplied,
        long durationTicks, long createdAt, long startedAt, long completedAt, boolean awaitingChunks) {
    public enum Orientation { NONE, CLOCKWISE_90, CLOCKWISE_180, COUNTERCLOCKWISE_90 }
    public record Plot(int x, int y, int z, Orientation rotation) {
        public Plot { Objects.requireNonNull(rotation); }
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
        if ((state == ConstructionState.PLANNED || state == ConstructionState.WAITING_FOR_RESOURCES || state == ConstructionState.READY) && startedAt != -1
                || state == ConstructionState.FAILED && (startedAt < 0 || !satisfied(required, supplied))
                || startedAt > Long.MAX_VALUE - durationTicks)
            throw new IllegalArgumentException("Invalid construction start");
        if (building == BuildingKind.CORE || building == BuildingKind.FOUNDING_CAMP)
            throw new IllegalArgumentException("Construction projects are permanent building additions");
    }
    public static ConstructionProject planned(UUID settlement, BuildingKind building, Plot plot, Map<ResourceKind,Integer> cost, long duration, long now) {
        return new ConstructionProject(UUID.randomUUID(), settlement, building, plot, ConstructionState.PLANNED, cost, Map.of(), duration, now, -1, -1, false);
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
        if (state != ConstructionState.READY || now < createdAt) throw new IllegalStateException("Project is not ready");
        return copy(ConstructionState.BUILDING, supplied, now, -1, false);
    }
    public boolean due(long now) { return state == ConstructionState.BUILDING && now >= startedAt && now - startedAt >= durationTicks; }
    public long deadline() { return Math.addExact(startedAt, durationTicks); }
    public double progress(long now) { return state == ConstructionState.COMPLETED ? 1 : startedAt < 0 ? 0 : Math.clamp((double)(now - startedAt) / durationTicks, 0, 1); }
    public ConstructionProject defer(long now) {
        if (!due(now)) throw new IllegalStateException("Construction is not due");
        return copy(state, supplied, startedAt, -1, true);
    }
    public ConstructionProject complete(long now) {
        if (!due(now)) throw new IllegalStateException("Construction is not due");
        return copy(ConstructionState.COMPLETED, supplied, startedAt, now, false);
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
        return copy(ConstructionState.BUILDING, supplied, startedAt, -1, false);
    }
    /** Operator-only completion; survival completion always checks the saved deadline. */
    public ConstructionProject debugComplete(long now) {
        if (state != ConstructionState.BUILDING || now < startedAt) throw new IllegalStateException("Project is not building");
        return copy(ConstructionState.COMPLETED, supplied, startedAt, now, false);
    }
    private ConstructionProject copy(ConstructionState next, Map<ResourceKind,Integer> materials, long start, long end, boolean pending) {
        return new ConstructionProject(id, settlementId, building, plot, next, required, materials, durationTicks, createdAt, start, end, pending);
    }
}

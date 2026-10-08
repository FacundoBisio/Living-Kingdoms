package dev.livingkingdoms.structure;

import java.util.EnumMap;
import java.util.Map;

public final class GenerationDiagnostics {
    public enum Rejection { EXCESSIVE_SLOPE, WATER, UNLOADED_CHUNKS, SETTLEMENT_OVERLAP,
        INSUFFICIENT_PLOTS, INSUFFICIENT_CORE_AREA, WORLD_BORDER, TERRITORY, OBSTACLE, ENTITIES, UNSUPPORTED_GROUND, PATH }

    private int bestPlots;
    public void validPlots(int count) { bestPlots = Math.max(bestPlots, count); }
    private int centers;
    private int plots;
    private final Map<Rejection, Integer> centerFailures = new EnumMap<>(Rejection.class);
    private final Map<Rejection, Integer> plotFailures = new EnumMap<>(Rejection.class);

    public void centerChecked() { centers++; }
    public void plotChecked() { plots++; }
    public void rejectCenter(Rejection reason) { centerFailures.merge(reason, 1, Integer::sum); }
    public void rejectPlot(Rejection reason) { plotFailures.merge(reason, 1, Integer::sum); }
    public Summary summary() { return new Summary(centers, plots, bestPlots, Map.copyOf(centerFailures), Map.copyOf(plotFailures)); }

    public record Summary(int centersChecked, int plotsChecked, int bestValidPlots, Map<Rejection, Integer> centerFailures,
                          Map<Rejection, Integer> plotFailures) {
        public String feedbackKey() {
            if (centerFailures.getOrDefault(Rejection.SETTLEMENT_OVERLAP, 0) == centersChecked && centersChecked > 0)
                return "commands.livingkingdoms.settlement.overlap";
            if (bestValidPlots > 0 && bestValidPlots < 4) return "commands.livingkingdoms.settlement.crowded";
            if (centerFailures.getOrDefault(Rejection.UNLOADED_CHUNKS, 0) == centersChecked && centersChecked > 0)
                return "commands.livingkingdoms.settlement.unloaded";
            return "commands.livingkingdoms.settlement.no_safe_site";
        }
        public Summary {
            centerFailures = Map.copyOf(centerFailures);
            plotFailures = Map.copyOf(plotFailures);
        }
    }
}

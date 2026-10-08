package dev.livingkingdoms.structure;

import dev.livingkingdoms.config.SettlementGenerationConfig.Tolerance;
import dev.livingkingdoms.settlement.domain.Territory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LayoutRulesTest {
    @Test void generationFeedbackDistinguishesCrowdingAndUnloadedTerrain() {
        var plots = new GenerationDiagnostics();
        plots.centerChecked(); plots.validPlots(2); plots.validPlots(1);
        plots.rejectCenter(GenerationDiagnostics.Rejection.INSUFFICIENT_PLOTS);
        assertEquals(2, plots.summary().bestValidPlots());
        assertEquals("commands.livingkingdoms.settlement.crowded", plots.summary().feedbackKey());
        var unloaded = new GenerationDiagnostics();
        unloaded.centerChecked(); unloaded.rejectCenter(GenerationDiagnostics.Rejection.UNLOADED_CHUNKS);
        assertEquals("commands.livingkingdoms.settlement.unloaded", unloaded.summary().feedbackKey());
    }

    @Test void spacingUsesWholeFootprintsIncludingCorners() {
        var hall = new PlotBounds(-6, -9, 6, 3);
        assertTrue(hall.conflicts(new PlotBounds(7, 1, 11, 5), 2));
        assertTrue(hall.conflicts(new PlotBounds(8, 5, 12, 9), 2));
        assertFalse(hall.conflicts(new PlotBounds(9, 6, 13, 10), 2));
        assertTrue(hall.conflicts(new PlotBounds(-2, -3, 2, 1), 0));
    }

    @Test void circularTerritoryProtectsAllFourCorners() {
        var territory = new Territory("minecraft:overworld", 0, 72, 0, 10);
        assertTrue(new PlotBounds(-6, -6, 6, 6).inside(territory));
        assertFalse(new PlotBounds(5, 5, 9, 9).inside(territory));
    }

    @Test void tolerancesCannotAllowMajorCliffsOrDeepSupports() {
        assertThrows(IllegalArgumentException.class, () -> new Tolerance(10, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> new Tolerance(2, 0, 2));
        assertEquals(3, new Tolerance(3, 1, 3).maxVariance());
    }

    @Test void diagnosticsKeepCenterAndLocalFailuresSeparateAndImmutable() {
        var diagnostics = new GenerationDiagnostics();
        diagnostics.centerChecked();
        diagnostics.plotChecked(); diagnostics.plotChecked();
        diagnostics.rejectPlot(GenerationDiagnostics.Rejection.WATER);
        diagnostics.rejectPlot(GenerationDiagnostics.Rejection.WATER);
        var summary = diagnostics.summary();
        diagnostics.rejectCenter(GenerationDiagnostics.Rejection.INSUFFICIENT_PLOTS);
        assertEquals(1, summary.centersChecked());
        assertEquals(2, summary.plotsChecked());
        assertEquals(2, summary.plotFailures().get(GenerationDiagnostics.Rejection.WATER));
        assertTrue(summary.centerFailures().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> summary.plotFailures().clear());
    }
}

package autodrillnext.testsupport;

import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.model.DiagnosticCode;
import autodrillnext.solver.ExitFeasibilitySolver;
import autodrillnext.solver.ExitResolution;
import autodrillnext.world.ExitPort;
import autodrillnext.world.OrePatch;
import autodrillnext.world.OrePatchAnalyzer;
import autodrillnext.world.TileState;
import autodrillnext.world.TerrainSnapshot;
import autodrillnext.world.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Map;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenshotWorldScenarioTest {

    @Test
    void downwardExitRejectsTheBoundaryInsteadOfSelectingTheOpenUpperSide() {
        WorldSnapshot world = ScreenshotWorldScenario.world();
        OrePatch patch = new OrePatchAnalyzer().analyze(world, ScreenshotWorldScenario.seed());
        TerrainSnapshot terrain = TerrainSnapshot.of(world);
        CapabilitySnapshot capabilities = new CapabilitySnapshot(Map.of());
        ExitPort bottom = ExitPort.forPreference(ExitPort.Side.BOTTOM, ExitPort.Bias.CENTER, patch);
        ExitResolution resolution = new ExitFeasibilitySolver().resolve(bottom, terrain, capabilities);
        String trace = trace(patch, bottom, world, resolution);

        assertEquals(patch.minY() - 1, bottom.anchors().get(0).tile().y(), trace);
        assertFalse(resolution.resolved(), trace);
        assertEquals(DiagnosticCode.NO_VALID_SINK, resolution.diagnostics().get(0).code(), trace);

        ExitPort top = ExitPort.forPreference(ExitPort.Side.TOP, ExitPort.Bias.CENTER, patch);
        ExitResolution upper = new ExitFeasibilitySolver().resolve(top, terrain, capabilities);
        assertTrue(upper.resolved(), trace);
        assertEquals(patch.maxY() + 1, upper.anchor().tile().y(), trace);
    }

    private String trace(OrePatch patch, ExitPort exit, WorldSnapshot world, ExitResolution resolution) {
        String anchors = exit.anchors().stream()
            .map(anchor -> anchor.tile() + "=" + state(world.tile(anchor.tile())))
            .collect(Collectors.joining(", "));
        return "downward-exit patch=" + patch.minX() + ".." + patch.maxX() + " x " + patch.minY() + ".." + patch.maxY()
            + "; port=" + exit.id() + "; anchors=[" + anchors + "]; resolved=" + resolution.resolved()
            + "; diagnostics=" + resolution.diagnostics();
    }

    private String state(TileState tile) {
        if (tile == null) return "outside";
        if (tile.solid()) return "solid";
        if (tile.deepLiquid()) return "deep-liquid";
        if (tile.fogged()) return "fogged";
        if (tile.isReservedByExistingBuild()) return "occupied";
        return "open";
    }
}

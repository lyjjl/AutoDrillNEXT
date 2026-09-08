package autodrillnext.solver;

import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.model.ContentId;
import autodrillnext.model.DiagnosticCode;
import autodrillnext.model.PlannerDiagnostic;
import autodrillnext.world.ExitAnchor;
import autodrillnext.world.ExitPort;
import autodrillnext.world.OrePatch;
import autodrillnext.world.TerrainRevision;
import autodrillnext.world.TerrainSnapshot;
import autodrillnext.world.TileKey;
import autodrillnext.world.TileOffset;
import autodrillnext.world.TileState;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExitFeasibilitySolverTest {
    @Test
    void eachSideHasThreeBiasesForTwelveExitPreferences() {
        OrePatch patch = patch();

        assertEquals(12, ExitPort.allPreferences(patch).size());
    }

    @Test
    void blockedFirstAnchorFallsThroughTheSameGoalRegion() {
        ExitAnchor blocked = new ExitAnchor(new TileKey(6, 2), ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, 0);
        ExitAnchor available = new ExitAnchor(new TileKey(7, 2), ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, 1);
        ExitPort port = new ExitPort("right-center", ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, List.of(blocked, available));
        TerrainSnapshot terrain = TerrainSnapshot.of(Map.of(
            blocked.tile(), new TileState(false, true, false, null, null, null, null),
            available.tile(), new TileState(false, false, false, null, null, null, null)
        ), TerrainRevision.of(1));

        ExitResolution result = new ExitFeasibilitySolver().resolve(
            port,
            terrain,
            new CapabilitySnapshot(Map.of())
        );

        assertTrue(result.resolved());
        assertEquals(available.tile(), result.anchor().tile());
    }

    @Test
    void noValidAnchorReturnsStableDiagnostic() {
        TileKey only = new TileKey(7, 2);
        ExitPort port = new ExitPort(
            "right-center",
            ExitPort.Side.RIGHT,
            ExitPort.Bias.CENTER,
            List.of(new ExitAnchor(only, ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, 0))
        );
        TerrainSnapshot terrain = TerrainSnapshot.of(Map.of(
            only, new TileState(true, false, false, null, null, null, null)
        ), TerrainRevision.of(1));

        ExitResolution result = new ExitFeasibilitySolver().resolve(
            port,
            terrain,
            new CapabilitySnapshot(Map.of())
        );

        assertTrue(result.unresolved());
        assertEquals(
            DiagnosticCode.NO_VALID_SINK,
            result.diagnostics().get(0).code()
        );
        assertEquals("7,2=fogged", result.diagnostics().get(0).arguments().get("anchors"));
        assertEquals("7..7 x 2..2", result.diagnostics().get(0).arguments().get("snapshot"));
    }

    private OrePatch patch() {
        Set<TileOffset> cells = Set.of(
            new TileOffset(0, 0), new TileOffset(1, 0), new TileOffset(2, 0),
            new TileOffset(0, 1), new TileOffset(1, 1), new TileOffset(2, 1),
            new TileOffset(0, 2), new TileOffset(1, 2), new TileOffset(2, 2)
        );
        return OrePatch.of(ContentId.of("mindustry:copper"), new TileKey(4, 1), cells);
    }
}

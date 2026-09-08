package autodrillnext.compile;

import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.model.ContentId;
import autodrillnext.model.DiagnosticCode;
import autodrillnext.model.EdgeKind;
import autodrillnext.model.ExistingNetwork;
import autodrillnext.model.FlowAssignment;
import autodrillnext.model.PlanEdge;
import autodrillnext.model.PlanGraph;
import autodrillnext.model.PlanNode;
import autodrillnext.model.Inventory;
import autodrillnext.world.TerrainRevision;
import autodrillnext.world.TerrainSnapshot;
import autodrillnext.world.TileKey;
import autodrillnext.world.TileState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FinalValidatorTest {
    @Test
    void liveTerrainChangesAbortPreviouslyValidGraph() {
        PlanNode source = PlanNode.source("source", 1f, new TileKey(0, 0));
        PlanNode sink = PlanNode.sink("sink", new TileKey(2, 0));
        PlanEdge edge = PlanEdge.transport("belt", EdgeKind.GROUND_EDGE, "source", "sink", 1f, 1f,
            autodrillnext.capability.spec.CostVector.empty(), "belt").withFootprint(Set.of(new TileKey(1, 0)));
        PlanGraph graph = PlanGraph.of(List.of(source, sink), List.of(edge), "sink")
            .withFlow(new FlowAssignment(1f, Map.of("belt", 1f), Map.of("belt", 1f), true));
        TerrainSnapshot terrain = TerrainSnapshot.of(Map.of(
            new TileKey(1, 0), new TileState(false, true, false, null, null, null, null)
        ), TerrainRevision.of(2));

        ValidationResult result = new FinalValidator().validate(
            graph,
            new LiveSnapshot(terrain, Inventory.empty(), new CapabilitySnapshot(Map.of()), true)
        );

        assertFalse(result.valid());
        assertEquals(DiagnosticCode.TERRAIN_OR_RULE_BLOCKED, result.diagnostics().get(0).code());
        assertEquals("1,0", result.diagnostics().get(0).arguments().get("tile"));
        assertEquals("solid", result.diagnostics().get(0).arguments().get("reason"));
    }
}

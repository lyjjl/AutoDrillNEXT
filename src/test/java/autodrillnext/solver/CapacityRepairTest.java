package autodrillnext.solver;

import autodrillnext.capability.spec.CostVector;
import autodrillnext.model.ContentId;
import autodrillnext.model.FlowAssignment;
import autodrillnext.model.PlanEdge;
import autodrillnext.model.EdgeKind;
import autodrillnext.model.PlanGraph;
import autodrillnext.model.PlanNode;
import autodrillnext.model.PlannerRequest;
import autodrillnext.world.ExitPort;
import autodrillnext.world.OrePatch;
import autodrillnext.world.TileKey;
import autodrillnext.world.TileOffset;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapacityRepairTest {
    @Test
    void twoCheapLanesBeatOneExpensiveLaneWhenSpaceAllows() {
        PlanNode source = PlanNode.source("source", 9f, new TileKey(0, 0));
        PlanNode trunk = PlanNode.junction("trunk", new TileKey(1, 0));
        PlanNode sink = PlanNode.sink("sink", new TileKey(2, 0));
        PlanGraph base = PlanGraph.of(
            List.of(source, trunk, sink),
            List.of(
                PlanEdge.source("source-feed", "source", "trunk", 9f),
                PlanEdge.transport("expensive", EdgeKind.GROUND_EDGE, "trunk", "sink", 10f, 10f,
                    CostVector.of(java.util.Map.of(autodrillnext.model.ItemId.of("thorium"), 20)), "expensive")
            ),
            "sink"
        );
        PlanGraph cheapLanes = PlanGraph.of(
            List.of(source, trunk, sink),
            List.of(
                PlanEdge.source("source-feed", "source", "trunk", 9f),
                PlanEdge.transport("cheap-a", EdgeKind.GROUND_EDGE, "trunk", "sink", 5f, 5f, CostVector.empty(), "cheap"),
                PlanEdge.transport("cheap-b", EdgeKind.GROUND_EDGE, "trunk", "sink", 5f, 5f, CostVector.empty(), "cheap")
            ),
            "sink"
        );
        PlanGraph choices = base.withAlternatives(List.of(cheapLanes));
        PlannerRequest request = PlannerRequest.defaults(
            new TileKey(0, 0),
            "sharded",
            ExitPort.forPreference(
                ExitPort.Side.RIGHT,
                ExitPort.Bias.CENTER,
                OrePatch.of(ContentId.of("mindustry:copper"), new TileKey(0, 0), Set.of(new TileOffset(0, 0)))
            )
        );

        FlowAssignment initial = new FlowSolver().assign(base);
        PlanGraph repaired = new CapacityRepair().repair(choices, initial, request);

        assertEquals(List.of("cheap"), repaired.transportIds());
        assertTrue(repaired.qOut() >= 9f);
    }
}

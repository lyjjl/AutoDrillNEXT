package autodrillnext.solver;

import autodrillnext.capability.spec.CostVector;
import autodrillnext.model.ContentId;
import autodrillnext.model.FlowAssignment;
import autodrillnext.model.PlanEdge;
import autodrillnext.model.PlanGraph;
import autodrillnext.model.EdgeKind;
import autodrillnext.model.PlanNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowSolverTest {
    @Test
    void fixedTopologyMaxFlowRespectsSharedCapacityAndReportsUtilization() {
        PlanNode first = PlanNode.source("first", 4f, new autodrillnext.world.TileKey(0, 0));
        PlanNode second = PlanNode.source("second", 4f, new autodrillnext.world.TileKey(0, 1));
        PlanNode trunk = PlanNode.junction("trunk", new autodrillnext.world.TileKey(1, 0));
        PlanNode sink = PlanNode.sink("sink", new autodrillnext.world.TileKey(2, 0));
        PlanGraph graph = PlanGraph.of(
            List.of(first, second, trunk, sink),
            List.of(
                PlanEdge.source("first-source", "first", "trunk", 4f),
                PlanEdge.source("second-source", "second", "trunk", 4f),
                PlanEdge.transport("shared", EdgeKind.GROUND_EDGE, "trunk", "sink", 5f, 5f, CostVector.empty(), "cheap")
            ),
            "sink"
        );

        FlowAssignment flow = new FlowSolver().assign(graph);

        assertEquals(5f, flow.qOut());
        assertEquals(5f, flow.flow("shared"));
        assertEquals(1f, flow.utilization("shared"));
        assertTrue(flow.capacitySafe());
    }
}

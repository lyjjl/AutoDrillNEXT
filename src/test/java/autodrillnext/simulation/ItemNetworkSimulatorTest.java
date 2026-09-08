package autodrillnext.simulation;

import autodrillnext.capability.spec.CostVector;
import autodrillnext.model.ContentId;
import autodrillnext.model.EdgeKind;
import autodrillnext.model.PlanEdge;
import autodrillnext.model.PlanGraph;
import autodrillnext.model.PlanNode;
import autodrillnext.world.TileKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemNetworkSimulatorTest {
    @Test
    void sustainedTransportBottleneckReducesOutputAndBackpressuresDrill() {
        SimulationResult result = new ItemNetworkSimulator().simulate(network(4f, 1f));

        assertEquals(1f, result.steadyStateQout(), 0.05f);
        assertTrue(result.drillBlockedRatio() > 0.70f, () -> "blocked=" + result.drillBlockedRatio());
        assertTrue(result.queueOccupancy() > 0f, () -> "occupancy=" + result.queueOccupancy());
        assertTrue(result.steadyStateDetected());
    }

    @Test
    void sufficientTransportPreservesStaticOutputWithoutBackpressure() {
        SimulationResult result = new ItemNetworkSimulator().simulate(network(4f, 4f));

        assertEquals(4f, result.steadyStateQout(), 0.05f);
        assertEquals(0f, result.drillBlockedRatio(), 0.0001f);
        assertTrue(result.steadyStateDetected());
    }

    @Test
    void terminalConveyorDeliversDirectlyIntoSink() {
        PlanGraph graph = PlanGraph.of(List.of(
            PlanNode.source("source", 3f, new TileKey(0, 0), ContentId.of("copper")),
            PlanNode.junction("belt", new TileKey(1, 0)),
            PlanNode.sink("sink", new TileKey(2, 0))
        ), List.of(
            PlanEdge.source("feed", "source", "belt", 3f),
            PlanEdge.transport("terminal", EdgeKind.GROUND_EDGE, "belt", "sink", 5f, 5f,
                CostVector.empty(), "conveyor")
        ), "sink");
        assertEquals(3f, new ItemNetworkSimulator().simulate(graph).steadyStateQout(), 0.01f);
    }

    private PlanGraph network(float productionPerSecond, float transportPerSecond) {
        PlanNode source = PlanNode.source("source", productionPerSecond, new TileKey(0, 0), ContentId.of("mindustry:copper"));
        PlanNode junction = PlanNode.junction("junction", new TileKey(1, 0));
        PlanNode outlet = PlanNode.junction("outlet", new TileKey(2, 0));
        PlanNode sink = PlanNode.sink("sink", new TileKey(3, 0));
        return PlanGraph.of(
            List.of(source, junction, outlet, sink),
            List.of(
                PlanEdge.source("source-feed", "source", "junction", productionPerSecond),
                PlanEdge.transport("conveyor", EdgeKind.GROUND_EDGE, "junction", "outlet", transportPerSecond,
                    transportPerSecond, CostVector.empty(), "test:conveyor"),
                PlanEdge.sink("sink-edge", "outlet", "sink", Float.MAX_VALUE / 4f)
            ),
            "sink"
        );
    }
}

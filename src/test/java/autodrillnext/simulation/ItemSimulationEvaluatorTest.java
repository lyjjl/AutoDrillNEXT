package autodrillnext.simulation;

import autodrillnext.capability.spec.CapabilityDescriptor;
import autodrillnext.capability.spec.CapabilityKind;
import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.CapabilityState;
import autodrillnext.capability.spec.CostVector;
import autodrillnext.capability.spec.ItemTransportSpec;
import autodrillnext.model.ContentId;
import autodrillnext.model.EdgeKind;
import autodrillnext.model.PlanEdge;
import autodrillnext.model.PlanGraph;
import autodrillnext.model.PlanNode;
import autodrillnext.world.TileKey;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemSimulationEvaluatorTest {
    @Test
    void modeledTransportProducesBoundedDynamicResult() {
        ContentId conveyor = ContentId.of("test:conveyor");
        SimulationResult result = new ItemSimulationEvaluator().evaluate(network(conveyor), capabilities(
            conveyor, SimulationFidelity.BOUNDED_MODEL
        ));

        assertEquals(SimulationFidelity.BOUNDED_MODEL, result.fidelity());
        assertTrue(result.steadyStateDetected());
        assertEquals(1f, result.steadyStateQout(), 0.05f);
    }

    @Test
    void unsupportedTransportDoesNotProduceDynamicClaim() {
        ContentId conveyor = ContentId.of("test:conveyor");
        SimulationResult result = new ItemSimulationEvaluator().evaluate(network(conveyor), capabilities(
            conveyor, SimulationFidelity.UNSUPPORTED
        ));

        assertEquals(SimulationFidelity.UNSUPPORTED, result.fidelity());
        assertEquals(0, result.simulatedTicks());
        assertFalse(result.steadyStateDetected());
    }

    private CapabilitySnapshot capabilities(ContentId id, SimulationFidelity fidelity) {
        ItemTransportSpec spec = new ItemTransportSpec(id, 1, 1f, 0, false, CostVector.empty(), fidelity);
        CapabilityDescriptor descriptor = new CapabilityDescriptor(
            id,
            CapabilityKind.ITEM_TRANSPORT,
            EnumSet.of(CapabilityState.AVAILABLE_NOW),
            Set.of(),
            CostVector.empty(),
            "test",
            spec
        );
        return new CapabilitySnapshot(Map.of(id, descriptor));
    }

    private PlanGraph network(ContentId conveyor) {
        return PlanGraph.of(
            List.of(
                PlanNode.source("source", 1f, new TileKey(0, 0), ContentId.of("mindustry:copper")),
                PlanNode.junction("outlet", new TileKey(1, 0)),
                PlanNode.sink("sink", new TileKey(2, 0))
            ),
            List.of(
                PlanEdge.transport("conveyor", EdgeKind.GROUND_EDGE, "source", "outlet", 1f, 1f,
                    CostVector.empty(), conveyor.value()),
                PlanEdge.sink("sink-edge", "outlet", "sink", Float.MAX_VALUE / 4f)
            ),
            "sink"
        );
    }
}

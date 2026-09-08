package autodrillnext.simulation;

import autodrillnext.capability.spec.CapabilityDescriptor;
import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.ItemTransportSpec;
import autodrillnext.model.ContentId;
import autodrillnext.model.EdgeKind;
import autodrillnext.model.PlanEdge;
import autodrillnext.model.PlanGraph;
import autodrillnext.model.SearchBudget;

import java.util.Objects;

/** Selects a safe shadow-model fidelity from an immutable capability snapshot. */
public final class ItemSimulationEvaluator {
    private final ItemNetworkSimulator simulator;

    public ItemSimulationEvaluator() {
        this(new ItemNetworkSimulator());
    }

    ItemSimulationEvaluator(ItemNetworkSimulator simulator) {
        this.simulator = Objects.requireNonNull(simulator, "simulator");
    }

    public SimulationResult evaluate(PlanGraph graph, CapabilitySnapshot capabilities) {
        return evaluate(graph, capabilities, SearchBudget.unlimited());
    }

    public SimulationResult evaluate(
        PlanGraph graph,
        CapabilitySnapshot capabilities,
        SearchBudget budget
    ) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(budget, "budget").checkpoint();
        for (PlanEdge edge : graph.edges()) {
            if (edge.kind() == EdgeKind.EXISTING_EDGE) return SimulationResult.unsupported();
            if (edge.kind() != EdgeKind.GROUND_EDGE && edge.kind() != EdgeKind.BRIDGE_EDGE) continue;
            CapabilityDescriptor descriptor = capabilities.descriptors().get(ContentId.of(edge.transportId()));
            if (descriptor == null || !(descriptor.spec() instanceof ItemTransportSpec transport)
                || transport.simulationFidelity() == SimulationFidelity.UNSUPPORTED) {
                return SimulationResult.unsupported();
            }
        }
        return simulator.simulate(graph, SimulationFidelity.BOUNDED_MODEL, budget);
    }
}

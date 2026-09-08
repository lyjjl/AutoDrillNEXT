package autodrillnext.solver;

import autodrillnext.model.MiningLayout;
import autodrillnext.model.ObjectiveValue;
import autodrillnext.model.PlanGraph;
import autodrillnext.model.ServiceBundle;
import autodrillnext.simulation.SimulationResult;

import java.util.Objects;

/** One complete, feasible plan admitted as a search incumbent. */
public record EvaluatedMiningPlan(
    MiningLayout layout,
    PlanGraph graph,
    SupportPlan support,
    ServiceBundle bundle,
    SimulationResult simulation,
    ObjectiveValue objective,
    boolean proofEligible
) {
    public EvaluatedMiningPlan {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(support, "support");
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(simulation, "simulation");
        Objects.requireNonNull(objective, "objective");
        if (!support.feasible() || graph.flow() == null || !graph.flow().capacitySafe()) {
            throw new IllegalArgumentException("evaluated plan must be fully feasible");
        }
    }
}

package autodrillnext.solver;

import autodrillnext.model.MiningLayout;
import autodrillnext.model.OptimalityCertificate;
import autodrillnext.model.PlanGraph;
import autodrillnext.model.ObjectiveValue;
import autodrillnext.model.ServiceBundle;
import autodrillnext.model.PlannerDiagnostic;
import autodrillnext.simulation.SimulationResult;

import java.util.List;

/** Selected feasible lower-bound plan and its bounded-search proof. */
public record OptimalPlanningResult(
    MiningLayout layout,
    PlanGraph graph,
    SupportPlan support,
    ServiceBundle bundle,
    SimulationResult simulation,
    ObjectiveValue objective,
    List<ServiceBundle> frontierBundles,
    OptimalityCertificate certificate,
    List<PlannerDiagnostic> diagnostics
) {
    public OptimalPlanningResult {
        if (certificate == null) throw new NullPointerException("certificate");
        frontierBundles = List.copyOf(frontierBundles);
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean hasPlan() {
        return layout != null && graph != null && support != null && support.feasible()
            && bundle != null && objective != null;
    }
}

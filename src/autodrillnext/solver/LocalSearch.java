package autodrillnext.solver;

import autodrillnext.model.DrillCandidate;
import autodrillnext.model.MiningLayout;
import autodrillnext.model.PlannerRequest;

import java.util.List;
import java.util.function.ToDoubleFunction;

public final class LocalSearch {
    private final MiningLayoutSolver solver = new MiningLayoutSolver();

    public MiningLayout improve(
        MiningLayout layout,
        List<DrillCandidate> candidates,
        PlannerRequest request,
        ToDoubleFunction<MiningLayout> score
    ) {
        return solver.improve(layout, candidates, request, score);
    }
}

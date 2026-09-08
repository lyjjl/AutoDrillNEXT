package autodrillnext.solver;

import autodrillnext.model.BudgetSnapshot;
import autodrillnext.model.FlowAssignment;
import autodrillnext.model.ObjectiveValue;
import autodrillnext.model.PlanGraph;
import autodrillnext.model.PlannerRequest;
import java.util.ArrayList;

public final class CapacityRepair {
    private final FlowSolver flowSolver = new FlowSolver();

    public PlanGraph repair(PlanGraph graph, FlowAssignment initial, PlannerRequest request) {
        return repair(graph, initial, request, null);
    }

    public PlanGraph repair(
        PlanGraph graph,
        FlowAssignment initial,
        PlannerRequest request,
        BudgetSnapshot budget
    ) {
        ArrayList<PlanGraph> choices = new ArrayList<>();
        choices.add(graph);
        choices.addAll(graph.alternatives());

        if (budget != null && !budget.infiniteResources()) {
            ArrayList<PlanGraph> affordable = new ArrayList<>();
            for (PlanGraph choice : choices) {
                if (choice.cost().materials().componentWiseAtMost(budget.inventory())) {
                    affordable.add(choice);
                }
            }
            if (!affordable.isEmpty()) choices = affordable;
        }

        PlanGraph bestGraph = null;
        FlowAssignment bestFlow = null;
        for (PlanGraph choice : choices) {
            FlowAssignment flow = flowSolver.assign(choice);
            if (bestGraph == null || better(choice, flow, bestGraph, bestFlow, request)) {
                bestGraph = choice;
                bestFlow = flow;
            }
        }
        return bestGraph.withFlow(bestFlow);
    }

    private boolean better(
        PlanGraph candidate,
        FlowAssignment candidateFlow,
        PlanGraph best,
        FlowAssignment bestFlow,
        PlannerRequest request
    ) {
        int byQout = Long.compare(ObjectiveValue.outputRank(candidateFlow.qOut()), ObjectiveValue.outputRank(bestFlow.qOut()));
        if (byQout != 0) return byQout > 0;

        int byMaterials = Double.compare(candidate.cost().materials().economicValue(), best.cost().materials().economicValue());
        if (byMaterials != 0) return byMaterials < 0;
        int bySpace = Integer.compare(candidate.cost().space(), best.cost().space());
        if (bySpace != 0) return bySpace < 0;
        int byComplexity = Integer.compare(candidate.cost().complexity(), best.cost().complexity());
        if (byComplexity != 0) return byComplexity < 0;
        return String.join("|", candidate.transportIds()).compareTo(String.join("|", best.transportIds())) < 0;
    }
}

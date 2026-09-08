package autodrillnext.solver;

import autodrillnext.compile.FinalValidator;
import autodrillnext.compile.LiveSnapshot;
import autodrillnext.compile.ValidationResult;
import autodrillnext.model.BudgetSnapshot;
import autodrillnext.model.DependencyKind;
import autodrillnext.model.DiagnosticCode;
import autodrillnext.model.DrillCandidate;
import autodrillnext.model.EdgeKind;
import autodrillnext.model.MiningLayout;
import autodrillnext.model.ObjectiveValue;
import autodrillnext.model.PatchSearchScope;
import autodrillnext.model.PlanCost;
import autodrillnext.model.PlanEdge;
import autodrillnext.model.PlanGraph;
import autodrillnext.model.PlanObjectiveOrder;
import autodrillnext.model.PlannerDiagnostic;
import autodrillnext.model.PlannerRequest;
import autodrillnext.model.SearchBudget;
import autodrillnext.model.ServiceBundle;
import autodrillnext.simulation.ItemSimulationEvaluator;
import autodrillnext.simulation.SimulationFidelity;
import autodrillnext.simulation.SimulationResult;
import autodrillnext.world.TileKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The only admission path from a complete layout/transport pair to a search incumbent. */
public final class PlanCandidateEvaluator {
    private static final float EPSILON = 0.0001f;

    private final FlowSolver flowSolver;
    private final SupportPlanner supportPlanner;
    private final FinalValidator validator;
    private final BudgetPlanner budgetPlanner;
    private final ItemSimulationEvaluator simulationEvaluator;

    public PlanCandidateEvaluator() {
        this(new FlowSolver(), new SupportPlanner(), new FinalValidator(), new BudgetPlanner(),
            new ItemSimulationEvaluator());
    }

    PlanCandidateEvaluator(
        FlowSolver flowSolver,
        SupportPlanner supportPlanner,
        FinalValidator validator,
        BudgetPlanner budgetPlanner,
        ItemSimulationEvaluator simulationEvaluator
    ) {
        this.flowSolver = Objects.requireNonNull(flowSolver, "flow solver");
        this.supportPlanner = Objects.requireNonNull(supportPlanner, "support planner");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.budgetPlanner = Objects.requireNonNull(budgetPlanner, "budget planner");
        this.simulationEvaluator = Objects.requireNonNull(simulationEvaluator, "simulation evaluator");
    }

    public Evaluation evaluate(
        MiningLayout layout,
        PlanGraph graph,
        PatchSearchScope scope,
        SearchBudget budget
    ) {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(budget, "budget").checkpoint();
        LinkedHashSet<PlannerDiagnostic> diagnostics = new LinkedHashSet<>(graph.diagnostics());
        if (!diagnostics.isEmpty()) return new Evaluation(List.of(), List.copyOf(diagnostics));

        PlanGraph flowed = graph.withTransportHeadroom(scope.request().transportHeadroom());
        flowed = flowed.withFlow(flowSolver.assign(flowed, budget));
        if (flowed.qOut() <= EPSILON || !flowed.flow().capacitySafe()
            || layout.candidates().size() > 1
                && flowed.qOut() + EPSILON < transportProduction(layout)) {
            diagnostics.add(PlannerDiagnostic.of(DiagnosticCode.NO_ROUTE));
            return new Evaluation(List.of(), List.copyOf(diagnostics));
        }

        List<SupportPlan> supports = supportPlanner.solveAll(
            layout,
            flowed,
            scope.terrain(),
            scope.capabilities(),
            scope.existingNetwork(),
            scope.selectedLiquidId(),
            budget
        );
        if (supports.isEmpty()) {
            SupportPlan failure = supportPlanner.solve(
                layout,
                flowed,
                scope.terrain(),
                scope.capabilities(),
                scope.existingNetwork(),
                scope.selectedLiquidId(),
                budget
            );
            diagnostics.addAll(failure.diagnostics());
            return new Evaluation(List.of(), List.copyOf(diagnostics));
        }

        ArrayList<EvaluatedMiningPlan> plans = new ArrayList<>();
        LiveSnapshot live = liveSnapshot(scope);
        BudgetSnapshot available = budgetSnapshot(scope);
        for (SupportPlan support : supports) {
            budget.checkpoint();
            ValidationResult physical = validator.validate(flowed, live, layout);
            if (!physical.valid()) {
                diagnostics.addAll(physical.diagnostics());
                continue;
            }
            ServiceBundle bundle = bundle(layout, flowed, support);
            BudgetSelection selection = budgetPlanner.select(bundle, available);
            if (!selection.hasSelection()) {
                diagnostics.addAll(selection.diagnostics());
                continue;
            }
            SimulationResult simulation = simulationEvaluator.evaluate(
                flowed, scope.capabilities(), budget);
            float throughput = simulatedThroughput(layout, support, simulation);
            if (!Float.isFinite(throughput) || throughput <= EPSILON) {
                diagnostics.add(PlannerDiagnostic.of(DiagnosticCode.NO_ROUTE));
                continue;
            }
            bundle = bundle.withQout(throughput);
            ObjectiveValue objective = objective(layout, bundle);
            plans.add(new EvaluatedMiningPlan(
                layout,
                flowed,
                support,
                bundle,
                simulation,
                objective,
                scope.modelComplete() && simulation.fidelity() != SimulationFidelity.UNSUPPORTED
            ));
        }

        PlanObjectiveOrder order = PlanObjectiveOrder.forProfile(scope.request().profile());
        plans.sort((first, second) -> order.bestFirst().compare(first.objective(), second.objective()));
        return new Evaluation(plans, List.copyOf(diagnostics));
    }

    private LiveSnapshot liveSnapshot(PatchSearchScope scope) {
        return new LiveSnapshot(
            scope.terrain(),
            scope.inventory(),
            scope.capabilities(),
            scope.request().budgetMode() == PlannerRequest.BudgetMode.INFINITE_RESOURCES
        );
    }

    private BudgetSnapshot budgetSnapshot(PatchSearchScope scope) {
        return new BudgetSnapshot(
            scope.inventory(),
            scope.request().budgetMode() == PlannerRequest.BudgetMode.INFINITE_RESOURCES
        );
    }

    private ServiceBundle bundle(MiningLayout layout, PlanGraph graph, SupportPlan support) {
        PlanCost total = graph.cost();
        Set<String> drillIds = new LinkedHashSet<>();
        for (DrillCandidate candidate : layout.candidates()) {
            drillIds.add(candidate.id());
            total = total.plus(new PlanCost(candidate.cost(), candidate.footprint().tiles().size(), 1));
        }
        Map<String, Set<String>> bridgeEndpoints = new LinkedHashMap<>();
        for (PlanEdge edge : graph.edges()) {
            if (edge.kind() == EdgeKind.BRIDGE_EDGE) {
                bridgeEndpoints.put(edge.id(), Set.of(edge.from(), edge.to()));
            }
        }
        Set<String> required = dependencyNames(support.dependencies());
        Set<String> provided = new LinkedHashSet<>(required);
        if (!support.variantId().equals("none")) provided.add(support.variantId());
        return ServiceBundle.of(
            "bundle:" + drillIds,
            support.finalQout(),
            total,
            drillIds,
            drillIds,
            bridgeEndpoints,
            required,
            provided
        );
    }

    private float simulatedThroughput(
        MiningLayout layout,
        SupportPlan support,
        SimulationResult simulation
    ) {
        float throughput = support.finalQout();
        if (simulation.fidelity() != SimulationFidelity.UNSUPPORTED) {
            throughput = Math.min(throughput, simulation.steadyStateQout());
        }
        float targetFraction = 1f;
        for (DrillCandidate candidate : layout.candidates()) {
            targetFraction = Math.min(
                targetFraction,
                candidate.production().perSecond() / candidate.production().transportPerSecond()
            );
        }
        return Math.min(layout.productionPerSecond(), throughput * targetFraction);
    }

    private ObjectiveValue objective(MiningLayout layout, ServiceBundle bundle) {
        Set<TileKey> covered = new LinkedHashSet<>();
        for (DrillCandidate candidate : layout.candidates()) {
            covered.addAll(candidate.coveredOreCells());
        }
        return new ObjectiveValue(
            bundle.qout(),
            covered.size(),
            bundle.cost().materials(),
            bundle.cost().space(),
            bundle.cost().complexity(),
            bundle.id()
        );
    }

    private float transportProduction(MiningLayout layout) {
        float production = 0f;
        for (DrillCandidate drill : layout.candidates()) {
            production += drill.production().transportPerSecond();
        }
        return production;
    }

    private Set<String> dependencyNames(Set<DependencyKind> dependencies) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (DependencyKind dependency : dependencies) names.add(dependency.name().toLowerCase());
        return names;
    }

    public record Evaluation(List<EvaluatedMiningPlan> plans, List<PlannerDiagnostic> diagnostics) {
        public Evaluation {
            plans = List.copyOf(plans);
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
